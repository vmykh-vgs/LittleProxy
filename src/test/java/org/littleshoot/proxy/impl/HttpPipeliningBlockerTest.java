package org.littleshoot.proxy.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.concurrent.EventExecutor;
import org.junit.Test;

public class HttpPipeliningBlockerTest {
  private final HttpPipeliningBlocker httpPipeliningBlocker = new HttpPipeliningBlocker();

  private final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
  private final EventExecutor eventExecutor = mock(EventExecutor.class);
  private final ChannelPromise promise = mock(ChannelPromise.class);
  private final FullHttpRequest request1 = mock(FullHttpRequest.class, "request1");
  private final FullHttpRequest request2 = mock(FullHttpRequest.class, "request2");
  private final FullHttpResponse response1 = mock(FullHttpResponse.class, "response1");

  @Test
  public void doesntStartProcessingOfTheNextRequestIfThePreviousOneHasntBeenProcessedYet() {
    httpPipeliningBlocker.channelRead(ctx, request1);
    httpPipeliningBlocker.channelRead(ctx, request2);

    verify(ctx, times(1)).fireChannelRead(request1);
    verify(ctx, times(0)).fireChannelRead(request2);
    verifyNoMoreInteractions(ctx);
  }

  @Test
  public void ignoresMessageIfItIsNotFullHttpRequest() {
    httpPipeliningBlocker.channelRead(ctx, "a string");
    verify(ctx, times(0)).fireChannelRead(any());
  }

  @Test
  public void ignoresMessageIfItIsNull() {
    httpPipeliningBlocker.channelRead(ctx, null);
    verify(ctx, times(0)).fireChannelRead(any());
  }

  @Test
  public void triggersProcessingOfTheNextRequestInTheQueueIfThereIsAny() {
    when(ctx.executor()).thenReturn(eventExecutor);
    doAnswer(invocation -> {
      Runnable task = invocation.getArgument(0);
      task.run();
      return null;
    }).when(eventExecutor).execute(any(Runnable.class));

    httpPipeliningBlocker.channelRead(ctx, request1);
    httpPipeliningBlocker.channelRead(ctx, request2);
    httpPipeliningBlocker.write(ctx, response1, promise);

    verify(ctx, times(1)).executor();
    verify(ctx, times(1)).fireChannelRead(request2);
  }

  @Test
  public void doesntUseEventExecutorIfQueueIsEmpty() {
    httpPipeliningBlocker.channelRead(ctx, request1);
    httpPipeliningBlocker.write(ctx, response1, promise);

    verify(ctx, times(0)).executor();
  }

  @Test
  public void doesntTriggerProcessingOfTheNextRequestInTheQueueIfResponseMessageIsNotInstanceOfFullHttpResponse() {
    httpPipeliningBlocker.channelRead(ctx, request1);
    httpPipeliningBlocker.write(ctx, "a string", promise);

    verify(ctx, times(0)).executor();
  }

  @Test
  public void doesntAllowProcessingOfTheNextReceivedRequestIfResponseMessageIsNotInstanceOfFullHttpResponse() {
    httpPipeliningBlocker.channelRead(ctx, request1);
    httpPipeliningBlocker.write(ctx, "a string", promise);

    httpPipeliningBlocker.channelRead(ctx, request2);
    verify(ctx, times(0)).fireChannelRead(request2);
  }
}