package com.yxt.bigdata.etl.connector.rdbms.writer

import java.sql.{Connection, PreparedStatement, SQLException, Struct}

import org.slf4j.LoggerFactory
import com.typesafe.config.Config
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.{types => SqlTypes}
import com.yxt.bigdata.etl.connector.base.AdvancedConfig
import com.yxt.bigdata.etl.connector.base.component.ETLWriter
import com.yxt.bigdata.etl.connector.base.db.DBUtil
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils.savePartition
import org.apache.spark.sql.jdbc.JdbcDialects
import org.apache.spark.sql.types.StructType

import scala.collection.mutable.ArrayBuffer


class RdbWriter(conf: Config) extends DBUtil(conf) with ETLWriter with Serializable {
  private val logger = LoggerFactory.getLogger(classOf[RdbWriter])

  override val tableName: String = AdvancedConfig.getString(conf, Key.TABLE_NAME)

  override var columns: Array[String] = {
    AdvancedConfig.getString(conf, Key.COLUMNS).split(",").map(_.trim)
  }

  private val batchSize: Int = AdvancedConfig.getIntWithDefaultValue(conf, Key.BATCH_SIZE, Constant.DEFAULT_BATCH_SIZE)

  override val writeMode: String = {
    val customWriteMode = AdvancedConfig.getString(conf, Key.WRITE_MODE).trim.toLowerCase
    val isWriteModeLegal = customWriteMode.equals("insert") || customWriteMode.equals("replace")
    if (!isWriteModeLegal)
      throw new Exception(s"您所配置的 writeMode: $customWriteMode 错误。因为目前仅支持 replace 或 insert 方式，请检查您的配置并作出修改。")

    // 直接用于SQL中
    customWriteMode.toUpperCase
  }

  private var writeSql: String = _

  private val columnNumber: Int = columns.length

  private var schema: StructType = _

  def saveTable(dataFrame: DataFrame, mode: String): Unit = {
    logger.info("开始写入")
    val writeSql = getWriteRecordSql(columns, mode)
    val rddSchema = dataFrame.schema
    val dialect = JdbcDialects.get(jdbcUrl)
    val isolationLevel = 0

    dataFrame.rdd.foreachPartition(iterator => {
      logger.info("开始批量插入")
      savePartition(getConnection, tableName, iterator, rddSchema, writeSql, batchSize, dialect, isolationLevel)
      logger.info("批量插入结束")
    })
  }

  def saveTable2(dataFrame: DataFrame): Unit = {
    writeSql = getWriteRecordSql(columns, writeMode)
    schema = dataFrame.schema

    dataFrame.rdd.foreachPartition(iterator => {
      logger.info("开始写入")
      val connection = getConnection()
      val writeBuffer = ArrayBuffer[Row]()

      try {
        for (row <- iterator) {
          writeBuffer += row

          if (writeBuffer.length >= batchSize) {
            doBatchWrite(connection, writeBuffer)
            writeBuffer.clear()
          }
        }

        if (writeBuffer.nonEmpty) {
          doBatchWrite(connection, writeBuffer)
          writeBuffer.clear()
        }
      } catch {
        case e: Exception => throw new Exception(s"此次数据写入出错，具体原因为： ${e.getMessage}")
      } finally {
        writeBuffer.clear()
        closeDB(null, null, connection)
      }
    })
  }

  def saveTableOld(dataFrame: DataFrame): Unit = {
    writeSql = getWriteRecordSql(columns, writeMode)
    schema = dataFrame.schema

    logger.info("开始写入")
    val connection = getConnection()
    val writeBuffer = ArrayBuffer[Row]()

    try {
      val iterator = dataFrame.toLocalIterator()
      while (iterator.hasNext) {
        writeBuffer += iterator.next()

        if (writeBuffer.length >= batchSize) {
          doBatchWrite(connection, writeBuffer)
          writeBuffer.clear()
        }
      }

      if (writeBuffer.nonEmpty) {
        doBatchWrite(connection, writeBuffer)
        writeBuffer.clear()
      }
    } catch {
      case e: Exception => throw new Exception(s"此次数据写入出错，具体原因为： ${e.getMessage}")
    } finally {
      writeBuffer.clear()
      closeDB(null, null, connection)
    }
  }

