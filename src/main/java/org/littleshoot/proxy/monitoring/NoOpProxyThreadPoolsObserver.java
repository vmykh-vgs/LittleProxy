package org.littleshoot.proxy.monitoring;

import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.impl.ProxyThreadPools;

public class NoOpProxyThreadPoolsObserver implements ProxyThreadPoolsObserver {

  @Override
  public void observe(TransportProtocol protocol, ProxyThreadPools proxyThreadPools) {

  }
}
