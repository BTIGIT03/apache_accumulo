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
package org.apache.accumulo.core.clientImpl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.AVAILABILITY;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.DIR;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.ECOMP;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.FILES;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.HOSTING_REQUESTED;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.LAST;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.LOCATION;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.LOGS;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.MERGEABILITY;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.OPID;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.PREV_ROW;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.SUSPEND;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.TIME;
import static org.apache.accumulo.core.util.LazySingletons.RANDOM;
import static org.apache.accumulo.core.util.Validators.EXISTING_TABLE_NAME;
import static org.apache.accumulo.core.util.Validators.NEW_TABLE_NAME;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.SPLIT_START_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.SPLIT_WAIT_POOL;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.InvalidTabletHostingRequestException;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.NamespaceExistsException;
import org.apache.accumulo.core.client.NamespaceNotFoundException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.TableOfflineException;
import org.apache.accumulo.core.client.admin.CloneConfiguration;
import org.apache.accumulo.core.client.admin.CompactionConfig;
import org.apache.accumulo.core.client.admin.DiskUsage;
import org.apache.accumulo.core.client.admin.FindMax;
import org.apache.accumulo.core.client.admin.ImportConfiguration;
import org.apache.accumulo.core.client.admin.Locations;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.SummaryRetriever;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.client.admin.TabletInformation;
import org.apache.accumulo.core.client.admin.TabletMergeability;
import org.apache.accumulo.core.client.admin.TimeType;
import org.apache.accumulo.core.client.admin.compaction.CompactionConfigurer;
import org.apache.accumulo.core.client.admin.compaction.CompactionSelector;
import org.apache.accumulo.core.client.sample.SamplerConfiguration;
import org.apache.accumulo.core.client.summary.SummarizerConfiguration;
import org.apache.accumulo.core.client.summary.Summary;
import org.apache.accumulo.core.clientImpl.ClientTabletCache.CachedTablet;
import org.apache.accumulo.core.clientImpl.ClientTabletCache.LocationNeed;
import org.apache.accumulo.core.clientImpl.bulk.BulkImport;
import org.apache.accumulo.core.clientImpl.thrift.ClientService.Client;
import org.apache.accumulo.core.clientImpl.thrift.SecurityErrorCode;
import org.apache.accumulo.core.clientImpl.thrift.TDiskUsage;
import org.apache.accumulo.core.clientImpl.thrift.TVersionedProperties;
import org.apache.accumulo.core.clientImpl.thrift.TableOperationExceptionType;
import org.apache.accumulo.core.clientImpl.thrift.ThriftNotActiveServiceException;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.clientImpl.thrift.ThriftTableOperationException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.TabletId;
import org.apache.accumulo.core.data.constraints.Constraint;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.dataImpl.TabletIdImpl;
import org.apache.accumulo.core.dataImpl.thrift.TRowRange;
import org.apache.accumulo.core.dataImpl.thrift.TSummaries;
import org.apache.accumulo.core.dataImpl.thrift.TSummarizerConfiguration;
import org.apache.accumulo.core.dataImpl.thrift.TSummaryRequest;
import org.apache.accumulo.core.fate.FateInstanceType;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.manager.thrift.FateService;
import org.apache.accumulo.core.manager.thrift.ManagerClientService;
import org.apache.accumulo.core.manager.thrift.TFateId;
import org.apache.accumulo.core.manager.thrift.TFateInstanceType;
import org.apache.accumulo.core.manager.thrift.TFateOperation;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.TabletState;
import org.apache.accumulo.core.metadata.schema.TabletDeletedException;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.Location;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.LocationType;
import org.apache.accumulo.core.metadata.schema.TabletsMetadata;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.sample.impl.SamplerConfigurationImpl;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.summary.SummarizerConfigurationUtil;
import org.apache.accumulo.core.summary.SummaryCollection;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.LocalityGroupUtil;
import org.apache.accumulo.core.util.LocalityGroupUtil.LocalityGroupConfigurationError;
import org.apache.accumulo.core.util.MapCounter;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.Retry;
import org.apache.accumulo.core.util.TextUtil;
import org.apache.accumulo.core.util.Timer;
import org.apache.accumulo.core.volume.VolumeConfiguration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;

public class TableOperationsImpl extends TableOperationsHelper {

  public static final String PROPERTY_EXCLUDE_PREFIX = "!";
  public static final String COMPACTION_CANCELED_MSG = "Compaction canceled";
  public static final String TABLE_DELETED_MSG = "Table is being deleted";

  private static final Logger log = LoggerFactory.getLogger(TableOperations.class);
  private final ClientContext context;

  public TableOperationsImpl(ClientContext context) {
    checkArgument(context != null, "context is null");
    this.context = context;
  }

  @Override
  public SortedSet<String> list() {

    Timer timer = null;

    if (log.isTraceEnabled()) {
      log.trace("tid={} Fetching list of tables...", Thread.currentThread().getId());
      timer = Timer.startNew();
    }

    var tableNames = new TreeSet<>(context.createQualifiedTableNameToIdMap().keySet());

    if (timer != null) {
      log.trace("tid={} Fetched {} table names in {}", Thread.currentThread().getId(),
          tableNames.size(), String.format("%.3f secs", timer.elapsed(MILLISECONDS) / 1000.0));
    }

    return tableNames;
  }

  @Override
  public boolean exists(String tableName) {
    EXISTING_TABLE_NAME.validate(tableName);

    if (SystemTables.containsTableName(tableName)) {
      return true;
    }

    Timer timer = null;

    if (log.isTraceEnabled()) {
      log.trace("tid={} Checking if table {} exists...", Thread.currentThread().getId(), tableName);
      timer = Timer.startNew();
    }

    boolean exists = false;
    try {
      context.getTableId(tableName);
      exists = true;
    } catch (TableNotFoundException e) {
      /* ignore */
    }

    if (timer != null) {
      log.trace("tid={} Checked existence of {} in {}", Thread.currentThread().getId(), exists,
          String.format("%.3f secs", timer.elapsed(MILLISECONDS) / 1000.0));
    }

    return exists;
  }

  @Override
  public void create(String tableName)
      throws AccumuloException, AccumuloSecurityException, TableExistsException {
    create(tableName, new NewTableConfiguration());
  }

