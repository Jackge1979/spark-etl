package com.yxt.bigdata.etl.connector.rdbms.dialect

trait BaseDialect {
  def format_date_month(columnName: String): String
}
