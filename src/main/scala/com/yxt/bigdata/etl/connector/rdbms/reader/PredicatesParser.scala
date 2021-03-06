package com.yxt.bigdata.etl.connector.rdbms.reader

import com.typesafe.config.Config
import com.yxt.bigdata.etl.connector.base.AdvancedConfig
import com.yxt.bigdata.etl.connector.rdbms.dialect.BaseDialect

class PredicatesParser(val dialect: BaseDialect) {
  /*
  按实际情况选择相应的方案，避免出现数据倾斜
   */
  def parseCustomPredicates(predicatesConf: Config): Array[String] = {
    /*
    type: custom

    "predicates": {
      "type": "custom",
      "rules": [
        ...
      ]
    }
     */
    AdvancedConfig.getStringArray(predicatesConf, Key.PREDICATES_RULES)
  }

  def parseLongFieldPredicates(predicatesConf: Config): (String, Long, Long, Int) = {
    /*
    type: long

    "predicates": {
      "type": "long",
      "rules": {
        "columnName": "...",
        "lowerBound": "...",
        "upperBound": "...",
        "numPartitions": "..."
      }
    }
     */
    val columnName = AdvancedConfig.getString(predicatesConf, Key.PREDICATES_RULES + "." + "columnName")
    val lowerBound = AdvancedConfig.getString(predicatesConf, Key.PREDICATES_RULES + "." + "lowerBound").toLong
    val upperBound = AdvancedConfig.getString(predicatesConf, Key.PREDICATES_RULES + "." + "upperBound").toLong
    val numPartition = AdvancedConfig.getInt(predicatesConf, Key.PREDICATES_RULES + "." + "numPartitions")
    (columnName, lowerBound, upperBound, numPartition)
  }

  def parseUUIDPredicates(predicatesConf: Config): Array[String] = {
    /*
    type: uuid

    "predicates": {
      "type": "uuid",
      "rules": {
        "columnName": "..."
      }
    }
     */
    val columnName = AdvancedConfig.getString(predicatesConf, Key.PREDICATES_RULES + "." + "columnName")
    var predicates = Array[String](s"$columnName<'0'", s"$columnName>='f'")

    // uuid是由16进制数构成，0~9，a~f
    val ascii = Array.concat((48 to 57).toArray, (97 to 102).toArray)
    val topChars = ascii.map(_.toChar.toString)
    for (i <- 0 until topChars.length - 1) {
      val start = topChars(i)
      val end = topChars(i + 1)
      predicates :+= s"$columnName>='$start' AND $columnName<'$end'"
    }

    predicates
  }

  def parseDatePredicates(predicatesConf: Config): Array[String] = {
    /*
      type: date
      定位到月，按月同步

      "predicates": {
        "type": "date",
        "rules": {
          "startYear": "...",
          "startMonth": "...",
          "endYear": "...",
          "endMonth": "...",
          "columnName": "..."
        }
      }
       */
    val columnName = AdvancedConfig.getString(predicatesConf, Key.PREDICATES_RULES + "." + "columnName")
    val startYear = AdvancedConfig.getInt(predicatesConf, Key.PREDICATES_RULES + "." + "startYear")
    val startMonth = AdvancedConfig.getInt(predicatesConf, Key.PREDICATES_RULES + "." + "startMonth")
    val endYear = AdvancedConfig.getInt(predicatesConf, Key.PREDICATES_RULES + "." + "endYear")
    val endMonth = AdvancedConfig.getInt(predicatesConf, Key.PREDICATES_RULES + "." + "endMonth")

    var predicates = Array[String]()
    var m = 1
    var M = 12
    for (i <- startYear to endYear) {
      if (i == startYear) m = startMonth
      else m = 1
      if (i == endYear) M = endMonth
      else M = 12
      for (j <- m to M) {
        predicates :+= dialect.format_date_month(columnName) + f"='$i-$j%02d'"
      }
    }

    predicates
  }
}
