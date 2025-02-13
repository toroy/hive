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

package org.apache.iceberg.mr.hive;

import java.util.List;
import java.util.Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.parse.AlterTableExecuteSpec;
import org.apache.hadoop.hive.ql.parse.TransformSpec;
import org.apache.hadoop.hive.ql.session.SessionStateUtil;
import org.apache.iceberg.ManageSnapshots;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdatePartitionSpec;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.mr.Catalogs;
import org.apache.iceberg.mr.InputFormatConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergTableUtil {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergTableUtil.class);

  private IcebergTableUtil() {

  }

  /**
   * Constructs the table properties needed for the Iceberg table loading by retrieving the information from the
   * hmsTable. It then calls {@link IcebergTableUtil#getTable(Configuration, Properties)} with these properties.
   * @param configuration a Hadoop configuration
   * @param hmsTable the HMS table
   * @return the Iceberg table
   */
  static Table getTable(Configuration configuration, org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    Properties properties = new Properties();
    properties.setProperty(Catalogs.NAME, TableIdentifier.of(hmsTable.getDbName(), hmsTable.getTableName()).toString());
    properties.setProperty(Catalogs.LOCATION, hmsTable.getSd().getLocation());
    hmsTable.getParameters().computeIfPresent(InputFormatConfig.CATALOG_NAME,
        (k, v) -> {
          properties.setProperty(k, v);
          return v;
        });
    return getTable(configuration, properties);
  }

  /**
   * Load the iceberg table either from the {@link QueryState} or through the configured catalog. Look for the table
   * object stored in the query state. If it's null, it means the table was not loaded yet within the same query
   * therefore we claim it through the Catalogs API and then store it in query state.
   * @param configuration a Hadoop configuration
   * @param properties controlling properties
   * @return an Iceberg table
   */
  static Table getTable(Configuration configuration, Properties properties) {
    String metaTable = properties.getProperty("metaTable");
    String tableName = properties.getProperty(Catalogs.NAME);
    String location = properties.getProperty(Catalogs.LOCATION);
    if (metaTable != null) {
      // HiveCatalog, HadoopCatalog uses NAME to identify the metadata table
      properties.setProperty(Catalogs.NAME, tableName + "." + metaTable);
      // HadoopTable uses LOCATION to identify the metadata table
      properties.setProperty(Catalogs.LOCATION, location + "#" + metaTable);
    }

    String tableIdentifier = properties.getProperty(Catalogs.NAME);
    return SessionStateUtil.getResource(configuration, tableIdentifier).filter(o -> o instanceof Table)
        .map(o -> (Table) o).orElseGet(() -> {
          LOG.debug("Iceberg table {} is not found in QueryState. Loading table from configured catalog",
              tableIdentifier);
          Table tab = Catalogs.loadTable(configuration, properties);
          SessionStateUtil.addResource(configuration, tableIdentifier, tab);
          return tab;
        });
  }

  /**
   * Create {@link PartitionSpec} based on the partition information stored in
   * {@link TransformSpec}.
   * @param configuration a Hadoop configuration
   * @param schema iceberg table schema
   * @return iceberg partition spec, always non-null
   */
  public static PartitionSpec spec(Configuration configuration, Schema schema) {
    List<TransformSpec> partitionTransformSpecList = SessionStateUtil
            .getResource(configuration, hive_metastoreConstants.PARTITION_TRANSFORM_SPEC)
        .map(o -> (List<TransformSpec>) o).orElseGet(() -> null);

    if (partitionTransformSpecList == null) {
      LOG.debug("Iceberg partition transform spec is not found in QueryState.");
      return null;
    }

    PartitionSpec.Builder builder = PartitionSpec.builderFor(schema);
    partitionTransformSpecList.forEach(spec -> {
      switch (spec.getTransformType()) {
        case IDENTITY:
          builder.identity(spec.getColumnName());
          break;
        case YEAR:
          builder.year(spec.getColumnName());
          break;
        case MONTH:
          builder.month(spec.getColumnName());
          break;
        case DAY:
          builder.day(spec.getColumnName());
          break;
        case HOUR:
          builder.hour(spec.getColumnName());
          break;
        case TRUNCATE:
          builder.truncate(spec.getColumnName(), spec.getTransformParam().get());
          break;
        case BUCKET:
          builder.bucket(spec.getColumnName(), spec.getTransformParam().get());
          break;
      }
    });
    return builder.build();
  }

  public static void updateSpec(Configuration configuration, Table table) {
    // get the new partition transform spec
    PartitionSpec newPartitionSpec = spec(configuration, table.schema());
    if (newPartitionSpec == null) {
      LOG.debug("Iceberg Partition spec is not updated due to empty partition spec definition.");
      return;
    }

    // delete every field from the old partition spec
    UpdatePartitionSpec updatePartitionSpec = table.updateSpec().caseSensitive(false);
    table.spec().fields().forEach(field -> updatePartitionSpec.removeField(field.name()));

    List<TransformSpec> partitionTransformSpecList = SessionStateUtil
        .getResource(configuration, hive_metastoreConstants.PARTITION_TRANSFORM_SPEC)
        .map(o -> (List<TransformSpec>) o).orElseGet(() -> null);

    partitionTransformSpecList.forEach(spec -> {
      switch (spec.getTransformType()) {
        case IDENTITY:
          updatePartitionSpec.addField(spec.getColumnName());
          break;
        case YEAR:
          updatePartitionSpec.addField(Expressions.year(spec.getColumnName()));
          break;
        case MONTH:
          updatePartitionSpec.addField(Expressions.month(spec.getColumnName()));
          break;
        case DAY:
          updatePartitionSpec.addField(Expressions.day(spec.getColumnName()));
          break;
        case HOUR:
          updatePartitionSpec.addField(Expressions.hour(spec.getColumnName()));
          break;
        case TRUNCATE:
          updatePartitionSpec.addField(Expressions.truncate(spec.getColumnName(), spec.getTransformParam().get()));
          break;
        case BUCKET:
          updatePartitionSpec.addField(Expressions.bucket(spec.getColumnName(), spec.getTransformParam().get()));
          break;
      }
    });

    updatePartitionSpec.commit();
  }

  public static boolean isBucketed(Table table) {
    return table.spec().fields().stream().anyMatch(f -> f.transform().toString().startsWith("bucket["));
  }

  /**
   * Roll an iceberg table's data back to a specific snapshot identified either by id or before a given timestamp.
   * @param table the iceberg table
   * @param type the type of the rollback, can be either time based or version based
   * @param value parameter of the rollback, that can be a timestamp in millis or a snapshot id
   */
  public static void rollback(Table table, AlterTableExecuteSpec.RollbackSpec.RollbackType type, Long value) {
    ManageSnapshots manageSnapshots = table.manageSnapshots();
    if (type == AlterTableExecuteSpec.RollbackSpec.RollbackType.TIME) {
      LOG.debug("Trying to rollback iceberg table to snapshot before timestamp {}", value);
      manageSnapshots.rollbackToTime(value);
    } else {
      LOG.debug("Trying to rollback iceberg table to snapshot ID {}", value);
      manageSnapshots.rollbackTo(value);
    }
    manageSnapshots.commit();
  }
}
