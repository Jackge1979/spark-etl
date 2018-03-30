package com.yxt.bigdata.etl.connector.rdbms.reader

import com.typesafe.config.Config
import org.apache.spark.sql.{DataFrame, SparkSession}

import com.yxt.bigdata.etl.connector.base.component.ETLReader
import com.yxt.bigdata.etl.connector.base.db.DBUtil
import com.yxt.bigdata.etl.connector.base.AdvancedConfig


class RdbReader(conf: Config) extends DBUtil(conf) with ETLReader {
  override val tableName: Option[String] = AdvancedConfig.getString(conf, Key.TABLE_NAME, isNecessary = false)

  override var columns: Option[Array[String]] = {
    val cols = AdvancedConfig.getString(conf, Key.COLUMNS, isNecessary = false)
    cols.map(_.split(",").map(_.trim.toLowerCase))
  }

  override var where: Option[String] = AdvancedConfig.getString(conf, Key.WHERE, isNecessary = false)

  override val querySql: Option[String] = AdvancedConfig.getString(conf, Key.QUERY_SQL, isNecessary = false)

  val predicatesConf: Option[Config] = AdvancedConfig.getConfig(conf, Key.PREDICATES, isNecessary = false)

  override def getDataFrame(spark: SparkSession): DataFrame = {
    /*
    query sql
    指定querySql时，columns和where则忽略;
    若querySql为空，则columns不能为空。
     */
    val table = querySql match {
      case Some(sql) =>
        // * 使用查询语句必须要用括号，否则会被识别为table;
        // * 不能包含分号，会被识别为无效字符.
        "(" + sql.replace(";", "") + ")"
      case None =>
        tableName match {
          case Some(tbName) =>
            // where
            where match {
              case Some(condition) =>
                val sql = s"select * from $tbName where $condition"
                "(" + sql.replace(";", "") + ")"
              case None => tbName
            }
          case None => throw new Exception("您未配置 tableName ；请检查您的配置文件并作出修改，建议 tableName 的格式为 db.table 。")
        }
    }

    var df: DataFrame = predicatesConf match {
      case Some(predConf) =>
        val typeName = AdvancedConfig.getString(predConf, Key.PREDICATES_TYPE)
        typeName match {
          case "long" =>
            /*
            多并发
            按数字字段切分
             */
            val (columnName, lowerBound, upperBound, numPartitions) = new PredicatesParser(dialect).parseLongFieldPredicates(predConf)
            spark.read.option("fetchsize", "1024").jdbc(
              jdbcUrl,
              table,
              columnName,
              lowerBound,
              upperBound,
              numPartitions,
              jdbcProperties
            )
          case "uuid" =>
            /*
            多并发
            按UUID字段首字母切分
             */
            val predicates = new PredicatesParser(dialect).parseUUIDPredicates(predConf)
            spark.read.option("fetchsize", "1024").jdbc(
              jdbcUrl,
              table,
              predicates,
              jdbcProperties
            )
          case "date" =>
            /*
            多并发
            按月切分
             */
            val predicates = new PredicatesParser(dialect).parseDatePredicates(predConf)
            spark.read.option("fetchsize", "1024").jdbc(
              jdbcUrl,
              table,
              predicates,
              jdbcProperties
            )
          case "custom" =>
            /*
            多并发
            自定义切分规则
             */
            val predicates = new PredicatesParser(dialect).parseCustomPredicates(predConf)
            spark.read.option("fetchsize", "1024").jdbc(
              jdbcUrl,
              table,
              predicates,
              jdbcProperties
            )
          case _ => throw new Exception("您配置的 predicates.type 错误，目前仅支持 'custom'、'long'、'uuid'，请检查你的配置并作出修改。")
        }
      case None =>
        /*
        单并发
        未配置predicates默认使用单并发
         */
        spark.read.option("fetchsize", "1024").jdbc(
          jdbcUrl,
          table,
          jdbcProperties
        )
    }

    // columns
    val exceptionOfColumns = new Exception("您未配置 columns ；请检查你的配置文件并作出修改，注意 columns 以英文逗号作为分隔符。")
    columns match {
      case Some(cols) =>
        if (cols.length == 0) {
          throw exceptionOfColumns
        } else {
          df = df.selectExpr(cols: _*) // 对于'*'也适用
        }
      case None => throw exceptionOfColumns
    }

    // 列名最小化
    val loweredColumns = df.columns.map(col => col.toLowerCase)
    df.toDF(loweredColumns: _*)
    // 输出schema
    df.printSchema()

    df
  }
}
