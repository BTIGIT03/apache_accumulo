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
package org.apache.accumulo.manager.compaction.queue;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.schema.Ample.DataLevel;
import org.apache.accumulo.core.spi.compaction.CompactionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompactionJobQueues {

  private static final Logger log = LoggerFactory.getLogger(CompactionJobQueues.class);

  // The code in this class specifically depends on the behavior of ConcurrentHashMap, behavior that
  // other ConcurrentMap implementations may not have. The behavior it depended on is that the
  // compute functions for a key are atomic. This is documented on its javadoc and in the impl it
  // can be observed that scoped locks are acquired. Other concurrent map impls may run the compute
  // lambdas concurrently for a given key, which may still be correct but is more difficult to
  // analyze.
  private final ConcurrentHashMap<ResourceGroupId,CompactionJobPriorityQueue> priorityQueues =
      new ConcurrentHashMap<>();

  private volatile long queueSize;

  private final Map<DataLevel,AtomicLong> currentGenerations;

  public CompactionJobQueues(long queueSize) {
    this.queueSize = queueSize;
    Map<DataLevel,AtomicLong> cg = new EnumMap<>(DataLevel.class);
    for (var level : DataLevel.values()) {
      cg.put(level, new AtomicLong());
    }
    currentGenerations = Collections.unmodifiableMap(cg);
  }

  public void beginFullScan(DataLevel level) {
    currentGenerations.get(level).incrementAndGet();
  }

  /**
   * The purpose of this method is to remove any tablets that were added before beginFullScan() was
   * called. The purpose of this is to handle tablets that were queued for compaction for a while
   * and because of some change no longer need to compact. If a full scan of the metadata table does
   * not find any new work for tablet, then any previously queued work for that tablet should be
   * discarded.
   *
   * @param level full metadata scans are done independently per DataLevel, so the tracking what
   *        needs to be removed must be done per DataLevel
   */
  public void endFullScan(DataLevel level) {
    priorityQueues.values()
        .forEach(pq -> pq.removeOlderGenerations(level, currentGenerations.get(level).get()));
  }

  public void add(KeyExtent extent, Collection<CompactionJob> jobs) {
    if (jobs.size() == 1) {
      var executorId = jobs.iterator().next().getGroup();
      add(extent, executorId, jobs);
    } else {
      jobs.stream().collect(Collectors.groupingBy(CompactionJob::getGroup))
          .forEach(((groupId, compactionJobs) -> add(extent, groupId, compactionJobs)));
    }
  }

  public KeySetView<ResourceGroupId,CompactionJobPriorityQueue> getQueueIds() {
    return priorityQueues.keySet();
  }

  public CompactionJobPriorityQueue getQueue(ResourceGroupId groupId) {
    return priorityQueues.get(groupId);
  }

  public long getQueueMaxSize(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    return prioQ == null ? 0 : prioQ.getMaxSize();
  }

  public long getQueuedJobs(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    return prioQ == null ? 0 : prioQ.getQueuedJobs();
  }

  public long getDequeuedJobs(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    return prioQ == null ? 0 : prioQ.getDequeuedJobs();
  }

  public long getRejectedJobs(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    return prioQ == null ? 0 : prioQ.getRejectedJobs();
  }

  public long getLowestPriority(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    return prioQ == null ? 0 : prioQ.getLowestPriority();
  }

  public long getQueueCount() {
    return priorityQueues.mappingCount();
  }

  public long getQueuedJobCount() {
    long count = 0;
    for (CompactionJobPriorityQueue queue : priorityQueues.values()) {
      count += queue.getQueuedJobs();
    }
    return count;
  }

  /**
   * Asynchronously get a compaction job from the queue. If the queue currently has jobs then a
   * completed future will be returned containing the highest priority job in the queue. If the
   * queue is currently empty, then an uncompleted future will be returned and later when something
   * is added to the queue the future will be completed.
   */
  public CompletableFuture<CompactionJob> getAsync(ResourceGroupId groupId) {
    var pq = priorityQueues.computeIfAbsent(groupId,
        gid -> new CompactionJobPriorityQueue(gid, queueSize, ResolvedCompactionJob.WEIGHER));
    return pq.getAsync();
  }

  public CompactionJob poll(ResourceGroupId groupId) {
    var prioQ = priorityQueues.get(groupId);
    if (prioQ == null) {
      return null;
    }

    return prioQ.poll();
  }

  private void add(KeyExtent extent, ResourceGroupId groupId, Collection<CompactionJob> jobs) {

    if (log.isTraceEnabled()) {
      log.trace("Adding jobs to queue {} {} {}", groupId, extent,
          jobs.stream().map(job -> "#files:" + job.getFiles().size() + ",prio:" + job.getPriority()
              + ",kind:" + job.getKind()).collect(Collectors.toList()));
    }

    var pq = priorityQueues.computeIfAbsent(groupId,
        gid -> new CompactionJobPriorityQueue(gid, queueSize, ResolvedCompactionJob.WEIGHER));
    pq.add(extent, jobs, currentGenerations.get(DataLevel.of(extent.tableId())).get());
  }

  public void resetMaxSize(long size) {
    this.queueSize = size;
    priorityQueues.values().forEach(cjpq -> cjpq.resetMaxSize(this.queueSize));
  }
}
