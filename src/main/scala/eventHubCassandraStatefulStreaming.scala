import config.Model.{EventRecord, EventState}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.apache.spark.eventhubs.{EventHubsConf, EventPosition}
import org.apache.spark.sql.functions.{col, explode, from_json, row_number, to_timestamp}
import org.apache.spark.sql.types.{ArrayType, MapType, StringType}
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, Row, SaveMode, SparkSession}
import org.apache.spark.sql.streaming.Trigger
import com.datastax.spark.connector.cql.{CassandraConnector, CassandraConnectorConf}
import com.datastax.oss.driver.api.core.cql.ResultSet
import org.apache.spark.SparkConf
import config.Configuration

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.concurrent.duration.DurationInt
import org.apache.log4j.Logger

object eventHubCassandraStatefulStreaming extends App {
  val connectionString = Configuration.connectionString
  val consumerGroup = Configuration.consumerGroup

  val datalakeCheckpoints = Configuration.datalakeCheckpoints
  val deltaBronzePath = Configuration.deltaBronzePath

  val logger = Logger.getLogger(getClass.getName)

  lazy val sparkConf: SparkConf = {
    val coreConf = new SparkConf()
      .setAppName(Configuration.appName)
    Configuration.Spark.settings.foreach(tuple =>
      coreConf.setIfMissing(tuple._1, tuple._2)
    )
    coreConf
  }

  lazy implicit val sparkSession: SparkSession = {
    SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()
  }

  val ehConf = EventHubsConf(connectionString)
    .setConsumerGroup(consumerGroup)
    .setMaxEventsPerTrigger(100)
    .setStartingPosition(EventPosition.fromEndOfStream)

  val inpStream: DataFrame = sparkSession.readStream
    .format("eventhubs")
    .options(ehConf.toMap)
    .load

  val parsedStream = inpStream
    .selectExpr("cast (body as string)")
    .select(explode(from_json(col("body"), ArrayType(StringType))).as("body"))
    .select(from_json(col("body"), MapType(StringType, StringType))).as("body")

  val filteredStream = parsedStream
    .filter(col("body.entries.event") !== "null")
    .select(
      col("body.entries.sentAt").as("sentAt"),
      col("body.entries.createdAt").as("createdAt"),
      col("body.entries.event").as("event"),
      col("body.entries.messageId").as("messageId"),
      col("body.entries.missingVersion").as("missingVersion"),
      col("body.entries.traits").as("traits"),
      col("body.entries.noField").as("noField"),
      col("body.entries.type").as("type")
    )

  import sparkSession.implicits._

  val connCreateObjects = CassandraConnector(sparkConf)

  val createKeyspace = "CREATE KEYSPACE IF NOT EXISTS events WITH REPLICATION = {'class': 'SimpleStrategy', 'replication_factor': 1 };"
  val createTableRecords = "CREATE TABLE IF NOT EXISTS events.records (\"messageId\" UUID PRIMARY KEY, event TEXT, \"noField\" INT, \"sentAt\" TEXT);"
  val createTableState = "CREATE TABLE IF NOT EXISTS events.state (event TEXT PRIMARY KEY, count BIGINT);"

  connCreateObjects.withSessionDo { session =>
    val checkKeyspace: ResultSet = session.execute(createKeyspace)
    val checkTableRecords: ResultSet = session.execute(createTableRecords)
    val checkTableState: ResultSet = session.execute(createTableState)
  }

  val eventRecordDF = filteredStream
    .select(
      to_timestamp(col("sentAt")).as("sentAt"),
      col("event"),
      col("messageId"),
      col("noField").cast("Int"),
    )
    .as[EventRecord]

  val saveStateDF = eventRecordDF
    .repartition(col("event"))
    .mapPartitions { iterator =>
      val stream = iterator.toStream
      val connector = CassandraConnector(sparkConf)
      connector.withSessionDo { session =>
        iterator.foreach { record => 
          val selectQuery =
            s"""select event, count from events.state
               |where event = '${record.event}';""".stripMargin
          val set: ResultSet = session.execute(selectQuery)
          val result = set
            .all()
            .asScala
            .toList
            .map(r => r.getLong(1))
          val count = if (result.length > 0) {
            result(0) + 1
          } else {
            1
          }
          if (record.event != null) {
            val insertQuery =
              s"""
                 |insert into events.state("event", "count")
                 |values ('${record.event}', ${count}) USING TTL 86400;""".stripMargin
            session.execute(insertQuery)
          }
        }
      }
      val result = stream.toIterator
      result
    }
    .writeStream
    .option("checkpointLocation", datalakeCheckpoints + "/delta")
    .foreachBatch { (batch: Dataset[EventRecord], _: Long) =>
      batch.show()

      batch
        .write
        .format("delta")
        .mode(SaveMode.Append)
        .save(deltaBronzePath)
    }
    .trigger(Trigger.ProcessingTime(10.seconds))
    .start()
    .awaitTermination()
}
