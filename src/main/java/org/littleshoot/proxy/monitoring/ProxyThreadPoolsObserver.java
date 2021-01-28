package org.littleshoot.proxy.monitoring;

import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.impl.ProxyThreadPools;

public interface ProxyThreadPoolsObserver {
  void observe(TransportProtocol protocol, ProxyThreadPools proxyThreadPools);
}
