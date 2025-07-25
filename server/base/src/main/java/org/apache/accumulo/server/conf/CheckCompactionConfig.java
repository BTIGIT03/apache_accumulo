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
package org.apache.accumulo.server.conf;

import static org.apache.accumulo.core.Constants.DEFAULT_COMPACTION_SERVICE_NAME;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.cli.Help;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.spi.common.ServiceEnvironment;
import org.apache.accumulo.core.spi.compaction.CompactionPlanner;
import org.apache.accumulo.core.spi.compaction.CompactionServiceId;
import org.apache.accumulo.core.util.ConfigurationImpl;
import org.apache.accumulo.core.util.compaction.CompactionPlannerInitParams;
import org.apache.accumulo.core.util.compaction.CompactionServicesConfig;
import org.apache.accumulo.start.spi.KeywordExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.beust.jcommander.Parameter;
import com.google.auto.service.AutoService;

/**
 * A command line tool that verifies that a given properties file will correctly configure
 * compaction services.
 *
 * This tool takes, as input, a local path to a properties file containing the properties used to
 * configure compaction services. The file is parsed and the user is presented with output detailing
 * which (if any) compaction services would be created from the given properties, or an error
 * describing why the given properties are incorrect.
 */
@AutoService(KeywordExecutable.class)
public class CheckCompactionConfig implements KeywordExecutable {

  private final static Logger log = LoggerFactory.getLogger(CheckCompactionConfig.class);

  static class Opts extends Help {
    @Parameter(description = "<path> Local path to file containing compaction configuration",
        required = true)
    String filePath;
  }

  @Override
  public String keyword() {
    return "check-compaction-config";
  }

  @Override
  public String description() {
    return "Verifies compaction config within a given file";
  }

  public static void main(String[] args) throws Exception {
    new CheckCompactionConfig().execute(args);
  }

  @Override
  public void execute(String[] args) throws Exception {
    Opts opts = new Opts();
    opts.parseArgs(keyword(), args);

    if (opts.filePath == null) {
      throw new IllegalArgumentException("No properties file was given");
    }

    Path path = Path.of(opts.filePath);
    if (Files.notExists(path)) {
      throw new FileNotFoundException("File at given path could not be found");
    }

    AccumuloConfiguration config = SiteConfiguration.fromFile(path.toFile()).build();
    validate(config, Level.INFO);
  }

  public static void validate(AccumuloConfiguration config, Level level)
      throws ReflectiveOperationException, SecurityException, IllegalArgumentException {
    var servicesConfig = new CompactionServicesConfig(config);
    ServiceEnvironment senv = createServiceEnvironment(config);

    Set<String> defaultService = Set.of(DEFAULT_COMPACTION_SERVICE_NAME);
    if (servicesConfig.getPlanners().keySet().equals(defaultService)) {
      log.atLevel(level).log("Only the default compaction service was created - {}",
          defaultService);
      return;
    }

    Map<ResourceGroupId,Set<String>> groupToServices = new HashMap<>();
    for (var entry : servicesConfig.getPlanners().entrySet()) {
      String serviceId = entry.getKey();
      String plannerClassName = entry.getValue();
      log.atLevel(level).log("Service id: {}, planner class:{}", serviceId, plannerClassName);

      Class<? extends CompactionPlanner> plannerClass =
          Class.forName(plannerClassName).asSubclass(CompactionPlanner.class);
      CompactionPlanner planner = plannerClass.getDeclaredConstructor().newInstance();

      var initParams = new CompactionPlannerInitParams(CompactionServiceId.of(serviceId),
          servicesConfig.getPlannerPrefix(serviceId), servicesConfig.getOptions().get(serviceId),
          senv);

      planner.init(initParams);

      initParams.getRequestedGroups().forEach(groupId -> {
        log.atLevel(level).log("Compaction service '{}' requested with compactor group '{}'",
            serviceId, groupId);
        groupToServices.computeIfAbsent(groupId, f -> new HashSet<>()).add(serviceId);
      });
    }

    boolean dupesFound = false;
    for (Entry<ResourceGroupId,Set<String>> e : groupToServices.entrySet()) {
      if (e.getValue().size() > 1) {
        log.warn("Compaction services " + e.getValue().toString()
            + " mapped to the same compactor group: " + e.getKey());
        dupesFound = true;
      }
    }

    if (dupesFound) {
      throw new IllegalStateException(
          "Multiple compaction services configured to use the same group. This could lead"
              + " to undesired behavior. Please fix the configuration");
    }

    log.atLevel(level).log("Properties file has passed all checks.");

  }

  private static ServiceEnvironment createServiceEnvironment(AccumuloConfiguration config) {
    return new ServiceEnvironment() {

      @Override
      public <T> T instantiate(TableId tableId, String className, Class<T> base) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> T instantiate(String className, Class<T> base) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getTableName(TableId tableId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Configuration getConfiguration(TableId tableId) {
        return new ConfigurationImpl(config);
      }

      @Override
      public Configuration getConfiguration() {
        return new ConfigurationImpl(config);
      }
    };
  }

}
