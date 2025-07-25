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
package org.apache.accumulo.tserver.log;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.max;
import static org.apache.accumulo.tserver.logger.LogEvents.COMPACTION_FINISH;
import static org.apache.accumulo.tserver.logger.LogEvents.COMPACTION_START;
import static org.apache.accumulo.tserver.logger.LogEvents.DEFINE_TABLET;
import static org.apache.accumulo.tserver.logger.LogEvents.MANY_MUTATIONS;
import static org.apache.accumulo.tserver.logger.LogEvents.MUTATION;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.file.blockfile.impl.CacheProvider;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.tserver.logger.LogEvents;
import org.apache.accumulo.tserver.logger.LogFileKey;
import org.apache.accumulo.tserver.logger.LogFileValue;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

/**
 * Extract Mutations for a tablet from a set of logs that have been sorted by operation and tablet.
 *
 */
public class SortedLogRecovery {

  private static final Logger log = LoggerFactory.getLogger(SortedLogRecovery.class);

  private final ServerContext context;

  private final CacheProvider cacheProvider;

  private final Cache<String,Long> fileLenCache;

  public SortedLogRecovery(ServerContext context, Cache<String,Long> fileLenCache,
      CacheProvider cacheProvider) {
    this.context = context;
    this.cacheProvider = cacheProvider;
    this.fileLenCache = fileLenCache;
  }

  static LogFileKey maxKey(LogEvents event) {
    LogFileKey key = new LogFileKey();
    key.setEvent(event);
    key.setTabletId(Integer.MAX_VALUE);
    key.setSeq(Long.MAX_VALUE);
    return key;
  }

  static LogFileKey maxKey(LogEvents event, int tabletId) {
    LogFileKey key = maxKey(event);
    key.setTabletId(tabletId);
    return key;
  }

  static LogFileKey minKey(LogEvents event) {
    LogFileKey key = new LogFileKey();
    key.setEvent(event);
    // see GitHub issue #477. There was a bug that caused -1 to end up in tabletId. If this happens
    // want to detect it and fail since recovery is dubious in this situation . Other code should
    // fail if the id is actually -1 in data.
    key.setTabletId(-1);
    key.setSeq(0);
    return key;
  }

  static LogFileKey minKey(LogEvents event, int tabletId) {
    LogFileKey key = minKey(event);
    key.setTabletId(tabletId);
    return key;
  }

  private int findMaxTabletId(KeyExtent extent, List<ResolvedSortedLog> recoveryLogDirs)
      throws IOException {
    int tabletId = -1;

    try (var rli = new RecoveryLogsIterator(context, recoveryLogDirs, minKey(DEFINE_TABLET),
        maxKey(DEFINE_TABLET), true, fileLenCache, cacheProvider)) {

      KeyExtent alternative = extent;
      if (extent.isRootTablet()) {
        alternative = RootTable.OLD_EXTENT;
      }

      while (rli.hasNext()) {
        LogFileKey key = rli.next().getKey();

        checkState(key.getEvent() == DEFINE_TABLET); // should only fail if bug elsewhere

        if (key.getTablet().equals(extent) || key.getTablet().equals(alternative)) {
          checkState(key.getTabletId() >= 0, "tabletId %s for %s is negative", key.getTabletId(),
              extent);
          checkState(tabletId == -1 || key.getTabletId() >= tabletId); // should only fail if bug in
          // RecoveryLogsIterator

          if (tabletId != key.getTabletId()) {
            tabletId = key.getTabletId();
          }
        }
      }
    }
    return tabletId;
  }

