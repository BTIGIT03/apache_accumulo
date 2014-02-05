/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test.randomwalk.bulk;

import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.test.randomwalk.Environment;
import org.apache.accumulo.test.randomwalk.State;
import org.apache.hadoop.io.Text;

public class Split extends BulkTest {
  
  @Override
  protected void runLater(State state, Environment env) throws Exception {
    SortedSet<Text> splits = new TreeSet<Text>();
    Random rand = (Random) state.get("rand");
    int count = rand.nextInt(20);
    for (int i = 0; i < count; i++)
      splits.add(new Text(String.format(BulkPlusOne.FMT, (rand.nextLong() & 0x7fffffffffffffffl) % BulkPlusOne.LOTS)));
    log.info("splitting " + splits);
    env.getConnector().tableOperations().addSplits(Setup.getTableName(), splits);
    log.info("split for " + splits + " finished");
  }
  
}
