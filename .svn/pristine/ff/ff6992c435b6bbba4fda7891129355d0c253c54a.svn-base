package com.yxt.bigdata.etl.connector.rdbms.dialect

object MysqlDialect extends BaseDialect {
  override def format_date_month(columnName: String): String = {
    s"DATE_FORMAT($columnName,'%Y-%m')"
  }
}
