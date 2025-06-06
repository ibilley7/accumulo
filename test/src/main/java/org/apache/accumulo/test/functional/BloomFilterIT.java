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

import static org.apache.accumulo.core.util.LazySingletons.RANDOM;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.keyfunctor.ColumnFamilyFunctor;
import org.apache.accumulo.core.file.keyfunctor.ColumnQualifierFunctor;
import org.apache.accumulo.core.file.keyfunctor.RowFunctor;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.accumulo.minicluster.MemoryUnit;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BloomFilterIT extends AccumuloClusterHarness {
  private static final Logger log = LoggerFactory.getLogger(BloomFilterIT.class);

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(6);
  }

  @Override
  public void configureMiniCluster(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setDefaultMemory(1, MemoryUnit.GIGABYTE);
    cfg.getClusterServerConfiguration().setNumDefaultTabletServers(1);
    Map<String,String> siteConfig = cfg.getSiteConfig();
    siteConfig.put(Property.TSERV_TOTAL_MUTATION_QUEUE_MAX.getKey(), "10M");
    cfg.setSiteConfig(siteConfig);
  }

  @Test
  public void test() throws Exception {
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      final String readAhead = c.instanceOperations().getSystemConfiguration()
          .get(Property.TSERV_SCAN_EXECUTORS_DEFAULT_THREADS.getKey());
      c.instanceOperations().setProperty(Property.TSERV_SCAN_EXECUTORS_DEFAULT_THREADS.getKey(),
          "1");
      try {
        Thread.sleep(1000);
        TableOperations tops = c.tableOperations();

        Map<String,String> props = new HashMap<>();
        props.put(Property.TABLE_INDEXCACHE_ENABLED.getKey(), "false");
        props.put(Property.TABLE_BLOCKCACHE_ENABLED.getKey(), "false");
        props.put(Property.TABLE_BLOOM_SIZE.getKey(), "2000000");
        props.put(Property.TABLE_BLOOM_ERRORRATE.getKey(), "1%");
        props.put(Property.TABLE_BLOOM_LOAD_THRESHOLD.getKey(), "0");
        props.put(Property.TABLE_FILE_COMPRESSED_BLOCK_SIZE.getKey(), "64K");
        NewTableConfiguration ntc = new NewTableConfiguration();
        ntc.setProperties(props);

        final String[] tables = getUniqueNames(4);
        for (String table : tables) {
          tops.create(table, ntc);
        }
        log.info("Writing");
        write(c, tables[0], 1, 0, 2000000000, 500);
        write(c, tables[1], 2, 0, 2000000000, 500);
        write(c, tables[2], 3, 0, 2000000000, 500);
        log.info("Writing complete");

        // test inserting an empty key
        try (BatchWriter bw = c.createBatchWriter(tables[3])) {
          Mutation m = new Mutation(new Text(""));
          m.put("", "", "foo1");
          bw.addMutation(m);
        }
        c.tableOperations().flush(tables[3], null, null, true);

        for (String table : Arrays.asList(tables[0], tables[1], tables[2])) {
          c.tableOperations().compact(table, null, null, true, true);
        }

        // ensure compactions are finished
        for (String table : tables) {
          FunctionalTestUtils.checkRFiles(c, table, 1, 1, 1, 1);
        }

        // these queries should only run quickly if bloom filters are working, so lets get a base
        log.info("Base query");
        long t1 = query(c, tables[0], 1, 0, 2000000000, 5000, 500);
        long t2 = query(c, tables[1], 2, 0, 2000000000, 5000, 500);
        long t3 = query(c, tables[2], 3, 0, 2000000000, 5000, 500);
        log.info("Base query complete");

        log.info("Rewriting with bloom filters");
        c.tableOperations().setProperty(tables[0], Property.TABLE_BLOOM_ENABLED.getKey(), "true");
        c.tableOperations().setProperty(tables[0], Property.TABLE_BLOOM_KEY_FUNCTOR.getKey(),
            RowFunctor.class.getName());

        c.tableOperations().setProperty(tables[1], Property.TABLE_BLOOM_ENABLED.getKey(), "true");
        c.tableOperations().setProperty(tables[1], Property.TABLE_BLOOM_KEY_FUNCTOR.getKey(),
            ColumnFamilyFunctor.class.getName());

        c.tableOperations().setProperty(tables[2], Property.TABLE_BLOOM_ENABLED.getKey(), "true");
        c.tableOperations().setProperty(tables[2], Property.TABLE_BLOOM_KEY_FUNCTOR.getKey(),
            ColumnQualifierFunctor.class.getName());

        c.tableOperations().setProperty(tables[3], Property.TABLE_BLOOM_ENABLED.getKey(), "true");
        c.tableOperations().setProperty(tables[3], Property.TABLE_BLOOM_KEY_FUNCTOR.getKey(),
            RowFunctor.class.getName());

        // allow the updates to zookeeper propagate
        Thread.sleep(500);

        c.tableOperations().compact(tables[3], null, null, false, true);
        c.tableOperations().compact(tables[0], null, null, false, true);
        c.tableOperations().compact(tables[1], null, null, false, true);
        c.tableOperations().compact(tables[2], null, null, false, true);
        log.info("Rewriting with bloom filters complete");

        // these queries should only run quickly if bloom
        // filters are working
        log.info("Bloom query");
        long tb1 = query(c, tables[0], 1, 0, 2000000000, 5000, 500);
        long tb2 = query(c, tables[1], 2, 0, 2000000000, 5000, 500);
        long tb3 = query(c, tables[2], 3, 0, 2000000000, 5000, 500);
        log.info("Bloom query complete");
        timeCheck(t1 + t2 + t3, tb1 + tb2 + tb3);

        // test querying for empty key
        try (Scanner scanner = c.createScanner(tables[3], Authorizations.EMPTY)) {
          scanner.setRange(new Range(new Text("")));

          if (!scanner.iterator().next().getValue().toString().equals("foo1")) {
            throw new Exception("Did not see foo1");
          }
        }
      } finally {
        c.instanceOperations().setProperty(Property.TSERV_SCAN_EXECUTORS_DEFAULT_THREADS.getKey(),
            readAhead);
      }
    }
  }

  private void timeCheck(long t1, long t2) throws Exception {
    double improvement = (t1 - t2) * 1.0 / t1;
    if (improvement < .1) {
      throw new Exception("Queries had less than 10% improvement (old: " + t1 + " new: " + t2
          + " improvement: " + (improvement * 100) + "%)");
    }
    log.info(String.format("Improvement: %.2f%% (%d vs %d)", (improvement * 100), t1, t2));
  }

  private long query(AccumuloClient c, String table, int depth, long start, long end, int num,
      int step) throws Exception {

    HashSet<Long> expected = new HashSet<>();
    List<Range> ranges = new ArrayList<>(num);
    Text key = new Text();
    Text row = new Text("row");
    Text cq = new Text("cq");
    Text cf = new Text("cf");

    for (int i = 0; i < num; ++i) {
      Long k = ((RANDOM.get().nextLong() & 0x7fffffffffffffffL) % (end - start)) + start;
      key.set(String.format("k_%010d", k));
      Range range = null;
      Key acuKey;

      if (k % (start + step) == 0) {
        expected.add(k);
      }

      switch (depth) {
        case 1:
          range = new Range(new Text(key));
          break;
        case 2:
          acuKey = new Key(row, key, cq);
          range = new Range(acuKey, true, acuKey.followingKey(PartialKey.ROW_COLFAM), false);
          break;
        case 3:
          acuKey = new Key(row, cf, key);
          range =
              new Range(acuKey, true, acuKey.followingKey(PartialKey.ROW_COLFAM_COLQUAL), false);
          break;
      }

      ranges.add(range);
    }

    try (BatchScanner bs = c.createBatchScanner(table)) {
      bs.setRanges(ranges);

      long t1 = System.currentTimeMillis();
      for (Entry<Key,Value> entry : bs) {
        long v = Long.parseLong(entry.getValue().toString());
        if (!expected.remove(v)) {
          throw new Exception("Got unexpected return " + entry.getKey() + " " + entry.getValue());
        }
      }
      long t2 = System.currentTimeMillis();

      if (!expected.isEmpty()) {
        throw new Exception("Did not get all expected values " + expected.size());
      }

      return t2 - t1;
    }
  }

  private void write(AccumuloClient c, String table, int depth, long start, long end, int step)
      throws Exception {

    try (BatchWriter bw = c.createBatchWriter(table)) {
      for (long i = start; i < end; i += step) {
        String key = String.format("k_%010d", i);
        Mutation m = null;
        switch (depth) {
          case 1:
            m = new Mutation(new Text(key));
            m.put("cf", "cq", "" + i);
            break;
          case 2:
            m = new Mutation(new Text("row"));
            m.put(key, "cq", "" + i);
            break;
          case 3:
            m = new Mutation(new Text("row"));
            m.put("cf", key, "" + i);
            break;
        }
        bw.addMutation(m);
      }
    }

    c.tableOperations().flush(table, null, null, true);
  }
}
