package com.yxt.bigdata.etl

import java.io.File
import scala.collection.JavaConversions._
import com.typesafe.config.ConfigFactory
import com.yxt.bigdata.etl.connector.base.component.{ETLReader, ETLWriter}
import com.yxt.bigdata.etl.connector.hive.reader.HiveReader
import com.yxt.bigdata.etl.connector.hive.writer.HiveWriter
import com.yxt.bigdata.etl.connector.rdbms.reader.RdbReader
import com.yxt.bigdata.etl.connector.rdbms.writer.RdbWriter


class JobParser(private val jobConfPath: String) {
  private lazy val jobConf = ConfigFactory.parseFile(new File(jobConfPath))

  def getReaders: List[ETLReader] = {
    val readers = for (readerConf <- jobConf.getConfigList("reader").toList) yield {
      val name = readerConf.getString("name").toLowerCase
      name match {
        case "mysqlreader" => new RdbReader(readerConf)
        case "oraclereader" => new RdbReader(readerConf)
        case "hivereader" => new HiveReader(readerConf)
        case _ => throw new Exception("您在 reader 中配置的 'name' 错误，请检查您的配置文件并作出修改。")
      }
    }
    readers
  }

  def getWriter: ETLWriter = {
    val writerConf = jobConf.getConfig("writer")
    val name = writerConf.getString("name").toLowerCase
    name match {
      case "mysqlwriter" => new RdbWriter(writerConf)
      case "oraclewriter" => new RdbWriter(writerConf)
      case "hivewriter" => new HiveWriter(writerConf)
      case _ => throw new Exception("您在 writer 中配置的 'name' 错误，请检查您的配置文件并作出修改。")
    }
  }
}
