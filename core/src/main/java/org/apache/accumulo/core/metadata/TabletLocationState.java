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
package org.apache.accumulo.core.metadata;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.Location;
import org.apache.accumulo.core.util.TextUtil;
import org.apache.hadoop.io.Text;

/**
 * When a tablet is assigned, we mark its future location. When the tablet is opened, we set its
 * current location. A tablet should never have both a future and current location.
 *
 * A tablet server is always associated with a unique session id. If the current tablet server has a
 * different session, we know the location information is out-of-date.
 */
public class TabletLocationState {

  public static class BadLocationStateException extends Exception {
    private static final long serialVersionUID = 2L;

    // store as byte array because Text isn't Serializable
    private final byte[] metadataTableEntry;

    public BadLocationStateException(String msg, Text row) {
      super(msg);
      this.metadataTableEntry = TextUtil.getBytes(requireNonNull(row));
    }

    public Text getEncodedEndRow() {
      return new Text(metadataTableEntry);
    }
  }

  public TabletLocationState(KeyExtent extent, Location future, Location current, Location last,
      SuspendingTServer suspend, Collection<Collection<String>> walogs)
      throws BadLocationStateException {
    this.extent = extent;
    this.future = validateLocation(future, TabletMetadata.LocationType.FUTURE);
    this.current = validateLocation(current, TabletMetadata.LocationType.CURRENT);
    this.last = validateLocation(last, TabletMetadata.LocationType.LAST);
    this.suspend = suspend;
    if (walogs == null) {
      walogs = Collections.emptyList();
    }
    this.walogs = walogs;
    if (hasCurrent() && hasFuture()) {
      throw new BadLocationStateException(
          extent + " is both assigned and hosted, which should never happen: " + this,
          extent.toMetaRow());
    }
  }

  public final KeyExtent extent;
  public final Location future;
  public final Location current;
  public final Location last;
  public final SuspendingTServer suspend;
  public final Collection<Collection<String>> walogs;

  public TServerInstance getCurrentServer() {
    return serverInstance(current);
  }

  public TServerInstance getFutureServer() {
    return serverInstance(future);
  }

  public TServerInstance getLastServer() {
    return serverInstance(last);
  }

  public TServerInstance futureOrCurrentServer() {
    return serverInstance(futureOrCurrent());
  }

  public Location futureOrCurrent() {
    if (hasCurrent()) {
      return current;
    }
    return future;
  }

  public TServerInstance getServer() {
    return serverInstance(getLocation());
  }

  public Location getLocation() {
    Location result = null;
    if (hasCurrent()) {
      result = current;
    } else if (hasFuture()) {
      result = future;
    } else {
      result = last;
    }
    return result;
  }

  public boolean hasCurrent() {
    return current != null;
  }

  public boolean hasFuture() {
    return future != null;
  }

  public boolean hasSuspend() {
    return suspend != null;
  }

  public TabletState getState(Set<TServerInstance> liveServers) {
    if (hasFuture()) {
      return liveServers.contains(future.getServerInstance()) ? TabletState.ASSIGNED
          : TabletState.ASSIGNED_TO_DEAD_SERVER;
    } else if (hasCurrent()) {
      return liveServers.contains(current.getServerInstance()) ? TabletState.HOSTED
          : TabletState.ASSIGNED_TO_DEAD_SERVER;
    } else if (hasSuspend()) {
      return TabletState.SUSPENDED;
    } else {
      return TabletState.UNASSIGNED;
    }
  }

  @Override
  public String toString() {
    return extent + "@(" + future + "," + current + "," + last + ")";
  }

  private static Location validateLocation(final Location location,
      final TabletMetadata.LocationType type) {
    if (location != null && !location.getType().equals(type)) {
      throw new IllegalArgumentException("Location type is required to be of type " + type);
    }
    return location;
  }

  protected static TServerInstance serverInstance(final Location location) {
    return location != null ? location.getServerInstance() : null;
  }
}
