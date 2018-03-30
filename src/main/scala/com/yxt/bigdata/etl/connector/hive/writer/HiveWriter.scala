package com.yxt.bigdata.etl.connector.hive.writer

import com.typesafe.config.Config
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.types.StructType
import com.yxt.bigdata.etl.connector.base.AdvancedConfig
import com.yxt.bigdata.etl.connector.base.component.ETLWriter


class HiveWriter(conf: Config) extends ETLWriter {
  override val tableName: String = AdvancedConfig.getString(conf, Key.TABLE_NAME)

  override var columns: Array[String] = {
    AdvancedConfig.getString(conf, Key.COLUMNS).split(",").map(_.trim.toLowerCase)
  }

  override val writeMode: String = AdvancedConfig.getStringWithDefaultValue(conf, Key.WRITE_MODE, Constant.WRITE_MODE)

  val partitionBy: Option[Array[String]] = AdvancedConfig.getStringArray(conf, Key.PARTITION_BY, isNecessary = false)

  override def saveTable(dataFrame: DataFrame, mode: String): Unit = {
    val spark = dataFrame.sparkSession
    val originalTableName = tableName

    // 创建表
    //    createTableIfNotExists(spark, dataFrame.schema, originalTableName)

    // 存储格式：textfile、orc
    // textfile: dropDelims(dataFrame)
    // orc: 不需要指定考虑分隔符
    val dataFrameWithoutDelims = dropDelims(dataFrame)


    // 方案一：使用SQL，灵活度高；
    //    val tmpTableName = genTmpTableName(tableName)
    //    dataFrameWithoutDelims.createOrReplaceTempView(tmpTableName)
    //    spark.sql(s"insert overwrite table $originalTableName select * from $tmpTableName")
    // 方案二：dataFrame原生API，简洁易读；
    var dataFrameWriter = dataFrameWithoutDelims.write.format("orc")
    // 确定写入模式
    dataFrameWriter = mode match {
      case "overwrite" => dataFrameWriter.mode(SaveMode.Overwrite)
      case "append" => dataFrameWriter.mode(SaveMode.Append)
      case _ => throw new Exception(s"写入模式有误，您配置的写入模式为：$mode，而目前hiveWriter支持的写入模式仅为：'overwrite' 和 'append'，请检查您的配置项并作出相应的修改。")
    }
    // 确定分区情况
    dataFrameWriter = partitionBy match {
      case Some(cols) =>
        spark.sqlContext.setConf("hive.exec.dynamic.partition", "true")
        spark.sqlContext.setConf("hive.exec.dynamic.partition.mode", "nonstrict")
        dataFrameWriter.partitionBy(cols: _*)
      case None => dataFrameWriter
    }
    dataFrameWriter.saveAsTable(originalTableName)
  }

  def genTmpTableName(originalTableName: String): String = {
    var tmpTableName: String = null
    val sepTableName = originalTableName.split("[.]")
    val len = sepTableName.length

    if (len == 1) tmpTableName = s"tmp_$originalTableName"
    else if (len == 2) {
      val Array(_, table) = sepTableName
      tmpTableName = s"tmp_$table"
    }
    else throw new Exception(s"表名配置信息有误，您的表名为：$originalTableName , 而目前支持的表名格式有两种：'database.table' 和 'table'，请检查您的配置并作出修改。")

    tmpTableName
  }

  def createTableIfNotExists(spark: SparkSession, schema: StructType, tableName: String): Unit = {
    val fields = schema.fields

    val sb = new StringBuilder()
    for (f <- fields) sb.append(s"${f.name} ${f.dataType.typeName},")
    val colString = sb.toString
    val createSql =
    //      s"""
    //         |CREATE TABLE IF NOT EXISTS
    //         |$tableName
    //         |(${colString.slice(0, colString.length - 1)})
    //         |ROW FORMAT DELIMITED
    //         |FIELDS TERMINATED BY '\001'
    //         |LINES TERMINATED BY '\n'
    //         |STORED AS TEXTFILE
    //       """.stripMargin
      s"""
         |CREATE TABLE IF NOT EXISTS
         |$tableName
         |(${colString.slice(0, colString.length - 1)})
         |STORED AS ORC
       """.stripMargin
    spark.sql(createSql)
  }

  def dropDelims(dataFrame: DataFrame): DataFrame = {
    val schema = dataFrame.schema
    val fields = schema.fields
    val exprs = new Array[String](fields.length)
    for (i <- fields.indices) {
      val field = fields(i)
      if ("string".equals(field.dataType.typeName)) {
        exprs(i) = s"regexp_replace(${field.name}, '\\r', '\\0') AS ${field.name}"
      } else exprs(i) = s"${field.name}"
    }
    dataFrame.selectExpr(exprs: _*)
  }
}
