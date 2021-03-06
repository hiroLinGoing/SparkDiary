package com.evayInfo.Inglory.SparkDiary.database.mysql

import java.util.Properties

import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

/**
  * Created by sunlu on 17/6/23.
  * 对mysql数据库进行读写操作
  */
object readWriteMysql {


  def SetLogger = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("com").setLevel(Level.OFF)
    System.setProperty("spark.ui.showConsoleProgress", "false")
    Logger.getRootLogger().setLevel(Level.OFF);
  }

  def main(args: Array[String]) {
    //不打印日志信息
    SetLogger

    val conf = new SparkConf().setAppName(s"readWriteMysql").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(conf).getOrCreate()
    val sc = spark.sparkContext

    //connect mysql database
    val url1 = "jdbc:mysql://localhost:3306/sunluMySQL"
    val prop1 = new Properties()
    prop1.setProperty("user", "root")
    prop1.setProperty("password", "root")
    //get data
    val ds1 = spark.read.jdbc(url1, "testTable2", prop1)

    //将ds1保存到testTable2表中
    val url2 = "jdbc:mysql://localhost:3306/sunluMySQL?useUnicode=true&characterEncoding=UTF-8"
    //使用"?useUnicode=true&characterEncoding=UTF-8"以防止出现存入MySQL数据库中中文乱码情况
    //    val url = "jdbc:mysql://localhost:3306/sunluMySQL?useUnicode=true&characterEncoding=UTF-8&" +
    //      "useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
    // 使用"useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"防止出现时间上的错误
    val prop2 = new Properties()
    prop1.setProperty("driver", "com.mysql.jdbc.Driver") //防止找不到driver
    prop2.setProperty("user", "root")
    prop2.setProperty("password", "root")

    //将结果保存到数据框中
    ds1.write.mode("append").jdbc(url2, "testTable4", prop2) //overwrite

    sc.stop()
    spark.stop()


  }
}
