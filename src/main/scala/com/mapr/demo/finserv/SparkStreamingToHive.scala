package com.mapr.demo.finserv

/** ****************************************************************************
  * PURPOSE:
  * This spark application consumes records from Kafka topics and continuously
  * persists each message in a Hive table, for the purposes of analysis and
  * visualization in Zeppelin.
  *
  * EXAMPLE USAGE:
  *   /opt/mapr/spark/spark-2.0.1/bin/spark-submit --class com.mapr.demo.finserv.SparkStreamingToHive /mapr/tmclust1/user/mapr/nyse-taq-streaming-1.0-jar-with-dependencies.jar --topics /user/mapr/taq:sender_1142,/user/mapr/taq:sender_0041 --table my_hive_table
  *
  * AUTHOR:
  * Ian Downard, idownard@mapr.com
  *
  * ****************************************************************************/

import org.apache.spark.sql.SparkSession
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{ Seconds, StreamingContext }
import org.apache.spark.streaming.kafka09.{ ConsumerStrategies, KafkaUtils, LocationStrategies }
import org.apache.spark.streaming.dstream.DStream

object SparkStreamingToHive {
  // Hive table name for persisted ticks
  var HIVE_TABLE: String = ""
  // Verbose output switch
  val VERBOSE: Boolean = false

  case class Tick(date: Long, exchange: String, symbol: String, price: Double, volume: Double, sender: String, receivers: Array[String]) extends Serializable

  val usage = """
    Usage: spark-submit --class com.mapr.demo.finserv.SparkStreamingToHive nyse-taq-streaming-1.0-jar-with-dependencies.jar --topics <topic1>,<topic2>... --table <destination Hive table>
  """

  def parseTick(record: String): Tick = {
    val tick = new com.mapr.demo.finserv.Tick(record)
    val receivers: Array[String]  = (List(tick.getReceivers) map (_.toString)).toArray
    Tick(tick.getTimeInMillis, tick.getExchange, tick.getSymbolRoot, tick.getTradePrice, tick.getTradeVolume, tick.getSender, receivers)
  }


  def main(args: Array[String]): Unit = {
    if (args.length != 4) { println(usage)
      throw new IllegalArgumentException("Missing command-line arguments")
    }

    var topicsSet: Set[String] = Set()

    if (args(0).compareTo("--topics") == 0) {
      topicsSet = args(1).split(",").toSet
    }
    else if (args(0).compareTo("--table") == 0) {
      HIVE_TABLE = args(1)
    }
    if (args(2).compareTo("--topics") == 0) {
      topicsSet = args(3).split(",").toSet
    }
    else if (args(2).compareTo("--table") == 0) {
      HIVE_TABLE = args(3)
    }


    println("Consuming messages from topics: " + topicsSet.mkString(", "))
    println("Persisting messages in Hive table: " + HIVE_TABLE)

    val brokers = "localhost:9092" // not needed for MapR Streams, needed for Kafka
    val groupId = "SparkStreamingToHive"
    val batchInterval = "2"
    val pollTimeout = "10000"

    val sparkConf = new SparkConf().setAppName("TickStream")

    val ssc = new StreamingContext(sparkConf, Seconds(batchInterval.toInt))

    // Create direct kafka stream with brokers and topics
    val kafkaParams = Map[String, String](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokers,
      ConsumerConfig.GROUP_ID_CONFIG -> groupId,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "true"
    )

    val consumerStrategy = ConsumerStrategies.Subscribe[String, String](topicsSet, kafkaParams)
    val messagesDStream = KafkaUtils.createDirectStream[String, String](
      ssc, LocationStrategies.PreferConsistent, consumerStrategy
    )
    // get message values from key,value
    val valuesDStream: DStream[String] = messagesDStream.map(_.value())

    println("Waiting for messages...")

    valuesDStream.foreachRDD { rdd =>

      // There exists at least one element in RDD
      if (!rdd.isEmpty) {
        val count = rdd.count
        println("count received " + count)
        val spark = SparkSession
          .builder()
          .appName("SparkSessionTicks")
          .config(rdd.sparkContext.getConf)
          .enableHiveSupport()
          .getOrCreate()
        import spark.implicits._

        val df = rdd.map(parseTick).toDF()
        // Display the top 20 rows of DataFrame
        if (VERBOSE) {
          df.printSchema()
          df.show()
        }

        df.createOrReplaceTempView("batchTable")

        // Validate the dataframe against the temp table
        if (VERBOSE) {
          df.groupBy("sender").count().show
          spark.sql("select sender, count(sender) as count from batchTable group by sender").show
        }

        spark.sql("create table if not exists " + HIVE_TABLE + " as select * from batchTable")
        spark.sql("insert into " + HIVE_TABLE + " select * from batchTable")

        // Validate the dataframe against the Hive table
        println("hive count:")
        df.groupBy("date").count().show
        spark.sql("select count(*) from" + HIVE_TABLE).show
      }
    }

    ssc.start()
    ssc.awaitTermination()

    ssc.stop(stopSparkContext = true, stopGracefully = true)
  }

}
