package net.snowflake.spark.snowflake.io

import java.io.InputStream
import java.util.zip.GZIPInputStream

import net.snowflake.client.jdbc._
import net.snowflake.spark.snowflake.ConnectorSFStageManager._
import net.snowflake.spark.snowflake.{DefaultJDBCWrapper, JDBCWrapper, SnowflakeConnectorUtils, SnowflakeRecordReader}
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.sql.SQLContext
import org.slf4j.LoggerFactory


private[snowflake] class S3InternalPartition(
    val srcFiles: Seq[(java.lang.String, java.lang.String, java.lang.String)],
    val rddId: Int,
    val index: Int)
    extends Partition {

  override def hashCode(): Int = 31 * (31 + rddId) + index

  override def equals(other: Any): Boolean = super.equals(other)
}

private[snowflake] class S3InternalRDD(
                                        @transient val sqlContext: SQLContext,
                                        @transient val params: MergedParameters,
                                        @transient val sql: String,
                                        @transient val jdbcWrapper: JDBCWrapper = DefaultJDBCWrapper
) extends RDD[String](sqlContext.sparkContext, Nil) with DataUnloader {

  @transient override val log = LoggerFactory.getLogger(getClass)
  @transient private val stageManager =
    new S3Internal(false, jdbcWrapper, params)

  @transient val tempStage = stageManager.setupStageArea()

  @transient private val FILES_PER_PARTITION = 2

  private val compress = if (params.sfCompress) "gzip" else "none"
  private val parallel = params.parallelism

  private[snowflake] val rowCount = stageManager
    .executeWithConnection({ c =>
      setup(preStatements = Seq.empty,
            sql = buildUnloadStmt(sql, s"@$tempStage", compress, None),
            conn = c,
            keepOpen = true)
    })
    .asInstanceOf[Long]

  private val stageLocation = stageManager.stageLocation
  private val awsID         = stageManager.awsId
  private val awsKey        = stageManager.awsKey
  private val awsToken      = stageManager.awsToken
  private val masterKey     = stageManager.masterKey
  private val is256         = stageManager.is256Encryption

  override def getPartitions: Array[Partition] = {

    val encryptionMaterialsGrouped =
      stageManager.getKeyIds.grouped(FILES_PER_PARTITION).toList

    val partitions = new Array[Partition](encryptionMaterialsGrouped.length)

    var i = 0

    while (i < encryptionMaterialsGrouped.length) {
      partitions(i) =
        new S3InternalPartition(encryptionMaterialsGrouped(i), id, i)
      i = i + 1
    }
    partitions
  }

  override def compute(thePartition: Partition,
                       context: TaskContext): Iterator[String] = {

    //val converter = Conversions.createRowConverter[T](resultSchema)

    val mats   = thePartition.asInstanceOf[S3InternalPartition].srcFiles
    //val reader = new SnowflakeRecordReader

    val stringIterator = new StringIterator()

    try {
      mats.foreach {
        case (file, queryId, smkId) =>
          if (queryId != null) {
            var stream: InputStream = null

            val amazonClient =
              createS3Client(is256,
                             masterKey,
                             queryId,
                             smkId,
                             awsID,
                             awsKey,
                             awsToken,
                             parallel)

            val (bucketName, stagePath) =
              extractBucketNameAndPath(stageLocation)

            var stageFilePath = file

            if (!stagePath.isEmpty) {
              stageFilePath =
                SnowflakeUtil.concatFilePathNames(stagePath, file, "/")
            }

            val dataObject = amazonClient.getObject(bucketName, stageFilePath)
            stream = dataObject.getObjectContent

            if (!is256) {
              stream = getDecryptedStream(stream,
                                          masterKey,
                                          dataObject.getObjectMetadata)
            }

            stream = compress match {
              case "gzip" => new GZIPInputStream(stream)
              case _      => stream
            }

            //reader.addStream(stream)
            stringIterator.addStream(stream)
          }
      }
    } catch {
      case ex: Exception =>
        //reader.closeAll()
        stringIterator.closeAll()
        SnowflakeConnectorUtils.handleS3Exception(ex)
    }
    new InterruptibleIterator[String](context, stringIterator)

//    new InterruptibleIterator(context, new Iterator[String] {
//
//      private var finished = false
//      private var havePair = false
//
//      override def hasNext: Boolean = {
//        if (!finished && !havePair) {
//          try {
//            finished = !reader.nextKeyValue
//          } catch {
//            case e: IOException =>
//              finished = true
//          }
//
//          havePair = !finished
//        }
//        !finished
//      }
//
//      override def next(): String = {
//        if (!hasNext) {
//          throw new java.util.NoSuchElementException("End of stream")
//        }
//        havePair = false
//
//        converter(reader.getCurrentValue)
//      }
//    })
  }

  override def finalize(): Unit = {
    stageManager.closeConnection()
    super.finalize()
  }
}