  /**
   * This function opens recovery logs one at a time to see if they define the tablet. This is done
   * so that later recovery steps that open all of the logs at once can possibly open a smaller set
   * of logs. Opening a recovery log requires holding its index in memory and few key/values from
   * it. For a lot of recovery logs this could possibly be a lot of memory.
   *
   * @return The maximum tablet ID observed AND the list of logs that contained the maximum tablet
   *         ID.
   */
  private Entry<Integer,List<ResolvedSortedLog>> findLogsThatDefineTablet(KeyExtent extent,
      List<ResolvedSortedLog> recoveryDirs) throws IOException {
    Map<Integer,List<ResolvedSortedLog>> logsThatDefineTablet = new HashMap<>();

    for (ResolvedSortedLog walDir : recoveryDirs) {
      int tabletId = findMaxTabletId(extent, Collections.singletonList(walDir));
      if (tabletId == -1) {
        log.debug("Did not find tablet {} in recovery log {}", extent, walDir);
      } else {
        logsThatDefineTablet.computeIfAbsent(tabletId, k -> new ArrayList<>()).add(walDir);
        log.debug("Found tablet {} with id {} in recovery log {}", extent, tabletId, walDir);
      }
    }

    if (logsThatDefineTablet.isEmpty()) {
      return new AbstractMap.SimpleEntry<>(-1, Collections.emptyList());
    } else {
      return max(logsThatDefineTablet.entrySet(), Comparator.comparingInt(Entry::getKey));
    }
  }

  private String getPathSuffix(String pathString) {
    Path path = new Path(pathString);
    if (path.depth() < 2) {
      throw new IllegalArgumentException("Bad path " + pathString);
    }
    return path.getParent().getName() + "/" + path.getName();
  }

  static class DeduplicatingIterator implements Iterator<Entry<LogFileKey,LogFileValue>> {

    private final PeekingIterator<Entry<LogFileKey,LogFileValue>> source;

    public DeduplicatingIterator(Iterator<Entry<LogFileKey,LogFileValue>> source) {
      this.source = Iterators.peekingIterator(source);
    }

    @Override
    public boolean hasNext() {
      return source.hasNext();
    }

    @Override
    public Entry<LogFileKey,LogFileValue> next() {
      Entry<LogFileKey,LogFileValue> next = source.next();

      while (source.hasNext() && next.getKey().compareTo(source.peek().getKey()) == 0) {
        source.next();
      }

      return next;
    }

  }

  private long findRecoverySeq(List<ResolvedSortedLog> recoveryLogs, Set<String> tabletFiles,
      int tabletId) throws IOException {
    HashSet<String> suffixes = new HashSet<>();
    for (String path : tabletFiles) {
      suffixes.add(getPathSuffix(path));
    }

    long lastStart = 0;
    long lastFinish = 0;
    long recoverySeq = 0;

    try (RecoveryLogsIterator rli =
        new RecoveryLogsIterator(context, recoveryLogs, minKey(COMPACTION_START, tabletId),
            maxKey(COMPACTION_START, tabletId), false, fileLenCache, cacheProvider)) {

      DeduplicatingIterator ddi = new DeduplicatingIterator(rli);

      String lastStartFile = null;
      LogEvents lastEvent = null;

      while (ddi.hasNext()) {
        LogFileKey key = ddi.next().getKey();

        checkState(key.getSeq() >= 0, "Unexpected negative seq %s for tabletId %s", key.getSeq(),
            tabletId);
        checkState(key.getTabletId() == tabletId); // should only fail if bug elsewhere
        checkState(key.getSeq() >= Math.max(lastFinish, lastStart)); // should only fail if bug
                                                                     // elsewhere

        switch (key.getEvent()) {
          case COMPACTION_START:
            lastStart = key.getSeq();
            lastStartFile = key.getFilename();
            break;
          case COMPACTION_FINISH:
            checkState(key.getSeq() > lastStart, "Compaction finish <= start %s %s %s",
                key.getTabletId(), key.getSeq(), lastStart);
            checkState(lastEvent != COMPACTION_FINISH,
                "Saw consecutive COMPACTION_FINISH events %s %s %s", key.getTabletId(), lastFinish,
                key.getSeq());
            lastFinish = key.getSeq();
            break;
          default:
            throw new IllegalStateException("Non compaction event seen " + key.getEvent());
        }

        lastEvent = key.getEvent();
      }

      if (lastEvent == COMPACTION_START && suffixes.contains(getPathSuffix(lastStartFile))) {
        // There was no compaction finish event following this start, however the last compaction
        // start event has a file in the metadata table, so the compaction finished.
        log.debug("Considering compaction start {} {} finished because file {} in metadata table",
            tabletId, lastStart, getPathSuffix(lastStartFile));
        recoverySeq = lastStart;
      } else {
        // Recover everything >= the maximum finish sequence number if its set, otherwise return 0.
        recoverySeq = Math.max(0, lastFinish - 1);
      }
    }
    return recoverySeq;
  }

