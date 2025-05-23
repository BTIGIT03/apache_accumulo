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
package org.apache.accumulo.test.functional;

import static org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.Upgrade12to13.SPLIT_RATIO_COLUMN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TimeType;
import org.apache.accumulo.core.clientImpl.ScannerImpl;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateInstanceType;
import org.apache.accumulo.core.file.rfile.RFile;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.metadata.ReferencedTabletFile;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.schema.Ample.TabletMutator;
import org.apache.accumulo.core.metadata.schema.DataFileValue;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.BulkFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CurrentLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.FutureLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.LastLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ServerColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.TabletColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataTime;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.Location;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.ColumnFQ;
import org.apache.accumulo.manager.upgrade.SplitRecovery12to13;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.manager.state.Assignment;
import org.apache.accumulo.server.util.MetadataTableUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

public class SplitRecoveryIT extends ConfigurableMacBase {

  public static Map<StoredTabletFile,DataFileValue> updateTabletDataFile(FateId fateId,
      KeyExtent extent, Map<ReferencedTabletFile,DataFileValue> estSizes, MetadataTime time,
      ServerContext context, ServiceLock zooLock) {
    TabletMutator tablet = context.getAmple().mutateTablet(extent);
    tablet.putTime(time);

    Map<StoredTabletFile,DataFileValue> newFiles = new HashMap<>(estSizes.size());
    estSizes.forEach((tf, dfv) -> {
      tablet.putFile(tf, dfv);
      tablet.putBulkFile(tf, fateId);
      newFiles.put(tf.insert(), dfv);
    });
    tablet.mutate();
    return newFiles;
  }

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(1);
  }

  private KeyExtent nke(String table, String endRow, String prevEndRow) {
    return new KeyExtent(TableId.of(table), endRow == null ? null : new Text(endRow),
        prevEndRow == null ? null : new Text(prevEndRow));
  }

  @Test
  public void run() throws Exception {

    ServerContext c = getCluster().getServerContext();
    ServiceLock zl = c.getServiceLock();

    // run test for a table with one tablet
    runSplitRecoveryTest(c, 0, "sp", 0, zl, nke("foo0", null, null));
    runSplitRecoveryTest(c, 1, "sp", 0, zl, nke("foo1", null, null));

    // run test for tables with two tablets, run test on first and last tablet
    runSplitRecoveryTest(c, 0, "k", 0, zl, nke("foo2", "m", null), nke("foo2", null, "m"));
    runSplitRecoveryTest(c, 1, "k", 0, zl, nke("foo3", "m", null), nke("foo3", null, "m"));
    runSplitRecoveryTest(c, 0, "o", 1, zl, nke("foo4", "m", null), nke("foo4", null, "m"));
    runSplitRecoveryTest(c, 1, "o", 1, zl, nke("foo5", "m", null), nke("foo5", null, "m"));

    // run test for table w/ three tablets, run test on middle tablet
    runSplitRecoveryTest(c, 0, "o", 1, zl, nke("foo6", "m", null), nke("foo6", "r", "m"),
        nke("foo6", null, "r"));
    runSplitRecoveryTest(c, 1, "o", 1, zl, nke("foo7", "m", null), nke("foo7", "r", "m"),
        nke("foo7", null, "r"));

    // run test for table w/ three tablets, run test on first
    runSplitRecoveryTest(c, 0, "g", 0, zl, nke("foo8", "m", null), nke("foo8", "r", "m"),
        nke("foo8", null, "r"));
    runSplitRecoveryTest(c, 1, "g", 0, zl, nke("foo9", "m", null), nke("foo9", "r", "m"),
        nke("foo9", null, "r"));

    // run test for table w/ three tablets, run test on last tablet
    runSplitRecoveryTest(c, 0, "w", 2, zl, nke("fooa", "m", null), nke("fooa", "r", "m"),
        nke("fooa", null, "r"));
    runSplitRecoveryTest(c, 1, "w", 2, zl, nke("foob", "m", null), nke("foob", "r", "m"),
        nke("foob", null, "r"));
  }

  private void runSplitRecoveryTest(ServerContext context, int failPoint, String mr,
      int extentToSplit, ServiceLock zl, KeyExtent... extents) throws Exception {

    Text midRow = new Text(mr);

    SortedMap<StoredTabletFile,DataFileValue> splitDataFiles = null;

    for (int i = 0; i < extents.length; i++) {
      KeyExtent extent = extents[i];

      String dirName = "dir_" + i;
      String tdir =
          context.getTablesDirs().iterator().next() + "/" + extent.tableId() + "/" + dirName;
      addTablet(extent, dirName, context, TimeType.LOGICAL);
      SortedMap<ReferencedTabletFile,DataFileValue> dataFiles = new TreeMap<>();
      dataFiles.put(new ReferencedTabletFile(new Path(tdir + "/" + RFile.EXTENSION + "_000_000")),
          new DataFileValue(1000017 + i, 10000 + i));

      FateId fateId =
          FateId.from(FateInstanceType.fromTableId(extent.tableId()), UUID.randomUUID());
      SortedMap<StoredTabletFile,DataFileValue> storedFiles =
          new TreeMap<>(updateTabletDataFile(fateId, extent, dataFiles,
              new MetadataTime(0, TimeType.LOGICAL), context, zl));
      if (i == extentToSplit) {
        splitDataFiles = storedFiles;
      }
    }

    KeyExtent extent = extents[extentToSplit];

    KeyExtent high = new KeyExtent(extent.tableId(), extent.endRow(), midRow);
    KeyExtent low = new KeyExtent(extent.tableId(), midRow, extent.prevEndRow());

    splitPartiallyAndRecover(context, extent, high, low, .4, splitDataFiles, midRow,
        "localhost:1234", failPoint, zl);
  }

  private static Map<FateId,List<ReferencedTabletFile>> getBulkFilesLoaded(ServerContext context,
      KeyExtent extent) {

    // Ample is not used here because it does not recognize some of the old columns that this
    // upgrade code is dealing with.
    try (Scanner scanner =
        context.createScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY)) {
      scanner.setRange(extent.toMetaRange());

      Map<FateId,List<ReferencedTabletFile>> bulkFiles = new HashMap<>();
      for (var entry : scanner) {
        if (entry.getKey().getColumnFamily().equals(BulkFileColumnFamily.NAME)) {
          var path = new StoredTabletFile(entry.getKey().getColumnQualifier().toString());
          var txid = BulkFileColumnFamily.getBulkLoadTid(entry.getValue());
          bulkFiles.computeIfAbsent(txid, k -> new ArrayList<>()).add(path.getTabletFile());
        }
      }

      return bulkFiles;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void splitPartiallyAndRecover(ServerContext context, KeyExtent extent, KeyExtent high,
      KeyExtent low, double splitRatio, SortedMap<StoredTabletFile,DataFileValue> dataFiles,
      Text midRow, String location, int steps, ServiceLock zl) throws Exception {

    SortedMap<StoredTabletFile,DataFileValue> lowDatafileSizes = new TreeMap<>();
    SortedMap<StoredTabletFile,DataFileValue> highDatafileSizes = new TreeMap<>();
    List<StoredTabletFile> highDatafilesToRemove = new ArrayList<>();

    SplitRecovery12to13.splitDatafiles(midRow, splitRatio, new HashMap<>(), dataFiles,
        lowDatafileSizes, highDatafileSizes, highDatafilesToRemove);

    SplitRecovery12to13.splitTablet(high, extent.prevEndRow(), splitRatio, context, Set.of());
    TServerInstance instance = new TServerInstance(location, zl.getSessionId());
    Assignment assignment = new Assignment(high, instance, null);

    TabletMutator tabletMutator = context.getAmple().mutateTablet(extent);
    tabletMutator.putLocation(Location.future(assignment.server));
    tabletMutator.mutate();

    if (steps >= 1) {
      Map<FateId,List<ReferencedTabletFile>> bulkFiles = getBulkFilesLoaded(context, high);

      addNewTablet(context, low, "lowDir", instance, lowDatafileSizes, bulkFiles,
          new MetadataTime(0, TimeType.LOGICAL), -1L);
    }
    if (steps >= 2) {
      SplitRecovery12to13.finishSplit(high, highDatafileSizes, highDatafilesToRemove, context);
    }

    if (steps < 2) {
      Double persistedSplitRatio = null;

      try (var scanner =
          context.createScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY)) {
        scanner.setRange(high.toMetaRange());
        for (var entry : scanner) {
          if (SPLIT_RATIO_COLUMN.hasColumns(entry.getKey())) {
            persistedSplitRatio = Double.parseDouble(entry.getValue().toString());
          }
        }
      }
      assertEquals(splitRatio, persistedSplitRatio, 0.0);
    }

    KeyExtent fixedExtent = SplitRecovery12to13.fixSplit(context, high.toMetaRow());

    if (steps >= 1) {
      assertEquals(high, fixedExtent);
      ensureTabletHasNoUnexpectedMetadataEntries(context, low, lowDatafileSizes);
      ensureTabletHasNoUnexpectedMetadataEntries(context, high, highDatafileSizes);

      Map<FateId,? extends Collection<ReferencedTabletFile>> lowBulkFiles =
          getBulkFilesLoaded(context, low);
      Map<FateId,? extends Collection<ReferencedTabletFile>> highBulkFiles =
          getBulkFilesLoaded(context, high);

      if (!lowBulkFiles.equals(highBulkFiles)) {
        throw new Exception(" " + lowBulkFiles + " != " + highBulkFiles + " " + low + " " + high);
      }

      if (lowBulkFiles.isEmpty()) {
        throw new Exception(" no bulk files " + low);
      }
    } else {
      assertEquals(extent, fixedExtent);
      ensureTabletHasNoUnexpectedMetadataEntries(context, extent, dataFiles);
    }
  }

  private void ensureTabletHasNoUnexpectedMetadataEntries(ServerContext context, KeyExtent extent,
      SortedMap<StoredTabletFile,DataFileValue> expectedDataFiles) throws Exception {
    try (Scanner scanner =
        new ScannerImpl(context, SystemTables.METADATA.tableId(), Authorizations.EMPTY)) {
      scanner.setRange(extent.toMetaRange());

      HashSet<ColumnFQ> expectedColumns = new HashSet<>();
      expectedColumns.add(ServerColumnFamily.DIRECTORY_COLUMN);
      expectedColumns.add(TabletColumnFamily.PREV_ROW_COLUMN);
      expectedColumns.add(ServerColumnFamily.TIME_COLUMN);
      expectedColumns.add(ServerColumnFamily.LOCK_COLUMN);

      HashSet<Text> expectedColumnFamilies = new HashSet<>();
      expectedColumnFamilies.add(DataFileColumnFamily.NAME);
      expectedColumnFamilies.add(FutureLocationColumnFamily.NAME);
      expectedColumnFamilies.add(CurrentLocationColumnFamily.NAME);
      expectedColumnFamilies.add(LastLocationColumnFamily.NAME);
      expectedColumnFamilies.add(BulkFileColumnFamily.NAME);

      Iterator<Entry<Key,Value>> iter = scanner.iterator();

      boolean sawPer = false;

      while (iter.hasNext()) {
        Entry<Key,Value> entry = iter.next();
        Key key = entry.getKey();

        if (!key.getRow().equals(extent.toMetaRow())) {
          throw new Exception("Tablet " + extent + " contained unexpected "
              + SystemTables.METADATA.tableName() + " entry " + key);
        }

        if (TabletColumnFamily.PREV_ROW_COLUMN.hasColumns(key)) {
          sawPer = true;
          if (!KeyExtent.fromMetaPrevRow(entry).equals(extent)) {
            throw new Exception("Unexpected prev end row " + entry);
          }
        }

        if (expectedColumnFamilies.contains(key.getColumnFamily())) {
          continue;
        }

        if (expectedColumns.remove(new ColumnFQ(key))) {
          continue;
        }

        throw new Exception("Tablet " + extent + " contained unexpected "
            + SystemTables.METADATA.tableName() + " entry " + key);
      }

      // This is not always present
      expectedColumns.remove(ServerColumnFamily.LOCK_COLUMN);

      if (expectedColumns.size() > 1 || (expectedColumns.size() == 1)) {
        throw new Exception("Not all expected columns seen " + extent + " " + expectedColumns);
      }

      assertTrue(sawPer);

      SortedMap<StoredTabletFile,DataFileValue> fixedDataFiles =
          MetadataTableUtil.getFileAndLogEntries(context, extent).getSecond();
      verifySame(expectedDataFiles, fixedDataFiles);
    }
  }

  private void verifySame(SortedMap<StoredTabletFile,DataFileValue> datafileSizes,
      SortedMap<StoredTabletFile,DataFileValue> fixedDatafileSizes) throws Exception {

    if (!datafileSizes.keySet().containsAll(fixedDatafileSizes.keySet())
        || !fixedDatafileSizes.keySet().containsAll(datafileSizes.keySet())) {
      throw new Exception("Key sets not the same " + datafileSizes.keySet() + " !=  "
          + fixedDatafileSizes.keySet());
    }

    for (Entry<StoredTabletFile,DataFileValue> entry : datafileSizes.entrySet()) {
      DataFileValue dfv = entry.getValue();
      DataFileValue otherDfv = fixedDatafileSizes.get(entry.getKey());

      if (!dfv.equals(otherDfv)) {
        throw new Exception(entry.getKey() + " dfv not equal  " + dfv + "  " + otherDfv);
      }
    }
  }

  public void addTablet(KeyExtent extent, String path, ServerContext context, TimeType timeType) {
    TabletMutator tablet = context.getAmple().mutateTablet(extent);
    tablet.putPrevEndRow(extent.prevEndRow());
    tablet.putDirName(path);
    tablet.putTime(new MetadataTime(0, timeType));
    tablet.mutate();

  }

  public void addNewTablet(ServerContext context, KeyExtent extent, String dirName,
      TServerInstance tServerInstance, Map<StoredTabletFile,DataFileValue> datafileSizes,
      Map<FateId,? extends Collection<ReferencedTabletFile>> bulkLoadedFiles, MetadataTime time,
      long lastFlushID) {

    TabletMutator tablet = context.getAmple().mutateTablet(extent);
    tablet.putPrevEndRow(extent.prevEndRow());
    tablet.putDirName(dirName);
    tablet.putTime(time);

    if (lastFlushID > 0) {
      tablet.putFlushId(lastFlushID);
    }

    if (tServerInstance != null) {
      tablet.putLocation(Location.current(tServerInstance));
      tablet.deleteLocation(Location.future(tServerInstance));
    }

    datafileSizes.forEach((key, value) -> tablet.putFile(key, value));

    for (Entry<FateId,? extends Collection<ReferencedTabletFile>> entry : bulkLoadedFiles
        .entrySet()) {
      for (ReferencedTabletFile ref : entry.getValue()) {
        tablet.putBulkFile(ref, entry.getKey());
      }
    }

    tablet.mutate();
  }
}
