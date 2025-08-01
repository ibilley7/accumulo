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
package org.apache.accumulo.test.fate.user;

import static org.apache.accumulo.core.fate.user.UserFateStore.getRowId;
import static org.apache.accumulo.test.fate.FateTestUtil.createFateTable;
import static org.apache.accumulo.test.fate.TestLock.createDummyLockID;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.fate.AbstractFateStore.FateIdGenerator;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateStore;
import org.apache.accumulo.core.fate.user.UserFateStore;
import org.apache.accumulo.core.fate.user.schema.FateSchema.TxColumnFamily;
import org.apache.accumulo.harness.SharedMiniClusterBase;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.test.fate.FateStoreITBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public class UserFateStoreFateIT_SimpleSuite extends FateStoreITBase {

  @BeforeAll
  public static void setup() throws Exception {
    SharedMiniClusterBase.startMiniCluster();
  }

  @AfterAll
  public static void teardown() {
    SharedMiniClusterBase.stopMiniCluster();
  }

  @Override
  public void executeTest(FateTestExecutor<TestEnv> testMethod, int maxDeferred,
      FateIdGenerator fateIdGenerator) throws Exception {
    String table = getUniqueNames(1)[0] + "fatestore";
    try (ClientContext client = (ClientContext) Accumulo.newClient().from(getClientProps()).build();
        FateStore<TestEnv> fs = new UserFateStore<>(client, table, createDummyLockID(), null,
            maxDeferred, fateIdGenerator)) {
      createFateTable(client, table);
      testMethod.execute(fs, getCluster().getServerContext());
    }
  }

  @Override
  protected void deleteKey(FateId fateId, ServerContext context) {
    String table = getUniqueNames(1)[0] + "fatestore";
    try (BatchWriter bw = context.createBatchWriter(table)) {
      Mutation mut = new Mutation(getRowId(fateId));
      TxColumnFamily.TX_KEY_COLUMN.putDelete(mut);
      bw.addMutation(mut);
    } catch (TableNotFoundException | MutationsRejectedException e) {
      throw new IllegalStateException(e);
    }
  }
}
