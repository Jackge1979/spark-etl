package com.yxt.bigdata.etl

import scala.util.Properties

import org.apache.spark.sql.SparkSession


/*

 */

object MainHandler extends App {
  Properties.setProp("scala.time", "true")

  val spark = SparkSession.builder()
    .appName("Spark-ETL")
    //                .master("local[*]")
    .enableHiveSupport()
    .getOrCreate()

  val jsonFile = args(0)
  val job = new JobParser(jsonFile)
  val writer = job.getWriter
  for (reader <- job.getReaders) {
    // 数据库查询
    var df = reader.getDataFrame(spark)

    val readerColumns = reader.getColumnsFromDataFrame(df)
    // 当writer的columns为'*'时，遵循reader的配置
    if ("*".equals(writer.columns.mkString(","))) {
      writer.columns = readerColumns
    } else {
      val writerColumns = writer.columns
      if (readerColumns.length != writerColumns.length) {
        throw new Exception("列配置信息有误，因为您配置的任务中，源头读取字段数：%d 与 目标表要写入的字段数：%d 不相等，请检查您的配置并作出修改。")
      }

      val columnsWithAlias = new Array[String](readerColumns.length)
      for (i <- readerColumns.indices) {
        val rc = readerColumns(i)
        val wc = writerColumns(i)
        if (rc.equals(wc)) columnsWithAlias(i) = s"$rc"
        else columnsWithAlias(i) = s"$rc AS $wc"
      }
      df = df.selectExpr(columnsWithAlias: _*)
    }

    writer.saveTable(df, writer.writeMode)
  }

  spark.close()
}