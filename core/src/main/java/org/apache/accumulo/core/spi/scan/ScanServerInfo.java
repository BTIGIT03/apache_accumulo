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
package org.apache.accumulo.core.spi.scan;

import org.apache.accumulo.core.data.ResourceGroupId;

/**
 * Information about a scan server.
 *
 * @since 2.1.0
 */
public interface ScanServerInfo {

  /**
   * @return the address in the form of {@code <host>:<port>} where the scan server is running.
   */
  String getAddress();

  /**
   * @return the group set when the scan server was started. If a group name was not set for the
   *         scan server, then {@code ResourceGroupId#DEFAULT} is returned.
   */
  ResourceGroupId getGroup();

}
