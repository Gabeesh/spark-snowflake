/*
 * Copyright 2015-2016 Snowflake Computing
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.snowflake.spark.snowflake

import java.net.URI

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql._
import org.slf4j.LoggerFactory
import net.snowflake.spark.snowflake.Parameters.MergedParameters

import scala.reflect.ClassTag

/** Data Source API implementation for Amazon Snowflake database tables */
private[snowflake] case class SnowflakeRelation(
    jdbcWrapper: JDBCWrapper,
    s3ClientFactory: AWSCredentials => AmazonS3Client,
    params: MergedParameters,
    userSchema: Option[StructType])(@transient val sqlContext: SQLContext)
    extends BaseRelation
    with PrunedFilteredScan
    with InsertableRelation
    with DataUnloader {
  import SnowflakeRelation._

  override def toString: String = {
    "SnowflakeRelation"
  }

  override val log = LoggerFactory.getLogger(getClass) // Create a temporary stage

  private lazy val creds = CloudCredentialsUtils
    .load(params.rootTempDir, sqlContext.sparkContext.hadoopConfiguration)

  if (sqlContext != null && params.usingExternalStage) {
    Utils.checkFileSystem(
      new URI(params.rootTempDir),
      sqlContext.sparkContext.hadoopConfiguration)
  }

  override lazy val schema: StructType = {
    userSchema.getOrElse {
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      val conn = jdbcWrapper.getConnector(params)
      try {
        jdbcWrapper.resolveTable(conn, tableNameOrSubquery)
      } finally {
        conn.close()
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val saveMode = if (overwrite) {
      SaveMode.Overwrite
    } else {
      SaveMode.Append
    }
    val writer = new SnowflakeWriter(jdbcWrapper, s3ClientFactory)
    writer.saveToSnowflake(sqlContext, data, saveMode, params)
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = {
    filters.filterNot(filter =>
      FilterPushdown.buildFilterExpression(schema, filter).isDefined)
  }

  // Build the RDD from a query string, generated by SnowflakeStrategy. Type can be InternalRow to comply with
  // SparkPlan's doExecute().
  def buildScanFromSQL[T: ClassTag](sql: String,
                                    schema: Option[StructType]): RDD[T] = {
    if (params.checkBucketConfiguration && params.usingExternalStage) {
      Utils.checkThatBucketHasObjectLifecycleConfiguration(
        params.rootTempDir,
        params.rootTempDirStorageType,
        s3ClientFactory(creds))
    }

    log.debug(Utils.sanitizeQueryText(sql))

    val conn = jdbcWrapper.getConnector(params)
    val resultSchema = schema.getOrElse(try {
      jdbcWrapper.resolveTable(conn, sql)
    } finally {
      conn.close()
    })

    getRDDFromS3[T](sql, resultSchema)
  }

  // Build RDD result from PrunedFilteredScan interface. Maintain this here for backwards compatibility and for
  // when extra pushdowns are disabled.
  override def buildScan(requiredColumns: Array[String],
                         filters: Array[Filter]): RDD[Row] = {
    if (params.checkBucketConfiguration && params.usingExternalStage) {
      Utils.checkThatBucketHasObjectLifecycleConfiguration(
        params.rootTempDir,
        params.rootTempDirStorageType,
        s3ClientFactory(creds))
    }
    if (requiredColumns.isEmpty) {
      // In the special case where no columns were requested, issue a `count(*)` against Snowflake
      // rather than unloading data.
      val whereClause = FilterPushdown.buildWhereClause(schema, filters)
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table).get
      val countQuery =
        s"SELECT count(*) FROM $tableNameOrSubquery $whereClause"
      log.debug(Utils.sanitizeQueryText(countQuery))
      val conn = jdbcWrapper.getConnector(params)
      try {
        val results = jdbcWrapper.executeQueryInterruptibly(conn, countQuery)
        if (results.next()) {
          val numRows = results.getLong(1)
          val parallelism =
            sqlContext.getConf("spark.sql.shuffle.partitions", "200").toInt
          val emptyRow = Row.empty
          sqlContext.sparkContext
            .parallelize(1L to numRows, parallelism)
            .map(_ => emptyRow)
        } else {
          throw new IllegalStateException(
            "Could not read count from Snowflake")
        }
      } finally {
        conn.close()
      }
    } else {
      // Unload data from Snowflake into a temporary directory in S3:
      //  val tempDir = params.createPerQueryTempDir()
      // val unloadSql =
      //     buildUnloadStmt(standardQuery(requiredColumns, filters), tempDir)
      val prunedSchema = pruneSchema(schema, requiredColumns)

      getRDDFromS3[Row](standardQuery(requiredColumns, filters), prunedSchema)
    }
  }

  // Get an RDD from an unload statement. Provide result schema because
  // when a custom SQL statement is used, this means that we cannot know the results
  // without first executing it.
  private def getRDDFromS3[T: ClassTag](sql: String,
                                        resultSchema: StructType): RDD[T] = {

    val rdd: RDD[String] = io.readRDD(sqlContext,params,sql)


    rdd.collect().foreach(println)

    println("XXXXXXXXXXXXXXXXXXX")

    sqlContext.sparkContext.emptyRDD[T]

//    if (params.usingExternalStage) {
//      val tempDir = params.createPerQueryTempDir()
//
//      val numRows = setup(
//        sql = buildUnloadStmt(
//          query = sql,
//          location = Utils.fixUrlForCopyCommand(tempDir),
//          compression = if (params.sfCompress) "gzip" else "none",
//          credentialsString = Some(
//            CloudCredentialsUtils.getSnowflakeCredentialsString(sqlContext,
//                                                              params))),
//
//        conn = jdbcWrapper.getConnector(params))
//
//      if (numRows == 0) {
//        // For no records, create an empty RDD
//        sqlContext.sparkContext.emptyRDD[T]
//      } else {
//        val rdd = sqlContext.sparkContext.newAPIHadoopFile(
//          tempDir,
//          classOf[SnowflakeInputFormat],
//          classOf[java.lang.Long],
//          classOf[Array[String]])
//        rdd.values.mapPartitions { iter =>
//          val converter: Array[String] => T =
//            Conversions.createRowConverter[T](resultSchema)
//          iter.map(converter)
//        }
//      }
//    } else {
//      val sfRDD =
//        new SnowflakeRDD[T](sqlContext, jdbcWrapper, params, sql, resultSchema)
//
//      if (sfRDD.rowCount == 0) sqlContext.sparkContext.emptyRDD[T]
//      else sfRDD
//    }
  }

  // Build a query out of required columns and filters. (Used by buildScan)
  private def standardQuery(requiredColumns: Array[String],
                            filters: Array[Filter]): String = {
    assert(!requiredColumns.isEmpty)
    // Always quote column names, and uppercase-cast them to make them equivalent to being unquoted
    // (unless already quoted):
    val columnList = requiredColumns
      .map(col => if (isQuoted(col)) col else "\"" + col.toUpperCase + "\"")
      .mkString(", ")
    val whereClause = FilterPushdown.buildWhereClause(schema, filters)
    val tableNameOrSubquery =
      params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
    s"SELECT $columnList FROM $tableNameOrSubquery $whereClause"
  }
}

private[snowflake] object SnowflakeRelation {

  private def pruneSchema(schema: StructType,
                          columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }

  private def isQuoted(name: String): Boolean = {
    name.startsWith("\"") && name.endsWith("\"")
  }
}
