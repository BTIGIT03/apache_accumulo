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
package org.apache.accumulo.server.security.handler;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.NamespaceNotFoundException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.clientImpl.Namespace;
import org.apache.accumulo.core.clientImpl.thrift.SecurityErrorCode;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.security.NamespacePermission;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.server.ServerContext;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKPermHandler implements PermissionHandler {
  private static final Logger log = LoggerFactory.getLogger(ZKPermHandler.class);

  private ServerContext ctx;
  private ZooReaderWriter zoo;
  private static final String ZKUserSysPerms = "/System";
  private static final String ZKUserTablePerms = "/Tables";
  private static final String ZKUserNamespacePerms = "/Namespaces";

  @Override
  public void initialize(ServerContext context) {
    this.ctx = context;
    zoo = context.getZooSession().asReaderWriter();
  }

  @Override
  public boolean hasTablePermission(String user, String table, TablePermission permission)
      throws TableNotFoundException {
    byte[] serializedPerms;
    try {
      String path = Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table;
      zoo.sync(path);
      serializedPerms = zoo.getData(path);
    } catch (KeeperException e) {
      if (e.code() == Code.NONODE) {
        // maybe the table was just deleted?
        try {
          // check for existence:
          zoo.getData(Constants.ZTABLES + "/" + table);
          // it's there, you don't have permission
          return false;
        } catch (InterruptedException ex) {
          log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
          return false;
        } catch (KeeperException ex) {
          // not there, throw an informative exception
          if (e.code() == Code.NONODE) {
            throw new TableNotFoundException(null, table, "while checking permissions");
          }
          log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
        }
        return false;
      }
      log.warn("Unhandled KeeperException, failing closed for table permission check", e);
      return false;
    } catch (InterruptedException e) {
      log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
      return false;
    }
    if (serializedPerms != null) {
      return ZKSecurityTool.convertTablePermissions(serializedPerms).contains(permission);
    }
    return false;
  }

  @Override
  public boolean hasCachedTablePermission(String user, String table, TablePermission permission) {
    byte[] serializedPerms =
        ctx.getZooCache().get(Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table);
    if (serializedPerms != null) {
      return ZKSecurityTool.convertTablePermissions(serializedPerms).contains(permission);
    }
    return false;
  }

  @Override
  public boolean hasNamespacePermission(String user, String namespace,
      NamespacePermission permission) throws NamespaceNotFoundException {
    byte[] serializedPerms;
    try {
      String path = Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace;
      zoo.sync(path);
      serializedPerms = zoo.getData(path);
    } catch (KeeperException e) {
      if (e.code() == Code.NONODE) {
        // maybe the namespace was just deleted?
        try {
          // check for existence:
          zoo.getData(Constants.ZNAMESPACES + "/" + namespace);
          // it's there, you don't have permission
          return false;
        } catch (InterruptedException ex) {
          log.warn("Unhandled InterruptedException, failing closed for namespace permission check",
              e);
          return false;
        } catch (KeeperException ex) {
          // not there, throw an informative exception
          if (e.code() == Code.NONODE) {
            throw new NamespaceNotFoundException(namespace, null, "while checking permissions");
          }
          log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
        }
        return false;
      }
      log.warn("Unhandled KeeperException, failing closed for table permission check", e);
      return false;
    } catch (InterruptedException e) {
      log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
      return false;
    }
    if (serializedPerms != null) {
      return ZKSecurityTool.convertNamespacePermissions(serializedPerms).contains(permission);
    }
    return false;
  }

  @Override
  public boolean hasCachedNamespacePermission(String user, String namespace,
      NamespacePermission permission) {
    byte[] serializedPerms = ctx.getZooCache()
        .get(Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace);
    if (serializedPerms != null) {
      return ZKSecurityTool.convertNamespacePermissions(serializedPerms).contains(permission);
    }
    return false;
  }

  @Override
  public void grantSystemPermission(String user, SystemPermission permission)
      throws AccumuloSecurityException {
    final String sysPermPath = Constants.ZUSERS + "/" + user + ZKUserSysPerms;
    try {
      byte[] permBytes = ctx.getZooCache().get(sysPermPath);
      Set<SystemPermission> perms;
      if (permBytes == null) {
        perms = new TreeSet<>();
      } else {
        perms = ZKSecurityTool.convertSystemPermissions(permBytes);
      }

      if (perms.add(permission)) {
        ctx.getZooCache().clear(sysPermPath);
        zoo.putPersistentData(sysPermPath, ZKSecurityTool.convertSystemPermissions(perms),
            NodeExistsPolicy.OVERWRITE);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void grantTablePermission(String user, String table, TablePermission permission)
      throws AccumuloSecurityException {
    Set<TablePermission> tablePerms;
    final String tablePermPath = Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table;
    byte[] serializedPerms = ctx.getZooCache().get(tablePermPath);
    if (serializedPerms != null) {
      tablePerms = ZKSecurityTool.convertTablePermissions(serializedPerms);
    } else {
      tablePerms = new TreeSet<>();
    }

    try {
      if (tablePerms.add(permission)) {
        ctx.getZooCache().clear(tablePermPath);
        zoo.putPersistentData(tablePermPath, ZKSecurityTool.convertTablePermissions(tablePerms),
            NodeExistsPolicy.OVERWRITE);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void grantNamespacePermission(String user, String namespace,
      NamespacePermission permission) throws AccumuloSecurityException {
    final String nsPermPath =
        Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace;
    Set<NamespacePermission> namespacePerms;
    byte[] serializedPerms = ctx.getZooCache().get(nsPermPath);
    if (serializedPerms != null) {
      namespacePerms = ZKSecurityTool.convertNamespacePermissions(serializedPerms);
    } else {
      namespacePerms = new TreeSet<>();
    }

    try {
      if (namespacePerms.add(permission)) {
        ctx.getZooCache().clear(nsPermPath);
        zoo.putPersistentData(nsPermPath,
            ZKSecurityTool.convertNamespacePermissions(namespacePerms), NodeExistsPolicy.OVERWRITE);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void revokeSystemPermission(String user, SystemPermission permission)
      throws AccumuloSecurityException {
    final String sysPermPath = Constants.ZUSERS + "/" + user + ZKUserSysPerms;
    byte[] sysPermBytes = ctx.getZooCache().get(sysPermPath);

    // User had no system permission, nothing to revoke.
    if (sysPermBytes == null) {
      return;
    }

    Set<SystemPermission> sysPerms = ZKSecurityTool.convertSystemPermissions(sysPermBytes);

    try {
      if (sysPerms.remove(permission)) {
        ctx.getZooCache().clear((path) -> path.startsWith(sysPermPath));
        zoo.putPersistentData(sysPermPath, ZKSecurityTool.convertSystemPermissions(sysPerms),
            NodeExistsPolicy.OVERWRITE);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void revokeTablePermission(String user, String table, TablePermission permission)
      throws AccumuloSecurityException {
    final String tablePermPath = Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table;
    byte[] serializedPerms = ctx.getZooCache().get(tablePermPath);

    // User had no table permission, nothing to revoke.
    if (serializedPerms == null) {
      return;
    }

    Set<TablePermission> tablePerms = ZKSecurityTool.convertTablePermissions(serializedPerms);
    try {
      if (tablePerms.remove(permission)) {
        ctx.getZooCache().clear((path) -> path.startsWith(tablePermPath));
        if (tablePerms.isEmpty()) {
          zoo.recursiveDelete(tablePermPath, NodeMissingPolicy.SKIP);
        } else {
          zoo.putPersistentData(tablePermPath, ZKSecurityTool.convertTablePermissions(tablePerms),
              NodeExistsPolicy.OVERWRITE);
        }
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void revokeNamespacePermission(String user, String namespace,
      NamespacePermission permission) throws AccumuloSecurityException {
    final String nsPermPath =
        Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace;
    byte[] serializedPerms = ctx.getZooCache().get(nsPermPath);

    // User had no namespace permission, nothing to revoke.
    if (serializedPerms == null) {
      return;
    }

    Set<NamespacePermission> namespacePerms =
        ZKSecurityTool.convertNamespacePermissions(serializedPerms);
    try {
      if (namespacePerms.remove(permission)) {
        ctx.getZooCache().clear((path) -> path.startsWith(nsPermPath));
        if (namespacePerms.isEmpty()) {
          zoo.recursiveDelete(nsPermPath, NodeMissingPolicy.SKIP);
        } else {
          zoo.putPersistentData(nsPermPath,
              ZKSecurityTool.convertNamespacePermissions(namespacePerms),
              NodeExistsPolicy.OVERWRITE);
        }
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void cleanTablePermissions(String table) throws AccumuloSecurityException {
    try {
      for (String user : ctx.getZooCache().getChildren(Constants.ZUSERS)) {
        final String tablePermPath = Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table;
        ctx.getZooCache().clear((path) -> path.startsWith(tablePermPath));
        zoo.recursiveDelete(tablePermPath, NodeMissingPolicy.SKIP);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException("unknownUser", SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void cleanNamespacePermissions(String namespace) throws AccumuloSecurityException {
    try {
      for (String user : ctx.getZooCache().getChildren(Constants.ZUSERS)) {
        final String nsPermPath =
            Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace;
        ctx.getZooCache().clear((path) -> path.startsWith(nsPermPath));
        zoo.recursiveDelete(nsPermPath, NodeMissingPolicy.SKIP);
      }
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException("unknownUser", SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void initializeSecurity(TCredentials itw, String rootuser)
      throws AccumuloSecurityException {

    // create the root user with all system privileges, no table privileges, and no record-level
    // authorizations
    Set<SystemPermission> rootPerms = new TreeSet<>();
    Collections.addAll(rootPerms, SystemPermission.values());
    Map<TableId,Set<TablePermission>> tablePerms = new HashMap<>();
    // Allow the root user to flush the system tables
    tablePerms.put(SystemTables.ROOT.tableId(), Collections.singleton(TablePermission.ALTER_TABLE));
    tablePerms.put(SystemTables.METADATA.tableId(),
        Collections.singleton(TablePermission.ALTER_TABLE));
    // essentially the same but on the system namespace, the ALTER_TABLE permission is now redundant
    Map<NamespaceId,Set<NamespacePermission>> namespacePerms = new HashMap<>();
    namespacePerms.put(Namespace.ACCUMULO.id(),
        Collections.singleton(NamespacePermission.ALTER_NAMESPACE));
    namespacePerms.put(Namespace.ACCUMULO.id(),
        Collections.singleton(NamespacePermission.ALTER_TABLE));

    try {
      // prep parent node of users with root username
      if (!zoo.exists(Constants.ZUSERS)) {
        zoo.putPersistentData(Constants.ZUSERS, rootuser.getBytes(UTF_8), NodeExistsPolicy.FAIL);
      }

      initUser(rootuser);
      zoo.putPersistentData(Constants.ZUSERS + "/" + rootuser + ZKUserSysPerms,
          ZKSecurityTool.convertSystemPermissions(rootPerms), NodeExistsPolicy.FAIL);
      for (Entry<TableId,Set<TablePermission>> entry : tablePerms.entrySet()) {
        createTablePerm(rootuser, entry.getKey(), entry.getValue());
      }
      for (Entry<NamespaceId,Set<NamespacePermission>> entry : namespacePerms.entrySet()) {
        createNamespacePerm(rootuser, entry.getKey(), entry.getValue());
      }
    } catch (KeeperException | InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void initUser(String user) throws AccumuloSecurityException {
    try {
      zoo.putPersistentData(Constants.ZUSERS + "/" + user, new byte[0], NodeExistsPolicy.SKIP);
      zoo.putPersistentData(Constants.ZUSERS + "/" + user + ZKUserTablePerms, new byte[0],
          NodeExistsPolicy.SKIP);
      zoo.putPersistentData(Constants.ZUSERS + "/" + user + ZKUserNamespacePerms, new byte[0],
          NodeExistsPolicy.SKIP);
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  /**
   * Sets up a new table configuration for the provided user/table. No checking for existence is
   * done here, it should be done before calling.
   */
  private void createTablePerm(String user, TableId table, Set<TablePermission> perms)
      throws KeeperException, InterruptedException {
    final String tablePermPath = Constants.ZUSERS + "/" + user + ZKUserTablePerms + "/" + table;
    ctx.getZooCache().clear((path) -> path.startsWith(tablePermPath));
    zoo.putPersistentData(tablePermPath, ZKSecurityTool.convertTablePermissions(perms),
        NodeExistsPolicy.FAIL);
  }

  /**
   * Sets up a new namespace configuration for the provided user/table. No checking for existence is
   * done here, it should be done before calling.
   */
  private void createNamespacePerm(String user, NamespaceId namespace,
      Set<NamespacePermission> perms) throws KeeperException, InterruptedException {
    final String nsPermPath =
        Constants.ZUSERS + "/" + user + ZKUserNamespacePerms + "/" + namespace;
    ctx.getZooCache().clear((path) -> path.startsWith(nsPermPath));
    zoo.putPersistentData(nsPermPath, ZKSecurityTool.convertNamespacePermissions(perms),
        NodeExistsPolicy.FAIL);
  }

  @Override
  public void cleanUser(String user) throws AccumuloSecurityException {
    try {
      zoo.recursiveDelete(Constants.ZUSERS + "/" + user + ZKUserSysPerms, NodeMissingPolicy.SKIP);
      zoo.recursiveDelete(Constants.ZUSERS + "/" + user + ZKUserTablePerms, NodeMissingPolicy.SKIP);
      zoo.recursiveDelete(Constants.ZUSERS + "/" + user + ZKUserNamespacePerms,
          NodeMissingPolicy.SKIP);
      ctx.getZooCache().clear((path) -> path.startsWith(Constants.ZUSERS + "/" + user));
    } catch (InterruptedException e) {
      log.error("{}", e.getMessage(), e);
      throw new IllegalStateException(e);
    } catch (KeeperException e) {
      log.error("{}", e.getMessage(), e);
      if (e.code().equals(KeeperException.Code.NONODE)) {
        throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST, e);
      }
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);

    }
  }

  @Override
  public boolean hasSystemPermission(String user, SystemPermission permission) {
    byte[] perms;
    try {
      String path = Constants.ZUSERS + "/" + user + ZKUserSysPerms;
      zoo.sync(path);
      perms = zoo.getData(path);
    } catch (KeeperException e) {
      if (e.code() == Code.NONODE) {
        return false;
      }
      log.warn("Unhandled KeeperException, failing closed for table permission check", e);
      return false;
    } catch (InterruptedException e) {
      log.warn("Unhandled InterruptedException, failing closed for table permission check", e);
      return false;
    }

    if (perms == null) {
      return false;
    }
    return ZKSecurityTool.convertSystemPermissions(perms).contains(permission);
  }

  @Override
  public boolean hasCachedSystemPermission(String user, SystemPermission permission) {
    byte[] perms = ctx.getZooCache().get(Constants.ZUSERS + "/" + user + ZKUserSysPerms);
    if (perms == null) {
      return false;
    }
    return ZKSecurityTool.convertSystemPermissions(perms).contains(permission);
  }

  @Override
  public boolean validSecurityHandlers(Authenticator authent, Authorizor author) {
    return true;
  }

}
