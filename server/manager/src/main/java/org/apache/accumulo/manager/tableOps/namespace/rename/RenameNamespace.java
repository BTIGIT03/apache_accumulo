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
package org.apache.accumulo.manager.tableOps.namespace.rename;

import org.apache.accumulo.core.clientImpl.thrift.TableOperation;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.Repo;
import org.apache.accumulo.core.fate.zookeeper.DistributedReadWriteLock.LockType;
import org.apache.accumulo.manager.Manager;
import org.apache.accumulo.manager.tableOps.ManagerRepo;
import org.apache.accumulo.manager.tableOps.Utils;
import org.slf4j.LoggerFactory;

public class RenameNamespace extends ManagerRepo {

  private static final long serialVersionUID = 1L;
  private final NamespaceId namespaceId;
  private final String oldName;
  private final String newName;

  @Override
  public long isReady(FateId fateId, Manager environment) throws Exception {
    return Utils.reserveNamespace(environment, namespaceId, fateId, LockType.WRITE, true,
        TableOperation.RENAME);
  }

  public RenameNamespace(NamespaceId namespaceId, String oldName, String newName) {
    this.namespaceId = namespaceId;
    this.oldName = oldName;
    this.newName = newName;
  }

  @Override
  public Repo<Manager> call(FateId fateId, Manager manager) throws Exception {

    Utils.getTableNameLock().lock();
    try {
      manager.getContext().getNamespaceMapping().rename(namespaceId, oldName, newName);
      manager.getContext().clearTableListCache();
    } finally {
      Utils.getTableNameLock().unlock();
      Utils.unreserveNamespace(manager, namespaceId, fateId, LockType.WRITE);
    }

    LoggerFactory.getLogger(RenameNamespace.class).debug("Renamed namespace {} {} {}", namespaceId,
        oldName, newName);

    return null;
  }

  @Override
  public void undo(FateId fateId, Manager env) {
    Utils.unreserveNamespace(env, namespaceId, fateId, LockType.WRITE);
  }

}
