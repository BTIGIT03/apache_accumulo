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
package org.apache.accumulo.monitor.rest.tables;

import static org.apache.accumulo.monitor.util.ParameterValidator.ALPHA_NUM_REGEX_TABLE_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.manager.thrift.ManagerMonitorInfo;
import org.apache.accumulo.core.manager.thrift.TableInfo;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.schema.Ample;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletsMetadata;
import org.apache.accumulo.core.metadata.schema.filters.HasCurrentFilter;
import org.apache.accumulo.monitor.Monitor;
import org.apache.accumulo.monitor.rest.tservers.TabletServer;
import org.apache.accumulo.monitor.rest.tservers.TabletServers;
import org.apache.accumulo.server.tables.TableManager;
import org.apache.accumulo.server.util.TableInfoUtil;

/**
 * Generates a tables list from the Monitor as a JSON object
 *
 * @since 2.0.0
 */
@Path("/tables")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class TablesResource {
  /**
   * A {@code String} constant representing Table ID to find participating tservers, used in path
   * parameter.
   */
  private static final String TABLEID_PARAM_KEY = "tableId";

  @Inject
  private Monitor monitor;

  private static final TabletServerStatus NO_STATUS = new TabletServerStatus();

  /**
   * Generates a list of all the tables
   *
   * @return list with all tables
   */
  @GET
  public TableInformationList getTables() {
    return getTables(monitor);
  }

  public static TableInformationList getTables(Monitor monitor) {
    TableInformationList tableList = new TableInformationList();
    ManagerMonitorInfo mmi = monitor.getMmi();
    if (mmi == null) {
      return tableList;
    }
    SortedMap<TableId,TableInfo> tableStats = new TreeMap<>();

    if (mmi.tableMap != null) {
      for (Map.Entry<String,TableInfo> te : mmi.tableMap.entrySet()) {
        tableStats.put(TableId.of(te.getKey()), te.getValue());
      }
    }

    Map<String,Double> compactingByTable = TableInfoUtil.summarizeTableStats(mmi);
    TableManager tableManager = monitor.getContext().getTableManager();

    // Add tables to the list
    monitor.getContext().createQualifiedTableNameToIdMap().forEach((tableName, tableId) -> {
      TableInfo tableInfo = tableStats.get(tableId);
      TableState tableState = tableManager.getTableState(tableId);

      if (tableInfo != null && tableState != TableState.OFFLINE) {
        Double holdTime = compactingByTable.get(tableId.canonical());
        if (holdTime == null) {
          holdTime = 0.;
        }

        tableList.addTable(
            new TableInformation(tableName, tableId, tableInfo, holdTime, tableState.name()));
      } else {
        tableList.addTable(new TableInformation(tableName, tableId, tableState.name()));
      }
    });
    return tableList;
  }

  /**
   * Generates a list of participating tservers for a table
   *
   * @param tableIdStr Table ID to find participating tservers
   * @return List of participating tservers
   */
  @Path("{" + TABLEID_PARAM_KEY + "}")
  @GET
  public TabletServers
      getParticipatingTabletServers(@PathParam(TABLEID_PARAM_KEY) @NotNull @Pattern(
          regexp = ALPHA_NUM_REGEX_TABLE_ID) String tableIdStr) {
    TableId tableId = TableId.of(tableIdStr);
    ManagerMonitorInfo mmi = monitor.getMmi();
    // fail fast if unable to get monitor info
    if (mmi == null) {
      return new TabletServers();
    }

    TabletServers tabletServers = new TabletServers(mmi.tServerInfo.size());

    if (tableIdStr.isBlank()) {
      return tabletServers;
    }

    TreeSet<String> locs = new TreeSet<>();
    if (SystemTables.ROOT.tableId().equals(tableId)) {
      var rootLoc = monitor.getContext().getAmple().readTablet(RootTable.EXTENT).getLocation();
      if (rootLoc != null && rootLoc.getType() == TabletMetadata.LocationType.CURRENT) {
        locs.add(rootLoc.getHostPort());
      }
    } else {
      var level = Ample.DataLevel.of(tableId);
      try (TabletsMetadata tablets = monitor.getContext().getAmple().readTablets().forLevel(level)
          .filter(new HasCurrentFilter()).build()) {

        for (TabletMetadata tm : tablets) {
          try {
            locs.add(tm.getLocation().getHostPort());
          } catch (Exception ex) {
            return tabletServers;
          }
        }
      }
    }

    List<TabletServerStatus> tservers = new ArrayList<>();
    for (TabletServerStatus tss : mmi.tServerInfo) {
      try {
        if (tss.name != null && locs.contains(tss.name)) {
          tservers.add(tss);
        }
      } catch (Exception ex) {
        return tabletServers;
      }
    }

    // Adds tservers to the list
    for (TabletServerStatus status : tservers) {
      if (status == null) {
        status = NO_STATUS;
      }
      TableInfo summary = status.tableMap.get(tableId.canonical());
      if (summary == null) {
        continue;
      }

      TabletServer tabletServerInfo = new TabletServer();
      tabletServerInfo.server.updateTabletServerInfo(monitor, status, summary);

      tabletServers.addTablet(tabletServerInfo);
    }

    return tabletServers;
  }

}