  private void playbackMutations(List<ResolvedSortedLog> recoveryLogs, MutationReceiver mr,
      int tabletId, long recoverySeq) throws IOException {
    LogFileKey start = minKey(MUTATION, tabletId);
    start.setSeq(recoverySeq);

    LogFileKey end = maxKey(MUTATION, tabletId);

    try (var rli = new RecoveryLogsIterator(context, recoveryLogs, start, end, false, fileLenCache,
        cacheProvider)) {
      while (rli.hasNext()) {
        Entry<LogFileKey,LogFileValue> entry = rli.next();
        LogFileKey logFileKey = entry.getKey();

        checkState(logFileKey.getTabletId() == tabletId); // should only fail if bug elsewhere
        checkState(logFileKey.getSeq() >= recoverySeq); // should only fail if bug elsewhere

        LogFileValue val = entry.getValue();
        if (logFileKey.getEvent() == MUTATION || logFileKey.getEvent() == MANY_MUTATIONS) {
          log.debug("Recover {} mutation(s) for {}", val.getMutations().size(), entry.getKey());
          for (Mutation m : val.getMutations()) {
            mr.receive(m);
          }
        } else {
          throw new IllegalStateException("Non mutation event seen " + logFileKey.getEvent());
        }
      }
    }
  }

  Collection<String> asNames(List<ResolvedSortedLog> recoveryLogs) {
    return Collections2.transform(recoveryLogs, rsl -> rsl.getDir().getName());
  }

  public boolean needsRecovery(KeyExtent extent, List<ResolvedSortedLog> recoveryDirs)
      throws IOException {
    Entry<Integer,List<ResolvedSortedLog>> maxEntry =
        findLogsThatDefineTablet(extent, recoveryDirs);
    int tabletId = maxEntry.getKey();
    return tabletId != -1;
  }

  public void recover(KeyExtent extent, List<ResolvedSortedLog> recoveryDirs,
      Set<String> tabletFiles, MutationReceiver mr) throws IOException {

    Entry<Integer,List<ResolvedSortedLog>> maxEntry =
        findLogsThatDefineTablet(extent, recoveryDirs);

    // A tablet may leave a tserver and then come back, in which case it would have a different and
    // higher tablet id. Only want to consider events in the log related to the last time the tablet
    // was loaded.
    int tabletId = maxEntry.getKey();
    List<ResolvedSortedLog> logsThatDefineTablet = maxEntry.getValue();

    if (tabletId == -1) {
      log.info("Tablet {} is not defined in recovery logs {} ", extent, asNames(recoveryDirs));
      return;
    } else {
      log.info("Found {} of {} logs with max id {} for tablet {}", logsThatDefineTablet.size(),
          recoveryDirs.size(), tabletId, extent);
    }

    // Find the seq # for the last compaction that started and finished
    long recoverySeq = findRecoverySeq(logsThatDefineTablet, tabletFiles, tabletId);

    log.info("Recovering mutations, tablet:{} tabletId:{} seq:{} logs:{}", extent, tabletId,
        recoverySeq, asNames(logsThatDefineTablet));

    // Replay all mutations that were written after the last successful compaction started.
    playbackMutations(logsThatDefineTablet, mr, tabletId, recoverySeq);
  }
}