  @Override
  public void create(String tableName, NewTableConfiguration ntc)
      throws AccumuloException, AccumuloSecurityException, TableExistsException {
    NEW_TABLE_NAME.validate(tableName);
    checkArgument(ntc != null, "ntc is null");

    List<ByteBuffer> args = new ArrayList<>();
    args.add(ByteBuffer.wrap(tableName.getBytes(UTF_8)));
    args.add(ByteBuffer.wrap(ntc.getTimeType().name().getBytes(UTF_8)));
    // Send info relating to initial table creation i.e, create online or offline
    args.add(ByteBuffer.wrap(ntc.getInitialTableState().name().getBytes(UTF_8)));
    // send initial tablet availability information
    args.add(ByteBuffer.wrap(ntc.getInitialTabletAvailability().name().getBytes(UTF_8)));
    // Check for possible initial splits to be added at table creation
    // Always send number of initial splits to be created, even if zero. If greater than zero,
    // add the splits to the argument List which will be used by the FATE operations.
    int numSplits = ntc.getSplitsMap().size();
    args.add(ByteBuffer.wrap(String.valueOf(numSplits).getBytes(UTF_8)));
    if (numSplits > 0) {
      for (Entry<Text,TabletMergeability> t : ntc.getSplitsMap().entrySet()) {
        args.add(TabletMergeabilityUtil.encodeAsBuffer(t.getKey(), t.getValue()));
      }
    }

    Map<String,String> opts = ntc.getProperties();

    try {
      doTableFateOperation(tableName, AccumuloException.class, TFateOperation.TABLE_CREATE, args,
          opts);
    } catch (TableNotFoundException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  private TFateId beginFateOperation(TFateInstanceType type) throws TException {
    while (true) {
      FateService.Client client = null;
      try {
        client = ThriftClientTypes.FATE.getConnectionWithRetry(context);
        return client.beginFateOperation(TraceUtil.traceInfo(), context.rpcCreds(), type);
      } catch (TTransportException tte) {
        log.debug("Failed to call beginFateOperation(), retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } catch (ThriftNotActiveServiceException e) {
        // Let it loop, fetching a new location
        log.debug("Contacted a Manager which is no longer active, retrying");
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        ThriftUtil.close(client, context);
      }
    }
  }

  // This method is for retrying in the case of network failures;
  // anything else it passes to the caller to deal with
  private void executeFateOperation(TFateId opid, TFateOperation op, List<ByteBuffer> args,
      Map<String,String> opts, boolean autoCleanUp) throws TException {
    while (true) {
      FateService.Client client = null;
      try {
        client = ThriftClientTypes.FATE.getConnectionWithRetry(context);
        client.executeFateOperation(TraceUtil.traceInfo(), context.rpcCreds(), opid, op, args, opts,
            autoCleanUp);
        return;
      } catch (TTransportException tte) {
        log.debug("Failed to call executeFateOperation(), retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } catch (ThriftNotActiveServiceException e) {
        // Let it loop, fetching a new location
        log.debug("Contacted a Manager which is no longer active, retrying");
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        ThriftUtil.close(client, context);
      }
    }
  }

  private String waitForFateOperation(TFateId opid) throws TException {
    while (true) {
      FateService.Client client = null;
      try {
        client = ThriftClientTypes.FATE.getConnectionWithRetry(context);
        return client.waitForFateOperation(TraceUtil.traceInfo(), context.rpcCreds(), opid);
      } catch (TTransportException tte) {
        log.debug("Failed to call waitForFateOperation(), retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } catch (ThriftNotActiveServiceException e) {
        // Let it loop, fetching a new location
        log.debug("Contacted a Manager which is no longer active, retrying");
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        ThriftUtil.close(client, context);
      }
    }
  }

  private void finishFateOperation(TFateId opid) throws TException {
    while (true) {
      FateService.Client client = null;
      try {
        client = ThriftClientTypes.FATE.getConnectionWithRetry(context);
        client.finishFateOperation(TraceUtil.traceInfo(), context.rpcCreds(), opid);
        break;
      } catch (TTransportException tte) {
        log.debug("Failed to call finishFateOperation(), retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } catch (ThriftNotActiveServiceException e) {
        // Let it loop, fetching a new location
        log.debug("Contacted a Manager which is no longer active, retrying");
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        ThriftUtil.close(client, context);
      }
    }
  }

  public String doBulkFateOperation(List<ByteBuffer> args, String tableName)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    try {
      return doFateOperation(TFateOperation.TABLE_BULK_IMPORT2, args, Collections.emptyMap(),
          tableName);
    } catch (TableExistsException | NamespaceExistsException e) {
      // should not happen
      throw new AssertionError(e);
    } catch (NamespaceNotFoundException ne) {
      throw new TableNotFoundException(null, tableName, "Namespace not found", ne);
    }
  }

  @FunctionalInterface
  public interface FateOperationExecutor<T> {
    T execute() throws Exception;
  }

  private <T> T handleFateOperation(FateOperationExecutor<T> executor, String tableOrNamespaceName)
      throws AccumuloSecurityException, TableExistsException, TableNotFoundException,
      AccumuloException, NamespaceExistsException, NamespaceNotFoundException {
    try {
      return executor.execute();
    } catch (ThriftSecurityException e) {
      switch (e.getCode()) {
        case TABLE_DOESNT_EXIST:
          throw new TableNotFoundException(null, tableOrNamespaceName,
              "Target table does not exist");
        case NAMESPACE_DOESNT_EXIST:
          throw new NamespaceNotFoundException(null, tableOrNamespaceName,
              "Target namespace does not exist");
        default:
          String tableInfo = context.getPrintableTableInfoFromName(tableOrNamespaceName);
          throw new AccumuloSecurityException(e.user, e.code, tableInfo, e);
      }
    } catch (ThriftTableOperationException e) {
      switch (e.getType()) {
        case EXISTS:
          throw new TableExistsException(e);
        case NOTFOUND:
          throw new TableNotFoundException(e);
        case NAMESPACE_EXISTS:
          throw new NamespaceExistsException(e);
        case NAMESPACE_NOTFOUND:
          throw new NamespaceNotFoundException(e);
        case OFFLINE:
          throw new TableOfflineException(
              e.getTableId() == null ? null : TableId.of(e.getTableId()), tableOrNamespaceName);
        case BULK_CONCURRENT_MERGE:
          throw new AccumuloBulkMergeException(e);
        default:
          throw new AccumuloException(e.description, e);
      }
    } catch (Exception e) {
      throw new AccumuloException(e.getMessage(), e);
    }
  }

  String doFateOperation(TFateOperation op, List<ByteBuffer> args, Map<String,String> opts,
      String tableOrNamespaceName)
      throws AccumuloSecurityException, TableExistsException, TableNotFoundException,
      AccumuloException, NamespaceExistsException, NamespaceNotFoundException {
    return doFateOperation(op, args, opts, tableOrNamespaceName, true);
  }

  String doFateOperation(TFateOperation op, List<ByteBuffer> args, Map<String,String> opts,
      String tableOrNamespaceName, boolean wait)
      throws AccumuloSecurityException, TableExistsException, TableNotFoundException,
      AccumuloException, NamespaceExistsException, NamespaceNotFoundException {
    AtomicReference<TFateId> opid = new AtomicReference<>();

    try {
      return handleFateOperation(() -> {
        TFateInstanceType t =
            FateInstanceType.fromNamespaceOrTableName(tableOrNamespaceName).toThrift();
        final TFateId fateId = beginFateOperation(t);
        opid.set(fateId);
        executeFateOperation(fateId, op, args, opts, !wait);
        if (!wait) {
          opid.set(null);
          return null;
        }
        return waitForFateOperation(fateId);
      }, tableOrNamespaceName);
    } finally {
      context.clearTableListCache();
      // always finish table op, even when exception
      if (opid.get() != null) {
        try {
          finishFateOperation(opid.get());
        } catch (Exception e) {
          log.warn("Exception thrown while finishing fate table operation", e);
        }
      }
    }
  }

  /**
   * On the server side the fate operation will exit w/o an error if the tablet requested to split
   * does not exist. When this happens it will also return an empty string. In the case where the
   * fate operation successfully splits the tablet it will return the following string. This code
   * uses this return value to see if it needs to retry finding the tablet.
   */
  public static final String SPLIT_SUCCESS_MSG = "SPLIT_SUCCEEDED";

  @Override
  public void addSplits(String tableName, SortedSet<Text> splits)
      throws AccumuloException, TableNotFoundException, AccumuloSecurityException {
    putSplits(tableName, TabletMergeabilityUtil.userDefaultSplits(splits));
  }

  @Override
  public void putSplits(String tableName, SortedMap<Text,TabletMergeability> splits)
      throws AccumuloException, TableNotFoundException, AccumuloSecurityException {

    EXISTING_TABLE_NAME.validate(tableName);

    TableId tableId = context.getTableId(tableName);

    // TODO should there be a server side check for this?
    context.requireNotOffline(tableId, tableName);

    ClientTabletCache tabLocator = context.getTabletLocationCache(tableId);

    SortedMap<Text,TabletMergeability> splitsTodo =
        Collections.synchronizedSortedMap(new TreeMap<>(splits));

    final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    ExecutorService startExecutor =
        context.threadPools().getPoolBuilder(SPLIT_START_POOL).numCoreThreads(16).build();
    ExecutorService waitExecutor =
        context.threadPools().getPoolBuilder(SPLIT_WAIT_POOL).numCoreThreads(16).build();

    while (!splitsTodo.isEmpty()) {

      tabLocator.invalidateCache();

      var splitsToTablets = mapSplitsToTablets(tableName, tableId, tabLocator, splitsTodo);
      Map<KeyExtent,List<Pair<Text,TabletMergeability>>> tabletSplits = splitsToTablets.newSplits;
      Map<KeyExtent,TabletMergeability> existingSplits = splitsToTablets.existingSplits;

      List<CompletableFuture<Void>> futures = new ArrayList<>();

      // Handle existing updates
      if (!existingSplits.isEmpty()) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            var tSplits = existingSplits.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().toThrift(), e -> TabletMergeabilityUtil.toThrift(e.getValue())));
            return ThriftClientTypes.MANAGER.executeTableCommand(context,
                client -> client.updateTabletMergeability(TraceUtil.traceInfo(), context.rpcCreds(),
                    tableName, tSplits));
          } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
            // This exception type is used because it makes it easier in the foreground thread to do
            // exception analysis when using CompletableFuture.
            throw new CompletionException(e);
          }
        }, startExecutor).thenApplyAsync(updated -> {
          // Remove the successfully updated tablets from the list, failures will be retried
          updated.forEach(tke -> splitsTodo.remove(KeyExtent.fromThrift(tke).endRow()));
          return null;
        }, waitExecutor));
      }

      // begin the fate operation for each tablet without waiting for the operation to complete
      for (Entry<KeyExtent,List<Pair<Text,TabletMergeability>>> splitsForTablet : tabletSplits
          .entrySet()) {
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
          var extent = splitsForTablet.getKey();

          List<ByteBuffer> args = new ArrayList<>();
          args.add(ByteBuffer.wrap(extent.tableId().canonical().getBytes(UTF_8)));
          args.add(extent.endRow() == null ? EMPTY : TextUtil.getByteBuffer(extent.endRow()));
          args.add(
              extent.prevEndRow() == null ? EMPTY : TextUtil.getByteBuffer(extent.prevEndRow()));

          splitsForTablet.getValue()
              .forEach(split -> args.add(TabletMergeabilityUtil.encodeAsBuffer(split)));

          try {
            return handleFateOperation(() -> {
              TFateInstanceType t = FateInstanceType.fromNamespaceOrTableName(tableName).toThrift();
              TFateId opid = beginFateOperation(t);
              executeFateOperation(opid, TFateOperation.TABLE_SPLIT, args, Map.of(), false);
              return new Pair<>(opid, splitsForTablet.getValue());
            }, tableName);
          } catch (TableExistsException | NamespaceExistsException | NamespaceNotFoundException
              | AccumuloSecurityException | TableNotFoundException | AccumuloException e) {
            // This exception type is used because it makes it easier in the foreground thread to do
            // exception analysis when using CompletableFuture.
            throw new CompletionException(e);
          }
          // wait for the fate operation to complete in a separate thread pool
        }, startExecutor).thenApplyAsync(pair -> {
          final TFateId opid = pair.getFirst();
          final List<Pair<Text,TabletMergeability>> completedSplits = pair.getSecond();

          try {
            String status = handleFateOperation(() -> waitForFateOperation(opid), tableName);

            if (SPLIT_SUCCESS_MSG.equals(status)) {
              completedSplits.stream().map(Pair::getFirst).forEach(splitsTodo::remove);
            }
          } catch (TableExistsException | NamespaceExistsException | NamespaceNotFoundException
              | AccumuloSecurityException | TableNotFoundException | AccumuloException e) {
            // This exception type is used because it makes it easier in the foreground thread to do
            // exception analysis when using CompletableFuture.
            throw new CompletionException(e);
          } finally {
            // always finish table op, even when exception
            if (opid != null) {
              try {
                finishFateOperation(opid);
              } catch (Exception e) {
                log.warn("Exception thrown while finishing fate table operation", e);
              }
            }
          }
          return null;
        }, waitExecutor);
        futures.add(future);
      }

      try {
        futures.forEach(CompletableFuture::join);
      } catch (CompletionException ee) {
        Throwable excep = ee.getCause();
        // Below all exceptions are wrapped and rethrown. This is done so that the user knows
        // what code path got them here. If the wrapping was not done, the user would only
        // have the stack trace for the background thread.
        if (excep instanceof TableNotFoundException) {
          TableNotFoundException tnfe = (TableNotFoundException) excep;
          throw new TableNotFoundException(tableId.canonical(), tableName,
              "Table not found by background thread", tnfe);
        } else if (excep instanceof TableOfflineException) {
          log.debug("TableOfflineException occurred in background thread. Throwing new exception",
              excep);
          throw new TableOfflineException(tableId, tableName);
        } else if (excep instanceof AccumuloSecurityException) {
          // base == background accumulo security exception
          AccumuloSecurityException base = (AccumuloSecurityException) excep;
          throw new AccumuloSecurityException(base.getUser(), base.asThriftException().getCode(),
              base.getTableInfo(), excep);
        } else if (excep instanceof AccumuloServerException) {
          throw new AccumuloServerException((AccumuloServerException) excep);
        } else {
          throw new AccumuloException(excep);
        }
      }

    }
    startExecutor.shutdown();
    waitExecutor.shutdown();
  }

  private SplitsToTablets mapSplitsToTablets(String tableName, TableId tableId,
      ClientTabletCache tabLocator, SortedMap<Text,TabletMergeability> splitsTodo)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    Map<KeyExtent,List<Pair<Text,TabletMergeability>>> newSplits = new HashMap<>();
    Map<KeyExtent,TabletMergeability> existingSplits = new HashMap<>();

    for (Entry<Text,TabletMergeability> splitEntry : splitsTodo.entrySet()) {
      var split = splitEntry.getKey();

      try {
        Retry retry = Retry.builder().infiniteRetries().retryAfter(Duration.ofMillis(100))
            .incrementBy(Duration.ofMillis(100)).maxWait(Duration.ofSeconds(2)).backOffFactor(1.5)
            .logInterval(Duration.ofMinutes(3)).createRetry();

        var tablet = tabLocator.findTablet(context, split, false, LocationNeed.NOT_REQUIRED);
        while (tablet == null) {
          context.requireTableExists(tableId, tableName);
          try {
            retry.waitForNextAttempt(log, "Find tablet in " + tableId + " containing " + split);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          tablet = tabLocator.findTablet(context, split, false, LocationNeed.NOT_REQUIRED);
        }

        // For splits that already exist collect them so we can update them
        // separately
        if (split.equals(tablet.getExtent().endRow())) {
          existingSplits.put(tablet.getExtent(), splitEntry.getValue());
          continue;
        }

        newSplits.computeIfAbsent(tablet.getExtent(), k -> new ArrayList<>())
            .add(Pair.fromEntry(splitEntry));

      } catch (InvalidTabletHostingRequestException e) {
        // not expected
        throw new AccumuloException(e);
      }
    }
    return new SplitsToTablets(newSplits, existingSplits);
  }

  private static class SplitsToTablets {
    final Map<KeyExtent,List<Pair<Text,TabletMergeability>>> newSplits;
    final Map<KeyExtent,TabletMergeability> existingSplits;

    private SplitsToTablets(Map<KeyExtent,List<Pair<Text,TabletMergeability>>> newSplits,
        Map<KeyExtent,TabletMergeability> existingSplits) {
      this.newSplits = Objects.requireNonNull(newSplits);
      this.existingSplits = Objects.requireNonNull(existingSplits);
    }
  }

  @Override
  public void merge(String tableName, Text start, Text end)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    ByteBuffer EMPTY = ByteBuffer.allocate(0);
    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(tableName.getBytes(UTF_8)),
        start == null ? EMPTY : TextUtil.getByteBuffer(start),
        end == null ? EMPTY : TextUtil.getByteBuffer(end));
    Map<String,String> opts = new HashMap<>();
    try {
      doTableFateOperation(tableName, TableNotFoundException.class, TFateOperation.TABLE_MERGE,
          args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  @Override
  public void deleteRows(String tableName, Text start, Text end)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    ByteBuffer EMPTY = ByteBuffer.allocate(0);
    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(tableName.getBytes(UTF_8)),
        start == null ? EMPTY : TextUtil.getByteBuffer(start),
        end == null ? EMPTY : TextUtil.getByteBuffer(end));
    Map<String,String> opts = new HashMap<>();
    try {
      doTableFateOperation(tableName, TableNotFoundException.class,
          TFateOperation.TABLE_DELETE_RANGE, args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  @Override
  public Collection<Text> listSplits(String tableName)
      throws TableNotFoundException, AccumuloSecurityException {
    // tableName is validated in _listSplits
    return _listSplits(tableName);
  }

  private List<Text> _listSplits(String tableName) throws TableNotFoundException {

    TableId tableId = context.getTableId(tableName);

    while (true) {
      try (TabletsMetadata tabletsMetadata = context.getAmple().readTablets().forTable(tableId)
          .fetch(PREV_ROW).checkConsistency().build()) {
        return tabletsMetadata.stream().map(tm -> tm.getExtent().endRow()).filter(Objects::nonNull)
            .collect(Collectors.toList());
      } catch (TabletDeletedException tde) {
        // see if the table was deleted
        context.requireTableExists(tableId, tableName);
        log.debug("A merge happened while trying to list splits for {} {}, retrying ", tableName,
            tableId, tde);
        sleepUninterruptibly(3, SECONDS);
      }
    }
  }

  /**
   * This version of listSplits is called when the maxSplits options is provided. If the value of
   * maxSplits is greater than the number of existing splits, then all splits are returned and no
   * additional processing is performed.
   *
   * But, if the value of maxSplits is less than the number of existing splits, maxSplit split
   * values are returned. These split values are "evenly" selected from the existing splits based
   * upon the algorithm implemented in the method.
   *
   * A stepSize is calculated based upon the number of splits requested and the total split count. A
   * running sum adjusted by this stepSize is calculated as each split is parsed. Once this sum
   * exceeds a value of 1, the current split point is selected to be returned. The sum is then
   * decremented by 1 and the process continues until all existing splits have been parsed or
   * maxSplits splits have been selected.
   *
   * @param tableName the name of the table
   * @param maxSplits specifies the maximum number of splits to return
   * @return a Collection containing a subset of evenly selected splits
   */
  @Override
  public Collection<Text> listSplits(final String tableName, final int maxSplits)
      throws TableNotFoundException, AccumuloSecurityException {
    // tableName is validated in _listSplits
    final List<Text> existingSplits = _listSplits(tableName);

    // As long as maxSplits is equal to or larger than the number of current splits, the existing
    // splits are returned and no additional processing is necessary.
    if (existingSplits.size() <= maxSplits) {
      return existingSplits;
    }

    // When the number of maxSplits requested is less than the number of existing splits, the
    // following code populates the splitsSubset list 'evenly' from the existing splits
    ArrayList<Text> splitsSubset = new ArrayList<>(maxSplits);
    final int SELECTION_THRESHOLD = 1;

    // stepSize can never be greater than 1 due to the if-loop check above.
    final double stepSize = (maxSplits + 1) / (double) existingSplits.size();
    double selectionTrigger = 0.0;

    for (Text existingSplit : existingSplits) {
      if (splitsSubset.size() >= maxSplits) {
        break;
      }
      selectionTrigger += stepSize;
      if (selectionTrigger > SELECTION_THRESHOLD) {
        splitsSubset.add(existingSplit);
        selectionTrigger -= 1;
      }
    }
    return splitsSubset;
  }

  @Override
  public void delete(String tableName)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    List<ByteBuffer> args = List.of(ByteBuffer.wrap(tableName.getBytes(UTF_8)));
    Map<String,String> opts = new HashMap<>();
    try {
      doTableFateOperation(tableName, TableNotFoundException.class, TFateOperation.TABLE_DELETE,
          args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  @Override
  public void clone(String srcTableName, String newTableName, boolean flush,
      Map<String,String> propertiesToSet, Set<String> propertiesToExclude)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException,
      TableExistsException {
    clone(srcTableName, newTableName,
        CloneConfiguration.builder().setFlush(flush).setPropertiesToSet(propertiesToSet)
            .setPropertiesToExclude(propertiesToExclude).setKeepOffline(false).build());
  }

  @Override
  public void clone(String srcTableName, String newTableName, CloneConfiguration config)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException,
      TableExistsException {
    NEW_TABLE_NAME.validate(newTableName);
    requireNonNull(config, "CloneConfiguration required.");

    TableId srcTableId = context.getTableId(srcTableName);

    if (config.isFlush()) {
      _flush(srcTableId, null, null, true);
    }

    Map<String,String> opts = new HashMap<>();
    validatePropertiesToSet(opts, config.getPropertiesToSet());

    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(srcTableId.canonical().getBytes(UTF_8)),
        ByteBuffer.wrap(newTableName.getBytes(UTF_8)),
        ByteBuffer.wrap(Boolean.toString(config.isKeepOffline()).getBytes(UTF_8)));

    prependPropertiesToExclude(opts, config.getPropertiesToExclude());

    doTableFateOperation(newTableName, AccumuloException.class, TFateOperation.TABLE_CLONE, args,
        opts);
  }

  @Override
  public void rename(String oldTableName, String newTableName) throws AccumuloSecurityException,
      TableNotFoundException, AccumuloException, TableExistsException {
    EXISTING_TABLE_NAME.validate(oldTableName);
    NEW_TABLE_NAME.validate(newTableName);

    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(oldTableName.getBytes(UTF_8)),
        ByteBuffer.wrap(newTableName.getBytes(UTF_8)));
    Map<String,String> opts = new HashMap<>();
    doTableFateOperation(oldTableName, TableNotFoundException.class, TFateOperation.TABLE_RENAME,
        args, opts);
  }

  @Override
  public void flush(String tableName) throws AccumuloException, AccumuloSecurityException {
    // tableName is validated in the flush method being called below
    try {
      flush(tableName, null, null, false);
    } catch (TableNotFoundException e) {
      throw new AccumuloException(e.getMessage(), e);
    }
  }

  @Override
  public void flush(String tableName, Text start, Text end, boolean wait)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    _flush(context.getTableId(tableName), start, end, wait);
  }

  @Override
  public void compact(String tableName, Text start, Text end, boolean flush, boolean wait)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException {
    compact(tableName, start, end, new ArrayList<>(), flush, wait);
  }

  @Override
  public void compact(String tableName, Text start, Text end, List<IteratorSetting> iterators,
      boolean flush, boolean wait)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException {
    compact(tableName, new CompactionConfig().setStartRow(start).setEndRow(end)
        .setIterators(iterators).setFlush(flush).setWait(wait));
  }

  @Override
  public void compact(String tableName, CompactionConfig config)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException {
    EXISTING_TABLE_NAME.validate(tableName);

    // Ensure compaction iterators exist on a tabletserver
    final String skviName = SortedKeyValueIterator.class.getName();
    for (IteratorSetting setting : config.getIterators()) {
      String iteratorClass = setting.getIteratorClass();
      if (!testClassLoad(tableName, iteratorClass, skviName)) {
        throw new AccumuloException("TabletServer could not load iterator class " + iteratorClass);
      }
    }

    if (!UserCompactionUtils.isDefault(config.getConfigurer())) {
      if (!testClassLoad(tableName, config.getConfigurer().getClassName(),
          CompactionConfigurer.class.getName())) {
        throw new AccumuloException(
            "TabletServer could not load " + CompactionConfigurer.class.getSimpleName() + " class "
                + config.getConfigurer().getClassName());
      }
    }

    if (!UserCompactionUtils.isDefault(config.getSelector())) {
      if (!testClassLoad(tableName, config.getSelector().getClassName(),
          CompactionSelector.class.getName())) {
        throw new AccumuloException(
            "TabletServer could not load " + CompactionSelector.class.getSimpleName() + " class "
                + config.getSelector().getClassName());
      }
    }

    TableId tableId = context.getTableId(tableName);
    Text start = config.getStartRow();
    Text end = config.getEndRow();

    if (config.getFlush()) {
      _flush(tableId, start, end, true);
    }

    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(tableId.canonical().getBytes(UTF_8)),
        ByteBuffer.wrap(UserCompactionUtils.encode(config)));
    Map<String,String> opts = new HashMap<>();

    try {
      doFateOperation(TFateOperation.TABLE_COMPACT, args, opts, tableName, config.getWait());
    } catch (TableExistsException | NamespaceExistsException e) {
      // should not happen
      throw new AssertionError(e);
    } catch (NamespaceNotFoundException e) {
      throw new TableNotFoundException(null, tableName, "Namespace not found", e);
    }
  }

  @Override
  public void cancelCompaction(String tableName)
      throws AccumuloSecurityException, TableNotFoundException, AccumuloException {
    EXISTING_TABLE_NAME.validate(tableName);

    TableId tableId = context.getTableId(tableName);
    List<ByteBuffer> args = List.of(ByteBuffer.wrap(tableId.canonical().getBytes(UTF_8)));
    Map<String,String> opts = new HashMap<>();

    try {
      doTableFateOperation(tableName, TableNotFoundException.class,
          TFateOperation.TABLE_CANCEL_COMPACT, args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }

  }

  private void _flush(TableId tableId, Text start, Text end, boolean wait)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    try {
      long flushID;

      // used to pass the table name. but the tableid associated with a table name could change
      // between calls.
      // so pass the tableid to both calls

      while (true) {
        ManagerClientService.Client client = null;
        try {
          client = ThriftClientTypes.MANAGER.getConnectionWithRetry(context);
          flushID =
              client.initiateFlush(TraceUtil.traceInfo(), context.rpcCreds(), tableId.canonical());
          break;
        } catch (TTransportException tte) {
          log.debug("Failed to call initiateFlush, retrying ... ", tte);
          sleepUninterruptibly(100, MILLISECONDS);
        } catch (ThriftNotActiveServiceException e) {
          // Let it loop, fetching a new location
          log.debug("Contacted a Manager which is no longer active, retrying");
          sleepUninterruptibly(100, MILLISECONDS);
        } finally {
          ThriftUtil.close(client, context);
        }
      }

      while (true) {
        ManagerClientService.Client client = null;
        try {
          client = ThriftClientTypes.MANAGER.getConnectionWithRetry(context);
          client.waitForFlush(TraceUtil.traceInfo(), context.rpcCreds(), tableId.canonical(),
              TextUtil.getByteBuffer(start), TextUtil.getByteBuffer(end), flushID,
              wait ? Long.MAX_VALUE : 1);
          break;
        } catch (TTransportException tte) {
          log.debug("Failed to call initiateFlush, retrying ... ", tte);
          sleepUninterruptibly(100, MILLISECONDS);
        } catch (ThriftNotActiveServiceException e) {
          // Let it loop, fetching a new location
          log.debug("Contacted a Manager which is no longer active, retrying");
          sleepUninterruptibly(100, MILLISECONDS);
        } finally {
          ThriftUtil.close(client, context);
        }
      }
    } catch (ThriftSecurityException e) {
      if (requireNonNull(e.getCode()) == SecurityErrorCode.TABLE_DOESNT_EXIST) {
        throw new TableNotFoundException(tableId.canonical(), null, e.getMessage(), e);
      }
      log.debug("flush security exception on table id {}", tableId);
      throw new AccumuloSecurityException(e.user, e.code, e);
    } catch (ThriftTableOperationException e) {
      if (requireNonNull(e.getType()) == TableOperationExceptionType.NOTFOUND) {
        throw new TableNotFoundException(e);
      }
      throw new AccumuloException(e.description, e);
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  @Override
  public void setProperty(final String tableName, final String property, final String value)
      throws AccumuloException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(property != null, "property is null");
    checkArgument(value != null, "value is null");

    try {
      setPropertyNoChecks(tableName, property, value);

      checkLocalityGroups(tableName, property);
    } catch (TableNotFoundException e) {
      throw new AccumuloException(e);
    }
  }

  private Map<String,String> tryToModifyProperties(String tableName,
      final Consumer<Map<String,String>> mapMutator) throws AccumuloException,
      AccumuloSecurityException, IllegalArgumentException, ConcurrentModificationException {
    final TVersionedProperties vProperties =
        ThriftClientTypes.CLIENT.execute(context, client -> client
            .getVersionedTableProperties(TraceUtil.traceInfo(), context.rpcCreds(), tableName));
    mapMutator.accept(vProperties.getProperties());

    // A reference to the map was passed to the user, maybe they still have the reference and are
    // modifying it. Buggy Accumulo code could attempt to make modifications to the map after this
    // point. Because of these potential issues, create an immutable snapshot of the map so that
    // from here on the code is assured to always be dealing with the same map.
    vProperties.setProperties(Map.copyOf(vProperties.getProperties()));

    try {
      // Send to server
      ThriftClientTypes.MANAGER.executeVoid(context,
          client -> client.modifyTableProperties(TraceUtil.traceInfo(), context.rpcCreds(),
              tableName, vProperties));
      for (String property : vProperties.getProperties().keySet()) {
        checkLocalityGroups(tableName, property);
      }
    } catch (TableNotFoundException e) {
      throw new AccumuloException(e);
    }

    return vProperties.getProperties();
  }

  @Override
  public Map<String,String> modifyProperties(String tableName,
      final Consumer<Map<String,String>> mapMutator)
      throws AccumuloException, AccumuloSecurityException, IllegalArgumentException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(mapMutator != null, "mapMutator is null");

    Retry retry = Retry.builder().infiniteRetries().retryAfter(Duration.ofMillis(25))
        .incrementBy(Duration.ofMillis(25)).maxWait(Duration.ofSeconds(30)).backOffFactor(1.5)
        .logInterval(Duration.ofMinutes(3)).createRetry();

    while (true) {
      try {
        var props = tryToModifyProperties(tableName, mapMutator);
        retry.logCompletion(log, "Modifying properties for table " + tableName);
        return props;
      } catch (ConcurrentModificationException cme) {
        try {
          retry.logRetry(log, "Unable to modify table properties for " + tableName
              + " because of concurrent modification");
          retry.waitForNextAttempt(log, "modify table properties for " + tableName);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      } finally {
        retry.useRetry();
      }
    }
  }

  /**
   * Like modifyProperties(...), but if an AccumuloException is caused by a TableNotFoundException,
   * unwrap and rethrow the TNFE directly. This is a hacky, temporary workaround that we can use
   * until we are able to change the public API and throw TNFE directly from all applicable methods.
   */
  private Map<String,String> modifyPropertiesUnwrapped(String tableName,
      Consumer<Map<String,String>> mapMutator)
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException {

    try {
      return modifyProperties(tableName, mapMutator);
    } catch (AccumuloException ae) {
      Throwable cause = ae.getCause();
      if (cause instanceof TableNotFoundException) {
        var tnfe = (TableNotFoundException) cause;
        tnfe.addSuppressed(ae);
        throw tnfe;
      }
      throw ae;
    }
  }

  private void setPropertyNoChecks(final String tableName, final String property,
      final String value)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    ThriftClientTypes.MANAGER.executeVoid(context, client -> client
        .setTableProperty(TraceUtil.traceInfo(), context.rpcCreds(), tableName, property, value));
  }

  @Override
  public void removeProperty(final String tableName, final String property)
      throws AccumuloException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(property != null, "property is null");

    try {
      removePropertyNoChecks(tableName, property);

      checkLocalityGroups(tableName, property);
    } catch (TableNotFoundException e) {
      throw new AccumuloException(e);
    }
  }

  private void removePropertyNoChecks(final String tableName, final String property)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    ThriftClientTypes.MANAGER.executeVoid(context, client -> client
        .removeTableProperty(TraceUtil.traceInfo(), context.rpcCreds(), tableName, property));
  }

  void checkLocalityGroups(String tableName, String propChanged)
      throws AccumuloException, TableNotFoundException {
    if (LocalityGroupUtil.isLocalityGroupProperty(propChanged)) {
      Map<String,String> allProps = getConfiguration(tableName);
      try {
        LocalityGroupUtil.checkLocalityGroups(allProps);
      } catch (LocalityGroupConfigurationError | RuntimeException e) {
        LoggerFactory.getLogger(this.getClass()).warn(
            "Changing '{}' for table '{}' resulted in bad locality group config.  This may be a transient situation since the config spreads over multiple properties.  Setting properties in a different order may help.  Even though this warning was displayed, the property was updated. Please check your config to ensure consistency.",
            propChanged, tableName, e);
      }
    }
  }

  @Override
  public Map<String,String> getConfiguration(final String tableName)
      throws AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    try {
      return ThriftClientTypes.CLIENT.execute(context, client -> client
          .getTableConfiguration(TraceUtil.traceInfo(), context.rpcCreds(), tableName));
    } catch (AccumuloException e) {
      Throwable t = e.getCause();
      if (t instanceof TableNotFoundException) {
        var tnfe = (TableNotFoundException) t;
        tnfe.addSuppressed(e);
        throw tnfe;
      }
      throw e;
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  @Override
  public Map<String,String> getTableProperties(final String tableName)
      throws AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    try {
      return ThriftClientTypes.CLIENT.execute(context, client -> client
          .getTableProperties(TraceUtil.traceInfo(), context.rpcCreds(), tableName));
    } catch (AccumuloException e) {
      Throwable t = e.getCause();
      if (t instanceof TableNotFoundException) {
        var tnfe = (TableNotFoundException) t;
        tnfe.addSuppressed(e);
        throw tnfe;
      }
      throw e;
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  @Override
  public void setLocalityGroups(String tableName, Map<String,Set<Text>> groupsToSet)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    // ensure locality groups do not overlap
    LocalityGroupUtil.ensureNonOverlappingGroups(groupsToSet);

    final String localityGroupPrefix = Property.TABLE_LOCALITY_GROUP_PREFIX.getKey();

    modifyPropertiesUnwrapped(tableName, properties -> {

      // add/update each locality group
      groupsToSet.forEach((groupName, colFams) -> properties.put(localityGroupPrefix + groupName,
          LocalityGroupUtil.encodeColumnFamilies(colFams)));

      // update the list of all locality groups
      final String allGroups = Joiner.on(",").join(groupsToSet.keySet());
      properties.put(Property.TABLE_LOCALITY_GROUPS.getKey(), allGroups);

      // remove any stale locality groups that were previously set
      properties.keySet().removeIf(property -> {
        if (property.startsWith(localityGroupPrefix)) {
          String group = property.substring(localityGroupPrefix.length());
          return !groupsToSet.containsKey(group);
        }
        return false;
      });
    });
  }

  @Override
  public Map<String,Set<Text>> getLocalityGroups(String tableName)
      throws AccumuloException, TableNotFoundException {

    AccumuloConfiguration conf = new ConfigurationCopy(this.getProperties(tableName));
    Map<String,Set<ByteSequence>> groups = LocalityGroupUtil.getLocalityGroups(conf);
    Map<String,Set<Text>> groups2 = new HashMap<>();

    for (Entry<String,Set<ByteSequence>> entry : groups.entrySet()) {

      HashSet<Text> colFams = new HashSet<>();

      for (ByteSequence bs : entry.getValue()) {
        colFams.add(new Text(bs.toArray()));
      }

      groups2.put(entry.getKey(), colFams);
    }

    return groups2;
  }

  @Override
  public Set<Range> splitRangeByTablets(String tableName, Range range, int maxSplits)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(range != null, "range is null");

    if (maxSplits < 1) {
      throw new IllegalArgumentException("maximum splits must be >= 1");
    }
    if (maxSplits == 1) {
      return Collections.singleton(range);
    }

    TableId tableId = context.getTableId(tableName);
    ClientTabletCache tl = context.getTabletLocationCache(tableId);
    // it's possible that the cache could contain complete, but old information about a tables
    // tablets... so clear it
    tl.invalidateCache();

    // group key extents to get <= maxSplits
    LinkedList<KeyExtent> unmergedExtents = new LinkedList<>();

    try {
      while (!tl.findTablets(context, Collections.singletonList(range),
          (cachedTablet, range1) -> unmergedExtents.add(cachedTablet.getExtent()),
          LocationNeed.NOT_REQUIRED).isEmpty()) {
        context.requireNotDeleted(tableId);
        context.requireNotOffline(tableId, tableName);

        log.warn("Unable to locate bins for specified range. Retrying.");
        // sleep randomly between 100 and 200ms
        sleepUninterruptibly(100 + RANDOM.get().nextInt(100), MILLISECONDS);
        unmergedExtents.clear();
        tl.invalidateCache();
      }
    } catch (InvalidTabletHostingRequestException e) {
      throw new AccumuloException("findTablets requested tablet hosting when it should not have",
          e);
    }

    // the sort method is efficient for linked list
    Collections.sort(unmergedExtents);

    List<KeyExtent> mergedExtents = new ArrayList<>();

    while (unmergedExtents.size() + mergedExtents.size() > maxSplits) {
      if (unmergedExtents.size() >= 2) {
        KeyExtent first = unmergedExtents.removeFirst();
        KeyExtent second = unmergedExtents.removeFirst();
        KeyExtent merged = new KeyExtent(first.tableId(), second.endRow(), first.prevEndRow());
        mergedExtents.add(merged);
      } else {
        mergedExtents.addAll(unmergedExtents);
        unmergedExtents.clear();
        unmergedExtents.addAll(mergedExtents);
        mergedExtents.clear();
      }

    }

    mergedExtents.addAll(unmergedExtents);

    Set<Range> ranges = new HashSet<>();
    for (KeyExtent k : mergedExtents) {
      ranges.add(k.toDataRange().clip(range));
    }

    return ranges;
  }

  private Path checkPath(String dir, String kind, String type)
      throws IOException, AccumuloException {
    FileSystem fs = VolumeConfiguration.fileSystemForPath(dir, context.getHadoopConf());
    Path ret = dir.contains(":") ? new Path(dir) : fs.makeQualified(new Path(dir));

    try {
      if (!fs.getFileStatus(ret).isDirectory()) {
        throw new AccumuloException(
            kind + " import " + type + " directory " + ret + " is not a directory!");
      }
    } catch (FileNotFoundException fnf) {
      throw new AccumuloException(
          kind + " import " + type + " directory " + ret + " does not exist!");
    }

    if (type.equals("failure")) {
      FileStatus[] listStatus = fs.listStatus(ret);
      if (listStatus != null && listStatus.length != 0) {
        throw new AccumuloException("Bulk import failure directory " + ret + " is not empty");
      }
    }

    return ret;
  }

  private void waitForTableStateTransition(TableId tableId, TableState expectedState)
      throws AccumuloException, TableNotFoundException {
    Text startRow = null;
    Text lastRow = null;

    while (true) {
      if (context.getTableState(tableId) != expectedState) {
        context.clearTableListCache();
        TableState currentState = context.getTableState(tableId);
        if (currentState != expectedState) {
          context.requireNotDeleted(tableId);
          if (currentState == TableState.DELETING) {
            throw new TableNotFoundException(tableId.canonical(), "", TABLE_DELETED_MSG);
          }
          throw new AccumuloException(
              "Unexpected table state " + tableId + " " + currentState + " != " + expectedState);
        }
      }

      Range range;
      if (startRow == null || lastRow == null) {
        range = new KeyExtent(tableId, null, null).toMetaRange();
      } else {
        range = new Range(startRow, lastRow);
      }

      KeyExtent lastExtent = null;

      int total = 0;
      int waitFor = 0;
      int holes = 0;
      Text continueRow = null;
      MapCounter<String> serverCounts = new MapCounter<>();

      try (TabletsMetadata tablets =
          TabletsMetadata.builder(context).scanMetadataTable().overRange(range)
              .fetch(AVAILABILITY, HOSTING_REQUESTED, LOCATION, PREV_ROW, OPID, ECOMP).build()) {

        for (TabletMetadata tablet : tablets) {
          total++;
          Location loc = tablet.getLocation();
          TabletAvailability availability = tablet.getTabletAvailability();
          var opid = tablet.getOperationId();
          var externalCompactions = tablet.getExternalCompactions();

          if ((expectedState == TableState.ONLINE
              && (availability == TabletAvailability.HOSTED
                  || (availability == TabletAvailability.ONDEMAND) && tablet.getHostingRequested())
              && (loc == null || loc.getType() == LocationType.FUTURE))
              || (expectedState == TableState.OFFLINE
                  && (loc != null || opid != null || !externalCompactions.isEmpty()))) {
            if (continueRow == null) {
              continueRow = tablet.getExtent().toMetaRow();
            }
            waitFor++;
            lastRow = tablet.getExtent().toMetaRow();

            if (loc != null) {
              serverCounts.increment(loc.getHostPortSession(), 1);
            }
          }

          if (!tablet.getExtent().tableId().equals(tableId)) {
            throw new AccumuloException(
                "Saw unexpected table Id " + tableId + " " + tablet.getExtent());
          }

          if (lastExtent != null && !tablet.getExtent().isPreviousExtent(lastExtent)) {
            holes++;
          }

          lastExtent = tablet.getExtent();
        }
      }

      if (continueRow != null) {
        startRow = continueRow;
      }

      if (holes > 0 || total == 0) {
        startRow = null;
        lastRow = null;
      }

      if (waitFor > 0 || holes > 0 || total == 0) {
        long waitTime;
        long maxPerServer = 0;
        if (serverCounts.size() > 0) {
          maxPerServer = serverCounts.max();
          waitTime = maxPerServer * 10;
        } else {
          waitTime = waitFor * 10L;
        }
        waitTime = Math.max(100, waitTime);
        waitTime = Math.min(5000, waitTime);
        log.trace("Waiting for {}({}) tablets, startRow = {} lastRow = {}, holes={} sleeping:{}ms",
            waitFor, maxPerServer, startRow, lastRow, holes, waitTime);
        sleepUninterruptibly(waitTime, MILLISECONDS);
      } else {
        break;
      }

    }
  }

  @Override
  public void offline(String tableName)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    offline(tableName, false);
  }

  @Override
  public void offline(String tableName, boolean wait)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    changeTableState(tableName, wait, TableState.OFFLINE);
  }

  @Override
  public boolean isOnline(String tableName) throws AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    TableId tableId = context.getTableId(tableName);
    TableState expectedState = context.getTableState(tableId, true);
    return expectedState == TableState.ONLINE;
  }

  @Override
  public void online(String tableName)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    online(tableName, false);
  }

  private void changeTableState(String tableName, boolean wait, TableState newState)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    TableId tableId = context.getTableId(tableName);

    TFateOperation op = null;
    switch (newState) {
      case OFFLINE:
        op = TFateOperation.TABLE_OFFLINE;
        if (SystemTables.containsTableName(tableName)) {
          throw new AccumuloException("Cannot set table to offline state");
        }
        break;
      case ONLINE:
        op = TFateOperation.TABLE_ONLINE;
        if (SystemTables.containsTableName(tableName)) {
          // Don't submit a Fate operation for this, these tables can only be online.
          return;
        }
        break;
      default:
        throw new IllegalArgumentException(newState + " is not handled.");
    }

    /*
     * ACCUMULO-4574 Don't submit a fate operation to change the table state to newState if it's
     * already in that state.
     */
    TableState currentState = context.getTableState(tableId, true);
    if (newState == currentState) {
      if (wait) {
        waitForTableStateTransition(tableId, newState);
      }
      return;
    }

    List<ByteBuffer> args = List.of(ByteBuffer.wrap(tableId.canonical().getBytes(UTF_8)));
    Map<String,String> opts = new HashMap<>();

    try {
      doTableFateOperation(tableName, TableNotFoundException.class, op, args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }

    if (wait) {
      waitForTableStateTransition(tableId, newState);
    }

  }

  @Override
  public void online(String tableName, boolean wait)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    changeTableState(tableName, wait, TableState.ONLINE);
  }

  @Override
  public void clearLocatorCache(String tableName) throws TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    ClientTabletCache tabLocator = context.getTabletLocationCache(context.getTableId(tableName));
    tabLocator.invalidateCache();
  }

  @Override
  public Map<String,String> tableIdMap() {
    return context.createQualifiedTableNameToIdMap().entrySet().stream()
        .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().canonical(), (v1, v2) -> {
          throw new IllegalStateException(
              String.format("Duplicate key for values %s and %s", v1, v2));
        }, TreeMap::new));
  }

  @Override
  public Text getMaxRow(String tableName, Authorizations auths, Text startRow,
      boolean startInclusive, Text endRow, boolean endInclusive) throws TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    Scanner scanner = context.createScanner(tableName, auths);
    return FindMax.findMax(scanner, startRow, startInclusive, endRow, endInclusive);
  }

  @Override
  public List<DiskUsage> getDiskUsage(Set<String> tableNames)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {

    List<TDiskUsage> diskUsages = null;
    while (diskUsages == null) {
      Pair<String,Client> pair = null;
      try {
        // this operation may us a lot of memory... it's likely that connections to tabletservers
        // hosting metadata tablets will be cached, so do not use cached
        // connections
        pair = ThriftClientTypes.CLIENT.getThriftServerConnection(context, false);
        diskUsages = pair.getSecond().getDiskUsage(tableNames, context.rpcCreds());
      } catch (ThriftTableOperationException e) {
        switch (e.getType()) {
          case NOTFOUND:
            throw new TableNotFoundException(e);
          case NAMESPACE_NOTFOUND:
            throw new TableNotFoundException(e.getTableName(), new NamespaceNotFoundException(e));
          default:
            throw new AccumuloException(e.description, e);
        }
      } catch (ThriftSecurityException e) {
        throw new AccumuloSecurityException(e.getUser(), e.getCode());
      } catch (TTransportException e) {
        // some sort of communication error occurred, retry
        if (pair == null) {
          log.debug("Disk usage request failed.  Pair is null.  Retrying request...", e);
        } else {
          log.debug("Disk usage request failed {}, retrying ... ", pair.getFirst(), e);
        }
        sleepUninterruptibly(100, MILLISECONDS);
      } catch (TException e) {
        // may be a TApplicationException which indicates error on the server side
        throw new AccumuloException(e);
      } finally {
        // must always return thrift connection
        if (pair != null) {
          ThriftUtil.close(pair.getSecond(), context);
        }
      }
    }

    List<DiskUsage> finalUsages = new ArrayList<>();
    for (TDiskUsage diskUsage : diskUsages) {
      finalUsages.add(new DiskUsage(new TreeSet<>(diskUsage.getTables()), diskUsage.getUsage()));
    }

    return finalUsages;
  }

  /**
   * Search multiple directories for exportMetadata.zip, the control file used for the importable
   * command.
   *
   * @param context used to obtain filesystem based on configuration
   * @param importDirs the set of directories to search.
   * @return the Path representing the location of the file.
   * @throws AccumuloException if zero or more than one copy of the exportMetadata.zip file are
   *         found in the directories provided.
   */
  public static Path findExportFile(ClientContext context, Set<String> importDirs)
      throws AccumuloException {
    LinkedHashSet<Path> exportFiles = new LinkedHashSet<>();
    for (String importDir : importDirs) {
      Path exportFilePath = null;
      try {
        FileSystem fs = new Path(importDir).getFileSystem(context.getHadoopConf());
        exportFilePath = new Path(importDir, Constants.EXPORT_FILE);
        log.debug("Looking for export metadata in {}", exportFilePath);
        if (fs.exists(exportFilePath)) {
          log.debug("Found export metadata in {}", exportFilePath);
          exportFiles.add(exportFilePath);
        }
      } catch (IOException ioe) {
        log.warn("Non-Fatal IOException reading export file: {}", exportFilePath, ioe);
      }
    }

    if (exportFiles.size() > 1) {
      String fileList = Arrays.toString(exportFiles.toArray());
      log.warn("Found multiple export metadata files: {}", fileList);
      throw new AccumuloException("Found multiple export metadata files: " + fileList);
    } else if (exportFiles.isEmpty()) {
      log.warn("Unable to locate export metadata");
      throw new AccumuloException("Unable to locate export metadata");
    }

    return exportFiles.iterator().next();
  }

  public static Map<String,String> getExportedProps(FileSystem fs, Path path) throws IOException {
    HashMap<String,String> props = new HashMap<>();

    try (ZipInputStream zis = new ZipInputStream(fs.open(path))) {
      ZipEntry zipEntry;
      while ((zipEntry = zis.getNextEntry()) != null) {
        if (zipEntry.getName().equals(Constants.EXPORT_TABLE_CONFIG_FILE)) {
          try (BufferedReader in = new BufferedReader(new InputStreamReader(zis, UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
              String[] sa = line.split("=", 2);
              props.put(sa[0], sa[1]);
            }
          }

          break;
        }
      }
    }
    return props;
  }

  @Override
  public void importTable(String tableName, Set<String> importDirs, ImportConfiguration ic)
      throws TableExistsException, AccumuloException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(importDirs != null, "importDir is null");

    boolean keepOffline = ic.isKeepOffline();
    boolean keepMapping = ic.isKeepMappings();

    Set<String> checkedImportDirs = new HashSet<>();
    try {
      for (String s : importDirs) {
        checkedImportDirs.add(checkPath(s, "Table", "").toString());
      }
    } catch (IOException e) {
      throw new AccumuloException(e);
    }

    try {
      Path exportFilePath = findExportFile(context, checkedImportDirs);
      FileSystem fs = exportFilePath.getFileSystem(context.getHadoopConf());
      Map<String,String> props = getExportedProps(fs, exportFilePath);

      for (Entry<String,String> entry : props.entrySet()) {
        if (Property.isClassProperty(entry.getKey())
            && !entry.getValue().contains(Constants.CORE_PACKAGE_NAME)) {
          LoggerFactory.getLogger(this.getClass()).info(
              "Imported table sets '{}' to '{}'.  Ensure this class is on Accumulo classpath.",
              sanitize(entry.getKey()), sanitize(entry.getValue()));
        }
      }
    } catch (IOException ioe) {
      LoggerFactory.getLogger(this.getClass()).warn(
          "Failed to check if imported table references external java classes : {}",
          ioe.getMessage());
    }

    List<ByteBuffer> args = new ArrayList<>(3 + checkedImportDirs.size());
    args.add(0, ByteBuffer.wrap(tableName.getBytes(UTF_8)));
    args.add(1, ByteBuffer.wrap(Boolean.toString(keepOffline).getBytes(UTF_8)));
    args.add(2, ByteBuffer.wrap(Boolean.toString(keepMapping).getBytes(UTF_8)));
    checkedImportDirs.stream().map(s -> s.getBytes(UTF_8)).map(ByteBuffer::wrap).forEach(args::add);

    try {
      doTableFateOperation(tableName, AccumuloException.class, TFateOperation.TABLE_IMPORT, args,
          Collections.emptyMap());
    } catch (TableNotFoundException e) {
      // should not happen
      throw new AssertionError(e);
    }

  }

  /**
   * Prevent potential CRLF injection into logs from read in user data. See the
   * <a href="https://find-sec-bugs.github.io/bugs.htm#CRLF_INJECTION_LOGS">bug description</a>
   */
  private String sanitize(String msg) {
    return msg.replaceAll("[\r\n]", "");
  }

  @Override
  public void exportTable(String tableName, String exportDir)
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(exportDir != null, "exportDir is null");

    if (isOnline(tableName)) {
      throw new IllegalStateException("The table " + tableName
          + " is not offline; exportTable requires a table to be offline before exporting.");
    }

    List<ByteBuffer> args = Arrays.asList(ByteBuffer.wrap(tableName.getBytes(UTF_8)),
        ByteBuffer.wrap(exportDir.getBytes(UTF_8)));
    Map<String,String> opts = Collections.emptyMap();

    try {
      doTableFateOperation(tableName, TableNotFoundException.class, TFateOperation.TABLE_EXPORT,
          args, opts);
    } catch (TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  @Override
  public boolean testClassLoad(final String tableName, final String className,
      final String asTypeName)
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);
    checkArgument(className != null, "className is null");
    checkArgument(asTypeName != null, "asTypeName is null");

    try {
      return ThriftClientTypes.CLIENT.execute(context,
          client -> client.checkTableClass(TraceUtil.traceInfo(), context.rpcCreds(), tableName,
              className, asTypeName));
    } catch (AccumuloSecurityException e) {
      throw e;
    } catch (AccumuloException e) {
      Throwable t = e.getCause();
      if (t instanceof TableNotFoundException) {
        var tnfe = (TableNotFoundException) t;
        tnfe.addSuppressed(e);
        throw tnfe;
      }
      throw e;
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  @Override
  public void attachIterator(String tableName, IteratorSetting setting,
      EnumSet<IteratorScope> scopes)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    testClassLoad(tableName, setting.getIteratorClass(), SortedKeyValueIterator.class.getName());
    super.attachIterator(tableName, setting, scopes);
  }

  @Override
  public int addConstraint(String tableName, String constraintClassName)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    testClassLoad(tableName, constraintClassName, Constraint.class.getName());
    return super.addConstraint(tableName, constraintClassName);
  }

  private void doTableFateOperation(String tableOrNamespaceName,
      Class<? extends Exception> namespaceNotFoundExceptionClass, TFateOperation op,
      List<ByteBuffer> args, Map<String,String> opts) throws AccumuloSecurityException,
      AccumuloException, TableExistsException, TableNotFoundException {
    try {
      doFateOperation(op, args, opts, tableOrNamespaceName);
    } catch (NamespaceExistsException e) {
      // should not happen
      throw new AssertionError(e);
    } catch (NamespaceNotFoundException e) {
      if (namespaceNotFoundExceptionClass == null) {
        // should not happen
        throw new AssertionError(e);
      } else if (AccumuloException.class.isAssignableFrom(namespaceNotFoundExceptionClass)) {
        throw new AccumuloException("Cannot create table in non-existent namespace", e);
      } else if (TableNotFoundException.class.isAssignableFrom(namespaceNotFoundExceptionClass)) {
        throw new TableNotFoundException(null, tableOrNamespaceName, "Namespace not found", e);
      } else {
        // should not happen
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void setSamplerConfiguration(String tableName, SamplerConfiguration samplerConfiguration)
      throws AccumuloException, TableNotFoundException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);

    Map<String,String> props =
        new SamplerConfigurationImpl(samplerConfiguration).toTablePropertiesMap();

    modifyPropertiesUnwrapped(tableName, properties -> {
      properties.keySet()
          .removeIf(property -> property.startsWith(Property.TABLE_SAMPLER_OPTS.getKey()));
      properties.putAll(props);
    });
  }

  @Override
  public void clearSamplerConfiguration(String tableName)
      throws AccumuloException, TableNotFoundException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);

    modifyPropertiesUnwrapped(tableName, properties -> {
      properties.remove(Property.TABLE_SAMPLER.getKey());
      properties.keySet()
          .removeIf(property -> property.startsWith(Property.TABLE_SAMPLER_OPTS.getKey()));
    });
  }

  @Override
  public SamplerConfiguration getSamplerConfiguration(String tableName)
      throws TableNotFoundException, AccumuloSecurityException, AccumuloException {
    EXISTING_TABLE_NAME.validate(tableName);

    AccumuloConfiguration conf = new ConfigurationCopy(this.getProperties(tableName));
    SamplerConfigurationImpl sci = SamplerConfigurationImpl.newSamplerConfig(conf);
    if (sci == null) {
      return null;
    }
    return sci.toSamplerConfiguration();
  }

  private static class LocationsImpl implements Locations {

    private Map<Range,List<TabletId>> groupedByRanges;
    private Map<TabletId,List<Range>> groupedByTablets;
    private final Map<TabletId,String> tabletLocations;

    public LocationsImpl(Map<String,Map<KeyExtent,List<Range>>> binnedRanges) {
      groupedByTablets = new HashMap<>();
      groupedByRanges = null;
      tabletLocations = new HashMap<>();

      for (Entry<String,Map<KeyExtent,List<Range>>> entry : binnedRanges.entrySet()) {
        String location = entry.getKey();

        for (Entry<KeyExtent,List<Range>> entry2 : entry.getValue().entrySet()) {
          TabletIdImpl tabletId = new TabletIdImpl(entry2.getKey());
          tabletLocations.put(tabletId, location);
          List<Range> prev =
              groupedByTablets.put(tabletId, Collections.unmodifiableList(entry2.getValue()));
          if (prev != null) {
            throw new IllegalStateException(
                "Unexpected : tablet at multiple locations : " + location + " " + tabletId);
          }
        }
      }

      groupedByTablets = Collections.unmodifiableMap(groupedByTablets);
    }

    @Override
    public String getTabletLocation(TabletId tabletId) {
      return tabletLocations.get(tabletId);
    }

    @Override
    public Map<Range,List<TabletId>> groupByRange() {
      if (groupedByRanges == null) {
        Map<Range,List<TabletId>> tmp = new HashMap<>();

        groupedByTablets.forEach((tabletId, rangeList) -> rangeList
            .forEach(range -> tmp.computeIfAbsent(range, k -> new ArrayList<>()).add(tabletId)));

        Map<Range,List<TabletId>> tmp2 = new HashMap<>();
        for (Entry<Range,List<TabletId>> entry : tmp.entrySet()) {
          tmp2.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }

        groupedByRanges = Collections.unmodifiableMap(tmp2);
      }

      return groupedByRanges;
    }

    @Override
    public Map<TabletId,List<Range>> groupByTablet() {
      return groupedByTablets;
    }
  }

  @Override
  public Locations locate(String tableName, Collection<Range> ranges)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);
    requireNonNull(ranges, "ranges must be non null");

    TableId tableId = context.getTableId(tableName);

    context.requireTableExists(tableId, tableName);
    context.requireNotOffline(tableId, tableName);

    List<Range> rangeList = null;
    if (ranges instanceof List) {
      rangeList = (List<Range>) ranges;
    } else {
      rangeList = new ArrayList<>(ranges);
    }

    ClientTabletCache locator = context.getTabletLocationCache(tableId);
    locator.invalidateCache();

    Retry retry = Retry.builder().infiniteRetries().retryAfter(Duration.ofMillis(100))
        .incrementBy(Duration.ofMillis(100)).maxWait(Duration.ofSeconds(2)).backOffFactor(1.5)
        .logInterval(Duration.ofMinutes(3)).createRetry();

    final ArrayList<KeyExtent> locationLess = new ArrayList<>();
    final Map<String,Map<KeyExtent,List<Range>>> binnedRanges = new HashMap<>();
    final AtomicBoolean foundOnDemandTabletInRange = new AtomicBoolean(false);

    BiConsumer<CachedTablet,Range> rangeConsumer = (cachedTablet, range) -> {
      // We want tablets that are currently hosted (location present) and
      // their tablet availability is HOSTED (not OnDemand)
      if (cachedTablet.getAvailability() != TabletAvailability.HOSTED) {
        foundOnDemandTabletInRange.set(true);
      } else if (cachedTablet.getTserverLocation().isPresent()
          && cachedTablet.getAvailability() == TabletAvailability.HOSTED) {
        ClientTabletCacheImpl.addRange(binnedRanges, cachedTablet, range);
      } else {
        locationLess.add(cachedTablet.getExtent());
      }
    };

    try {

      List<Range> failed =
          locator.findTablets(context, rangeList, rangeConsumer, LocationNeed.NOT_REQUIRED);

      if (foundOnDemandTabletInRange.get()) {
        throw new AccumuloException(
            "TableOperations.locate() only works with tablets that have an availability of "
                + TabletAvailability.HOSTED
                + ". Tablets with other availabilities were seen.  table:" + tableName
                + " table id:" + tableId);
      }

      while (!failed.isEmpty() || !locationLess.isEmpty()) {

        context.requireTableExists(tableId, tableName);
        context.requireNotOffline(tableId, tableName);

        if (foundOnDemandTabletInRange.get()) {
          throw new AccumuloException(
              "TableOperations.locate() only works with tablets that have a tablet availability of "
                  + TabletAvailability.HOSTED
                  + ". Tablets with other availabilities were seen.  table:" + tableName
                  + " table id:" + tableId);
        }

        try {
          retry.waitForNextAttempt(log,
              String.format("locating tablets in table %s(%s) for %d ranges", tableName, tableId,
                  rangeList.size()));
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }

        locationLess.clear();
        binnedRanges.clear();
        foundOnDemandTabletInRange.set(false);
        locator.invalidateCache();
        failed = locator.findTablets(context, rangeList, rangeConsumer, LocationNeed.NOT_REQUIRED);
      }

    } catch (InvalidTabletHostingRequestException e) {
      throw new AccumuloException("findTablets requested tablet hosting when it should not have",
          e);
    }

    return new LocationsImpl(binnedRanges);
  }

  @Override
  public SummaryRetriever summaries(String tableName) {
    EXISTING_TABLE_NAME.validate(tableName);

    return new SummaryRetriever() {
      private Text startRow = null;
      private Text endRow = null;
      private List<TSummarizerConfiguration> summariesToFetch = Collections.emptyList();
      private String summarizerClassRegex;
      private boolean flush = false;

      @Override
      public SummaryRetriever startRow(Text startRow) {
        Objects.requireNonNull(startRow);
        if (endRow != null) {
          Preconditions.checkArgument(startRow.compareTo(endRow) < 0,
              "Start row must be less than end row : %s >= %s", startRow, endRow);
        }
        this.startRow = startRow;
        return this;
      }

      @Override
      public SummaryRetriever startRow(CharSequence startRow) {
        return startRow(new Text(startRow.toString()));
      }

      @Override
      public List<Summary> retrieve()
          throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
        TableId tableId = context.getTableId(tableName);
        context.requireNotOffline(tableId, tableName);

        TRowRange range =
            new TRowRange(TextUtil.getByteBuffer(startRow), TextUtil.getByteBuffer(endRow));
        TSummaryRequest request =
            new TSummaryRequest(tableId.canonical(), range, summariesToFetch, summarizerClassRegex);
        if (flush) {
          _flush(tableId, startRow, endRow, true);
        }

        TSummaries ret = ThriftClientTypes.TABLET_SERVER.execute(context, client -> {
          TSummaries tsr =
              client.startGetSummaries(TraceUtil.traceInfo(), context.rpcCreds(), request);
          while (!tsr.finished) {
            tsr = client.contiuneGetSummaries(TraceUtil.traceInfo(), tsr.sessionId);
          }
          return tsr;
        });
        return new SummaryCollection(ret).getSummaries();
      }

      @Override
      public SummaryRetriever endRow(Text endRow) {
        Objects.requireNonNull(endRow);
        if (startRow != null) {
          Preconditions.checkArgument(startRow.compareTo(endRow) < 0,
              "Start row must be less than end row : %s >= %s", startRow, endRow);
        }
        this.endRow = endRow;
        return this;
      }

      @Override
      public SummaryRetriever endRow(CharSequence endRow) {
        return endRow(new Text(endRow.toString()));
      }

      @Override
      public SummaryRetriever withConfiguration(Collection<SummarizerConfiguration> configs) {
        Objects.requireNonNull(configs);
        summariesToFetch = configs.stream().map(SummarizerConfigurationUtil::toThrift)
            .collect(Collectors.toList());
        return this;
      }

      @Override
      public SummaryRetriever withConfiguration(SummarizerConfiguration... config) {
        Objects.requireNonNull(config);
        return withConfiguration(Arrays.asList(config));
      }

      @Override
      public SummaryRetriever withMatchingConfiguration(String regex) {
        Objects.requireNonNull(regex);
        // Do a sanity check here to make sure that regex compiles, instead of having it fail on a
        // tserver.
        Pattern.compile(regex);
        this.summarizerClassRegex = regex;
        return this;
      }

      @Override
      public SummaryRetriever flush(boolean b) {
        this.flush = b;
        return this;
      }
    };
  }

  @Override
  public void addSummarizers(String tableName, SummarizerConfiguration... newConfigs)
      throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    HashSet<SummarizerConfiguration> currentConfigs =
        new HashSet<>(SummarizerConfiguration.fromTableProperties(getProperties(tableName)));
    HashSet<SummarizerConfiguration> newConfigSet = new HashSet<>(Arrays.asList(newConfigs));

    newConfigSet.removeIf(currentConfigs::contains);
    Set<String> newIds =
        newConfigSet.stream().map(SummarizerConfiguration::getPropertyId).collect(toSet());

    for (SummarizerConfiguration csc : currentConfigs) {
      if (newIds.contains(csc.getPropertyId())) {
        throw new IllegalArgumentException("Summarizer property id is in use by " + csc);
      }
    }

    Map<String,String> props = SummarizerConfiguration.toTableProperties(newConfigSet);
    modifyProperties(tableName, properties -> properties.putAll(props));
  }

  @Override
  public void removeSummarizers(String tableName, Predicate<SummarizerConfiguration> predicate)
      throws AccumuloException, TableNotFoundException, AccumuloSecurityException {
    EXISTING_TABLE_NAME.validate(tableName);

    Collection<SummarizerConfiguration> summarizerConfigs =
        SummarizerConfiguration.fromTableProperties(getProperties(tableName));

    modifyProperties(tableName,
        properties -> summarizerConfigs.stream().filter(predicate)
            .map(sc -> sc.toTableProperties().keySet())
            .forEach(keySet -> keySet.forEach(properties::remove)));

  }

  @Override
  public List<SummarizerConfiguration> listSummarizers(String tableName)
      throws AccumuloException, TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);
    return new ArrayList<>(SummarizerConfiguration.fromTableProperties(getProperties(tableName)));
  }

  @Override
  public ImportDestinationArguments importDirectory(String directory) {
    return new BulkImport(directory, context);
  }

  @Override
  public TimeType getTimeType(final String tableName) throws TableNotFoundException {
    TableId tableId = context.getTableId(tableName);
    Optional<TabletMetadata> tabletMetadata;
    try (TabletsMetadata tabletsMetadata =
        context.getAmple().readTablets().forTable(tableId).fetch(TIME).checkConsistency().build()) {
      tabletMetadata = tabletsMetadata.stream().findFirst();
    }
    TabletMetadata timeData =
        tabletMetadata.orElseThrow(() -> new IllegalStateException("Failed to retrieve TimeType"));
    return timeData.getTime().getType();
  }

  private void prependPropertiesToExclude(Map<String,String> opts, Set<String> propsToExclude) {
    if (propsToExclude == null) {
      return;
    }

    for (String prop : propsToExclude) {
      opts.put(PROPERTY_EXCLUDE_PREFIX + prop, "");
    }
  }

  private void validatePropertiesToSet(Map<String,String> opts, Map<String,String> propsToSet) {
    if (propsToSet == null) {
      return;
    }

    propsToSet.forEach((k, v) -> {
      if (k.startsWith(PROPERTY_EXCLUDE_PREFIX)) {
        throw new IllegalArgumentException(
            "Property can not start with " + PROPERTY_EXCLUDE_PREFIX);
      }
      opts.put(k, v);
    });
  }

  @Override
  public void setTabletAvailability(String tableName, Range range, TabletAvailability availability)
      throws AccumuloSecurityException, AccumuloException {
    EXISTING_TABLE_NAME.validate(tableName);
    if (SystemTables.containsTableName(tableName)) {
      throw new AccumuloException("Cannot set set tablet availability for table " + tableName);
    }

    checkArgument(range != null, "range is null");
    checkArgument(availability != null, "tabletAvailability is null");

    byte[] bRange;
    try {
      bRange = new TSerializer().serialize(range.toThrift());
    } catch (TException e) {
      throw new RuntimeException("Error serializing range object", e);
    }

    List<ByteBuffer> args = new ArrayList<>();
    args.add(ByteBuffer.wrap(tableName.getBytes(UTF_8)));
    args.add(ByteBuffer.wrap(bRange));
    args.add(ByteBuffer.wrap(availability.name().getBytes(UTF_8)));

    Map<String,String> opts = Collections.emptyMap();

    try {
      doTableFateOperation(tableName, AccumuloException.class,
          TFateOperation.TABLE_TABLET_AVAILABILITY, args, opts);
    } catch (TableNotFoundException | TableExistsException e) {
      // should not happen
      throw new AssertionError(e);
    }
  }

  @Override
  public Stream<TabletInformation> getTabletInformation(final String tableName, final Range range)
      throws TableNotFoundException {
    EXISTING_TABLE_NAME.validate(tableName);

    final Text scanRangeStart = (range.getStartKey() == null) ? null : range.getStartKey().getRow();
    TableId tableId = context.getTableId(tableName);

    TabletsMetadata tabletsMetadata =
        context.getAmple().readTablets().forTable(tableId).overlapping(scanRangeStart, true, null)
            .fetch(AVAILABILITY, LOCATION, DIR, PREV_ROW, FILES, LAST, LOGS, SUSPEND, MERGEABILITY)
            .checkConsistency().build();

    Set<TServerInstance> liveTserverSet = TabletMetadata.getLiveTServers(context);

    var currentTime = Suppliers.memoize(() -> {
      try {
        return Duration.ofNanos(ThriftClientTypes.MANAGER.execute(context,
            client -> client.getManagerTimeNanos(TraceUtil.traceInfo(), context.rpcCreds())));
      } catch (AccumuloException | AccumuloSecurityException e) {
        throw new IllegalStateException(e);
      }
    });

    return tabletsMetadata.stream().onClose(tabletsMetadata::close).peek(tm -> {
      if (scanRangeStart != null && tm.getEndRow() != null
          && tm.getEndRow().compareTo(scanRangeStart) < 0) {
        log.debug("tablet {} is before scan start range: {}", tm.getExtent(), scanRangeStart);
        throw new RuntimeException("Bug in ample or this code.");
      }
    }).takeWhile(tm -> tm.getPrevEndRow() == null
        || !range.afterEndKey(new Key(tm.getPrevEndRow()).followingKey(PartialKey.ROW)))
        .map(tm -> new TabletInformationImpl(tm, TabletState.compute(tm, liveTserverSet).toString(),
            currentTime));
  }

}
