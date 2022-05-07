package org.littleshoot.proxy.impl;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.LinkedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HttpPipeliningBlocker extends ChannelDuplexHandler {
  private static final Logger logger = LoggerFactory.getLogger(HttpPipeliningBlocker.class);

  private final LinkedList<FullHttpRequest> requestQueue = new LinkedList<>();
  private boolean requestIsBeingProcessed = false;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!(msg instanceof FullHttpRequest)) {
      String messageType = msg != null ? msg.getClass().getName() : "null";
      logger.warn("Unexpected message type: {}. Ignoring the message.", messageType);
      return;
    }

    FullHttpRequest request = (FullHttpRequest) msg;
    if (!requestIsBeingProcessed && requestQueue.isEmpty()) {
      processRequest(ctx, request);
    } else {
      requestQueue.addLast(request);
      logger.debug("One of the previous requests is already being processed. Added current request to the queue. "
          + "Queue size: {}", requestQueue.size());
    }
  }

  private void processRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
    requestIsBeingProcessed = true;
    ctx.fireChannelRead(request);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    ctx.write(msg, promise);
    if (!(msg instanceof FullHttpResponse)) {
      return;
    }
    requestIsBeingProcessed = false;
    if (!requestQueue.isEmpty()) {
      ctx.executor().execute(() -> {
        if (!requestIsBeingProcessed && !requestQueue.isEmpty()) {
          FullHttpRequest request = requestQueue.pollFirst();
          logger.debug("Polled next request from the queue for processing. Queue size: {}", requestQueue.size());
          processRequest(ctx, request);
        }
      });
    }
  }
}
