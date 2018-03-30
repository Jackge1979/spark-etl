package com.yxt.bigdata.etl.connector.rdbms.dialect

object OracleDialect extends BaseDialect {
  override def format_date_month(columnName: String): String = s"to_char($columnName, 'yyyy-MM')"
}