  private def getWriteRecordSql(columns: Array[String], writeMode: String): String = {
    val columnsHolder = columns.mkString(",")
    val valuesHolder = 0.until(columnNumber).map(_ => "?").mkString(",")

    if (writeMode == "INSERT") {
      val onDuplicateKeyUpdateHolder = onDuplicateKeyUpdateString(columns)
      s"""
         |$writeMode INTO $tableName
         |($columnsHolder)
         |VALUES($valuesHolder)
         |$onDuplicateKeyUpdateHolder
     """.stripMargin
    } else if (writeMode == "REPLACE") {
      s"""
         |$writeMode INTO $tableName
         |($columnsHolder)
         |VALUES($valuesHolder)
     """.stripMargin
    } else ""
  }

  private def onDuplicateKeyUpdateString(columns: Array[String]): String = {
    val sb = new StringBuilder()
    sb.append("ON DUPLICATE KEY UPDATE")
    var isFirst = true
    for (column <- columns) {
      if (isFirst) isFirst = false
      else sb.append(",")

      sb.append(s" $column=VALUES($column)")
    }

    sb.toString
  }

  private def doBatchWrite(connection: Connection, writeBuffer: ArrayBuffer[Row]): Unit = {
    var preparedStatement: PreparedStatement = null

    try {
      connection.setAutoCommit(false)
      preparedStatement = connection.prepareStatement(writeSql)

      for (row <- writeBuffer) {
        preparedStatement = fillPreparedStatement(preparedStatement, row)
        preparedStatement.addBatch()
        preparedStatement.clearParameters()
      }
      preparedStatement.executeBatch()
      connection.commit()
    } finally {
      closeDB(null, preparedStatement, null)
    }
  }

  private def fillPreparedStatement(preparedStatement: PreparedStatement, row: Row): PreparedStatement = {
    var mutPreparedStatement = preparedStatement
    for (i <- 0 until columnNumber) {
      val columnType = schema(i).dataType
      mutPreparedStatement = fillPreparedStatementColumnType(mutPreparedStatement, i, columnType, row)
    }

    mutPreparedStatement
  }

  private def fillPreparedStatementColumnType(preparedStatement: PreparedStatement,
                                              columnIndex: Int,
                                              columnType: SqlTypes.DataType,
                                              row: Row): PreparedStatement = {
    columnType match {
      case SqlTypes.IntegerType | SqlTypes.ShortType | SqlTypes.ByteType =>
        preparedStatement.setInt(columnIndex + 1, row.getInt(columnIndex))
      case SqlTypes.LongType =>
        preparedStatement.setLong(columnIndex + 1, row.getLong(columnIndex))
      case SqlTypes.DoubleType =>
        preparedStatement.setDouble(columnIndex + 1, row.getDouble(columnIndex))
      case SqlTypes.FloatType =>
        preparedStatement.setFloat(columnIndex + 1, row.getFloat(columnIndex))
      case SqlTypes.BooleanType =>
        preparedStatement.setBoolean(columnIndex + 1, row.getBoolean(columnIndex))
      case SqlTypes.StringType =>
        preparedStatement.setString(columnIndex + 1, row.getString(columnIndex))
      case SqlTypes.BinaryType =>
        preparedStatement.setBytes(columnIndex + 1, row.getAs[Array[Byte]](columnIndex))
      case SqlTypes.TimestampType =>
        preparedStatement.setTimestamp(columnIndex + 1, row.getTimestamp(columnIndex))
      case SqlTypes.DateType =>
        preparedStatement.setDate(columnIndex + 1, row.getDate(columnIndex))
      case t: SqlTypes.DecimalType =>
        preparedStatement.setBigDecimal(columnIndex + 1, row.getDecimal(columnIndex))
      case _ =>
        val columnName = schema(columnIndex).name
        throw new Exception(s"配置文件中的列配置信息有误，因为目前不支持数据库写入这种字段类型。字段名:[$columnName]，字段类型:[$columnType]，请修改表中该字段的类型或者不同步该字段。")
    }

    preparedStatement
  }
}
