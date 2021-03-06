package com.evayInfo.Inglory.Project.DataMiningPlatform.machine.learning.recommend

import java.util.Properties


import com.evayInfo.Inglory.Project.DataMiningPlatform.utils.{Constants, ConfigurationManager}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, MatrixEntry}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Row, SparkSession}

/**
 * Created by sunlu on 18/10/26.
 * 功能描述：构建基于用户的协同过滤模型
 */

case class UserSimi(user_id_1: Long, user_id_2: Long, similar: Double)

class BuildUserBasedModel {

  // 是否输出日志
  def SetLogger = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("com").setLevel(Level.OFF)
    Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
    System.setProperty("spark.ui.showConsoleProgress", "false")
    Logger.getRootLogger().setLevel(Level.OFF)
  }


  // 链接mysql配置信息
  //  val url = "jdbc:mysql://localhost:3306/data_mining_DB?useUnicode=true&characterEncoding=UTF-8&" +
  //    "useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
  //  val user = "root"
  //  val password = "123456"
  val url: String = ConfigurationManager.getProperty(Constants.MYSQL_JDBC_URL)
  val user: String = ConfigurationManager.getProperty(Constants.MYSQL_JDBC_USER)
  val password: String = ConfigurationManager.getProperty(Constants.MYSQL_JDBC_PASSWORD)

  val prop = new Properties()
  prop.setProperty("user", user)
  prop.setProperty("password", password)


