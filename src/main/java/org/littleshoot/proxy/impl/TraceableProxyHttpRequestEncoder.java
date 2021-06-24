package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;

public class TraceableProxyHttpRequestEncoder extends HttpRequestEncoder {

  @Override
  protected void sanitizeHeadersBeforeEncode(HttpRequest msg, boolean isAlwaysEmpty) {
    HttpHeaders headers = msg.headers();

    String headerVgsCustomerB3TraceId = headers.get(TraceUtils.HEADER_VGS_CUSTOMER_B3_TRACEID);

    // need to remove this header no matter what
    headers.remove(TraceUtils.HEADER_VGS_CUSTOMER_B3_TRACEID);

    if (headerVgsCustomerB3TraceId == null) {
      // looks like all tracing header were injected by VGS so removing them
      headers.remove(TraceUtils.HEADER_X_B3_TRACEID);
      headers.remove(TraceUtils.HEADER_X_B3_SPANID);
      headers.remove(TraceUtils.HEADER_X_B3_SAMPLED);
      headers.remove(TraceUtils.HEADER_X_B3_PARENTSPANID);
    } else {
      // preserving "X-B3-TraceId" header
      headers.set(TraceUtils.HEADER_X_B3_TRACEID, headerVgsCustomerB3TraceId);
    }
  }
}
