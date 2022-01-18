package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates the various steps involved in establishing a connection, such as
 * establishing a socket connection, SSL handshaking, HTTP CONNECT request
 * processing, and so on.
 */
class ConnectionFlow {
    private Queue<ConnectionFlowStep> steps = new ConcurrentLinkedQueue<ConnectionFlowStep>();

    private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection;
    private volatile ConnectionFlowStep currentStep;
    private volatile boolean suppressInitialRequest = false;
    private final Object connectLock;
    private final Map<Future<Channel>, GenericFutureListener<Future<?>>> listeners = new HashMap();

    /**
     * Construct a new {@link ConnectionFlow} for the given client and server
     * connections.
     * 
     * @param clientConnection
     * @param serverConnection
     * @param connectLock
     *            an object that's shared by {@link ConnectionFlow} and
     *            {@link ProxyToServerConnection} and that is used for
     *            synchronizing the reader and writer threads that are both
     *            involved during the establishing of a connection.
     */
    ConnectionFlow(
            ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection,
            Object connectLock) {
        super();
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.connectLock = connectLock;
    }

    /**
     * Add a {@link ConnectionFlowStep} to this flow.
     * 
     * @param step
     * @return
     */
    ConnectionFlow then(ConnectionFlowStep step) {
        steps.add(step);
        return this;
    }

    /**
     * While we're in the process of connecting, any messages read by the
     * {@link ProxyToServerConnection} are passed to this method, which passes
     * it on to {@link ConnectionFlowStep#read(ConnectionFlow, Object)} for the
     * current {@link ConnectionFlowStep}.
     * 
     * @param msg
     */
    void read(Object msg) {
        if (this.currentStep != null) {
            this.currentStep.read(this, msg);
        }
    }

    /**
     * Starts the connection flow, notifying the {@link ClientToProxyConnection}
     * that we've started.
     */
    void start() {
        clientConnection.serverConnectionFlowStarted(serverConnection);
        advance();
    }

    /**
     * <p>
     * Advances the flow. {@link #advance()} will be called until we're either
     * out of steps, or a step has failed.
     * </p>
     */
    void advance() {
        currentStep = steps.poll();
        if (currentStep == null) {
            succeed();
        } else {
            processCurrentStep();
        }
    }

    /**
     * <p>
     * Process the current {@link ConnectionFlowStep}. With each step, we:
     * </p>
     * 
     * <ol>
     * <li>Change the state of the associated {@link ProxyConnection} to the
     * value of {@link ConnectionFlowStep#getState()}</li>
     * <li>Call {@link ConnectionFlowStep#execute()}</li>
     * <li>On completion of the {@link Future} returned by
     * {@link ConnectionFlowStep#execute()}, check the success.</li>
     * <li>If successful, we call back into
     * {@link ConnectionFlowStep#onSuccess(ConnectionFlow)}.</li>
     * <li>If unsuccessful, we call {@link #fail()}, stopping the connection
     * flow</li>
     * </ol>
     */
    private void processCurrentStep() {
        final ProxyConnection connection = currentStep.getConnection();
        final ProxyConnectionLogger LOG = connection.getLOG();

        LOG.debug("Processing connection flow step: {}", currentStep);
        connection.become(currentStep.getState());
        suppressInitialRequest = suppressInitialRequest
                || currentStep.shouldSuppressInitialRequest();

        if (currentStep.shouldExecuteOnEventLoop()) {
            connection.ctx.executor().submit(new Runnable() {
                @Override
                public void run() {
                    doProcessCurrentStep(LOG);
                }
            });
        } else {
            doProcessCurrentStep(LOG);
        }
    }

    /**
     * Does the work of processing the current step, checking the result and
     * handling success/failure.
     * 
     * @param LOG
     */
    @SuppressWarnings("unchecked")
    private void doProcessCurrentStep(final ProxyConnectionLogger LOG) {
        GenericFutureListener<Future<?>> futureListener = new GenericFutureListener<Future<?>>() {
            public void operationComplete(
                Future<?> future)
                throws Exception {
                synchronized (connectLock) {
                    if (future.isSuccess()) {
                        LOG.debug("ConnectionFlowStep succeeded");
                        currentStep
                            .onSuccess(ConnectionFlow.this);
                    } else {
                        LOG.debug("ConnectionFlowStep failed",
                            future.cause());
                        fail(future.cause());
                    }
                }
            }

            ;
        };
        Future stepFuture = currentStep.execute();
        stepFuture.addListener(
            futureListener);
        listeners.put(stepFuture, futureListener);
    }

    /**
     * Called when the flow is complete and successful. Notifies the
     * {@link ProxyToServerConnection} that we succeeded.
     */
    void succeed() {
        synchronized (connectLock) {
            try {
                serverConnection.getLOG().debug(
                    "Connection flow completed successfully: {}", currentStep);
                serverConnection.connectionSucceeded(!suppressInitialRequest);
                notifyThreadsWaitingForConnection();
            } finally {
                // we're now done with the initialRequest: it's either been forwarded to the upstream server (HTTP requests), or
                // completely dropped (HTTPS CONNECTs). if the initialRequest is reference counted (typically because the HttpObjectAggregator is in
                // the pipeline to generate FullHttpRequests), we need to manually release it to avoid a memory leak.
                if (serverConnection.getInitialRequest() instanceof ReferenceCounted) {
                    ((ReferenceCounted)serverConnection.getInitialRequest()).release();
                }
                removeListeners();
            }
        }
    }

    /**
     * Called when the flow fails at some {@link ConnectionFlowStep}.
     * Disconnects the {@link ProxyToServerConnection} and informs the
     * {@link ClientToProxyConnection} that our connection failed.
     */
    @SuppressWarnings("unchecked")
    void fail(final Throwable cause) {
        final ConnectionState lastStateBeforeFailure = serverConnection
                .getCurrentState();
        Future disconnectFuture = serverConnection.disconnect();
        GenericFutureListener listener = new GenericFutureListener() {
            @Override
            public void operationComplete(Future future)
                throws Exception {
                synchronized (connectLock) {

                    boolean fallbackToAnotherChainedProxy = false;

                    try {
                        fallbackToAnotherChainedProxy = clientConnection.serverConnectionFailed(
                            serverConnection,
                            lastStateBeforeFailure,
                            cause);
                    } finally {
                        // Do not release when there is fallback chained proxy
                        if (!fallbackToAnotherChainedProxy) {
                            if (serverConnection.getInitialRequest() instanceof ReferenceCounted) {
                                ((ReferenceCounted) serverConnection.getInitialRequest()).release();
                            }

                            // the connection to the server failed and we are not retrying, so transition to the
                            // DISCONNECTED state
                            serverConnection.become(ConnectionState.DISCONNECTED);

                            // We are not retrying our connection, let anyone waiting for a connection know that we're done
                            notifyThreadsWaitingForConnection();
                        }
                    }
                }
            }
        };
        disconnectFuture.addListener(
            listener);
        listeners.put(disconnectFuture, listener);
    }

    /**
     * Like {@link #fail(Throwable)} but with no cause.
     */
    void fail() {
        removeListeners();
        fail(null);
    }

    private void removeListeners() {
        for (Future future : listeners.keySet()) {
            future.removeListener(listeners.get(future));
        }
    }

    /**
     * Once we've finished recording our connection and written our initial
     * request, we can notify anyone who is waiting on the connection that it's
     * okay to proceed.
     */
    private void notifyThreadsWaitingForConnection() {
        connectLock.notifyAll();
    }

}
