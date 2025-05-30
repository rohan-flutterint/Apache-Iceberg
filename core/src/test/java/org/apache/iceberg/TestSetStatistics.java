/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestSetStatistics extends TestBase {

  @TestTemplate
  public void testEmptyUpdateStatistics() {
    assertThat(version()).isEqualTo(0);
    TableMetadata base = readMetadata();

    table.updateStatistics().commit();

    assertThat(table.ops().current()).isSameAs(base);
    assertThat(version()).isEqualTo(1);
  }

  @TestTemplate
  public void testEmptyTransactionalUpdateStatistics() {
    assertThat(version()).isEqualTo(0);
    TableMetadata base = readMetadata();

    Transaction transaction = table.newTransaction();
    transaction.updateStatistics().commit();
    transaction.commitTransaction();

    assertThat(table.ops().current()).isSameAs(base);
    assertThat(version()).isEqualTo(0);
  }

  @TestTemplate
  public void testUpdateStatistics() {
    // Create a snapshot
    table.newFastAppend().commit();
    assertThat(version()).isEqualTo(1);

    TableMetadata base = readMetadata();
    long snapshotId = base.currentSnapshot().snapshotId();
    GenericStatisticsFile statisticsFile =
        new GenericStatisticsFile(
            snapshotId,
            "/some/statistics/file.puffin",
            100,
            42,
            ImmutableList.of(
                new GenericBlobMetadata(
                    "stats-type",
                    snapshotId,
                    base.lastSequenceNumber(),
                    ImmutableList.of(1, 2),
                    ImmutableMap.of("a-property", "some-property-value"))));

    table.updateStatistics().setStatistics(statisticsFile).commit();

    TableMetadata metadata = readMetadata();
    assertThat(version()).isEqualTo(2);
    assertThat(metadata.currentSnapshot().snapshotId()).isEqualTo(snapshotId);
    assertThat(metadata.statisticsFiles()).containsExactly(statisticsFile);
  }

  @TestTemplate
  public void testRemoveStatistics() {
    // Create a snapshot
    table.newFastAppend().commit();
    assertThat(version()).isEqualTo(1);

    TableMetadata base = readMetadata();
    long snapshotId = base.currentSnapshot().snapshotId();
    GenericStatisticsFile statisticsFile =
        new GenericStatisticsFile(
            snapshotId, "/some/statistics/file.puffin", 100, 42, ImmutableList.of());

    table.updateStatistics().setStatistics(statisticsFile).commit();

    TableMetadata metadata = readMetadata();
    assertThat(version()).isEqualTo(2);
    assertThat(metadata.statisticsFiles()).containsExactly(statisticsFile);

    table.updateStatistics().removeStatistics(snapshotId).commit();

    metadata = readMetadata();
    assertThat(version()).isEqualTo(3);
    assertThat(metadata.statisticsFiles()).isEmpty();
  }
}
