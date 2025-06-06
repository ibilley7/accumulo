/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.rpc;

import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.thrift.server.TServer;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;

/**
 * Encapsulate a Thrift server and the address, host and port, to which it is bound.
 */
public class ServerAddress {
  public final TServer server;
  public final HostAndPort address;

  public ServerAddress(TServer server, HostAndPort address) {
    this.server = server;
    this.address = address;
  }

  public TServer getServer() {
    return server;
  }

  public HostAndPort getAddress() {
    return address;
  }

  public void startThriftServer(String threadName) {
    Threads.createCriticalThread(threadName, server::serve).start();

    while (!server.isServing()) {
      // Wait for the thread to start and for the TServer to start
      // serving events
      UtilWaitThread.sleep(10);
      Preconditions.checkState(!server.getShouldStop());
    }

  }
}