/*
  构建基于用户的协同过滤模型（构建模型）
  train_table:训练集名称，String类型
  user_col:用户列，String类型
  item_col:商品列，String类型
  rating_col:打分列，String类型
  simi_threshold:形似性阈值，Double类型，0～1之间
  model_name:模型路径，String类型
  test_table:测试集名称，String类型
  opt_table:测试集预测结果表，String类型
 */
  def UserBased(train_table:String,user_col:String,item_col:String,rating_col:String,
                simi_threshold:Double,model_name:String):Boolean={
    val SparkConf = new SparkConf().setAppName(s"BuildUserBasedModel:UserBased").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(SparkConf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    // 读取mysql数据
    val col_name = Seq("user","item","rating")
    val train_df = spark.read.jdbc(url, train_table, prop).select(user_col,item_col,rating_col).toDF(col_name:_*)

    // string to index
    val user_indexer = new StringIndexer()
      .setInputCol("user")
      .setOutputCol("user_id")

    val item_indexer = new StringIndexer()
      .setInputCol("item")
      .setOutputCol("item_id")

    // Create our pipeline
    val indexer_pipeline = new Pipeline().setStages(Array(user_indexer, item_indexer))
    // Train the model
    val indexer_model = indexer_pipeline.fit(train_df)

    val indexer_df = indexer_model.transform(train_df)

    val train_pre_df = indexer_df.
      withColumn("user_id", $"user_id".cast("long")).
      withColumn("item_id", $"item_id".cast("long")).
      withColumn("rating", $"rating".cast("double"))

    val rating_rdd  = train_pre_df.select("user_id","item_id","rating").
      rdd.map{case Row(user_id:Long,item_id:Long, rating:Double) => MatrixEntry(user_id, item_id,rating)}

    //calculate similarities
    val ratings = new CoordinateMatrix(rating_rdd).transpose()
    //    val simi_threshold = 0.1
    val userSimi = ratings.toRowMatrix.columnSimilarities()
    // user-user similarity
    val userSimiRdd = userSimi.entries.map(f => UserSimi(f.i, f.j, f.value))

    val simi_col_name = Seq("user_id_1", "user_id_2","simi")
    val user_id_simi_df = userSimiRdd.map { f => (f.user_id_1, f.user_id_2, f.similar) }.
      union(userSimiRdd.map { f => (f.user_id_2, f.user_id_1, f.similar) }).toDF(simi_col_name:_*)

    val user_1_lab = train_pre_df.select("user_id","user").toDF("user_id_1","user_1")
    val user_2_lab = train_pre_df.select("user_id","user").toDF("user_id_2","user_2")
    val user_simi_df = user_id_simi_df.join(user_1_lab, Seq("user_id_1"), "left").
      join(user_2_lab, Seq("user_id_2"), "left").na.drop.select("user_1", "user_2", "simi").
      withColumn("simi", bround($"simi", 3)) // 保留3位有效数字

    // 保存 user_simi_df 到mysql
    user_simi_df.write.mode("overwrite").jdbc(url, model_name, prop) //overwrite ; append

    sc.stop()
    spark.stop()

    return true
  }


  /*
  构建基于用户的协同过滤模型（构建模型＋模型预测）
  train_table:训练集名称，String类型
  user_col:用户列，String类型
  item_col:商品列，String类型
  rating_col:打分列，String类型
  simi_threshold:形似性阈值，Double类型，0～1之间
  model_name:模型路径，String类型
  test_table:测试集名称，String类型
  opt_table:测试集预测结果表，String类型
   */
  def UserBased_test(train_table:String,user_col:String,item_col:String,rating_col:String,
                     simi_threshold:Double,
                     model_name:String,test_table:String,opt_table:String):Boolean={

    val SparkConf = new SparkConf().setAppName(s"BuildUserBasedModel:UserBased_test").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(SparkConf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    // 读取mysql数据
    val col_name = Seq("user","item","rating")
    val train_df = spark.read.jdbc(url, train_table, prop).select(user_col,item_col,rating_col).toDF(col_name:_*)

    // string to index
    val user_indexer = new StringIndexer()
      .setInputCol("user")
      .setOutputCol("user_id")

    val item_indexer = new StringIndexer()
      .setInputCol("item")
      .setOutputCol("item_id")

    // Create our pipeline
    val indexer_pipeline = new Pipeline().setStages(Array(user_indexer, item_indexer))
    // Train the model
    val indexer_model = indexer_pipeline.fit(train_df)

    val indexer_df = indexer_model.transform(train_df)

    val train_pre_df = indexer_df.
      withColumn("user_id", $"user_id".cast("long")).
      withColumn("item_id", $"item_id".cast("long")).
      withColumn("rating", $"rating".cast("double"))

    val rating_rdd  = train_pre_df.select("user_id","item_id","rating").
      rdd.map{case Row(user_id:Long,item_id:Long, rating:Double) => MatrixEntry(user_id, item_id,rating)}

    //calculate similarities
    val ratings = new CoordinateMatrix(rating_rdd).transpose()
    //    val simi_threshold = 0.1
    val userSimi = ratings.toRowMatrix.columnSimilarities(simi_threshold)
    // user-user similarity
    val userSimiRdd = userSimi.entries.map(f => UserSimi(f.i, f.j, f.value))

    val simi_col_name = Seq("user_id_1", "user_id_2","simi")
    val user_id_simi_df = userSimiRdd.map { f => (f.user_id_1, f.user_id_2, f.similar) }.
      union(userSimiRdd.map { f => (f.user_id_2, f.user_id_1, f.similar) }).toDF(simi_col_name:_*)

    val user_1_lab = train_pre_df.select("user_id","user").toDF("user_id_1","user_1")
    val user_2_lab = train_pre_df.select("user_id","user").toDF("user_id_2","user_2")
    val user_simi_df = user_id_simi_df.join(user_1_lab, Seq("user_id_1"), "left").
      join(user_2_lab, Seq("user_id_2"), "left").na.drop.select("user_1", "user_2", "simi").
      withColumn("simi", bround($"simi", 3)) // 保留3位有效数字

    // 保存 user_simi_df 到mysql
    user_simi_df.write.mode("overwrite").jdbc(url, model_name, prop) //overwrite ; append

    // 对输入的测试集进行预测
    val model_application = new UserBasedModelApplication()
    model_application.RatingPrediction(test_table,model_name,user_col,item_col,rating_col,opt_table)

    sc.stop()
    spark.stop()
    return true

  }



}
