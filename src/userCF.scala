
import utils.{getDiffDatetime, getProPerties, saveHbase}

import scala.collection.mutable.ArrayBuffer
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.expressions.Window

object userCF {

  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession
      .builder
      .master("yarn")
      .appName("userCF")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits._

    /**
     * window_days: 时间窗口
     * similar_user_num: 商品的候选相似用户数量
     * hot_item_regular: 热门商品惩罚力度
     * profile_decay: 用户偏好时间衰减率
     * black_user: 黑名单用户
     * black_items: 黑名单商品
     * recommend_num:  推荐商品数量
     */

    val properties = getProPerties(args(0))
    val window_days = properties.getProperty("window_days").toInt
    val similar_user_num = properties.getProperty("similar_user_num").toInt
    val hot_item_regular = properties.getProperty("hot_item_regular").toDouble
    val profile_decay = properties.getProperty("profile_decay").toDouble
    val black_users = properties.getProperty("black_users")
    val black_items = properties.getProperty("black_items")
    val recommend_num = properties.getProperty("recommend_num").toInt
    val start_date = getDiffDatetime(window_days)
    val table_date = getDiffDatetime(0)

    println(s"训练时间窗口:${start_date} => ${table_date}")

    val df_sales = spark.sql(s"select USR_NUM_ID, ITEM_NUM_ID, ORDER_DATE from gp_test.sales_data " +
      s"where to_date(ORDER_DATE) >= '${start_date}' and USR_NUM_ID not in (${black_users}) and ITEM_NUM_ID not in (${black_items})")
      .toDF("userid", "itemid", "date").cache()

    println(s"交易数量:${df_sales.count()}")

    // 商品的倒排表
    val item_user = df_sales.groupBy("itemid").agg(collect_set("userid").as("user_set"))

    // 用户共现矩阵
    val item_user2 = item_user.flatMap { row =>
      val userlist = row.getAs[scala.collection.mutable.WrappedArray[Long]](1).toArray.sorted
      val result = new ArrayBuffer[(Long, Long, Double)]()
      for (i <- 0 to userlist.length - 2) {
        for (j <- i + 1 to userlist.length - 1) {
          result += ((userlist(i), userlist(j), 1.0 / math.log(1 + userlist.length))) // 热门商品惩罚
        }
      }
      result
    }.withColumnRenamed("_1", "useridI").withColumnRenamed("_2", "useridJ").withColumnRenamed("_3", "score")

    val item_user3 = item_user2.groupBy("useridI", "useridJ").agg(sum("score").as("sumIJ"))

    // 计算用户的购买次数
    val df_sales0 = df_sales.withColumn("score", lit(1)).groupBy("userid").agg(sum("score").as("score"))

    // 准备计算共现相似度,N ∩ M / srqt(N * M), row_number取top similar_user_num
    val df_sales4 = item_user3.join(df_sales0.withColumnRenamed("userid", "useridJ").withColumnRenamed("score", "sumJ").select("useridJ", "sumJ"), "useridJ")
    val df_sales5 = df_sales4.join(df_sales0.withColumnRenamed("userid", "useridI").withColumnRenamed("score", "sumI").select("useridI", "sumI"), "useridI")
    val df_sales6 = df_sales5.withColumn("result", bround(col("sumIJ") / sqrt(col("sumI") * col("sumJ")), 5)).withColumn("rank", row_number().over(Window.partitionBy("useridI").orderBy($"result".desc))).filter(s"rank <= ${similar_user_num}").drop("rank")

    //  user1和user2交换
    val df_sales8 = df_sales6.select("useridI", "useridJ", "result").union(df_sales6.select($"useridJ".as("useridI"), $"useridI".as("useridJ"), $"result")).withColumnRenamed("result", "similar").cache()
    val usercf_similar = df_sales8.map { row =>
      val userdI = row.getLong(0)
      val userdJ_similar = (row.getLong(1).toString, row.getDouble(2))
      (userdI, userdJ_similar)
    }.toDF("userid", "similar_users").groupBy("userid").agg(collect_list("similar_users").as("similar_users"))

    // 计算用户偏好和商品热度
    val user_score = df_sales.withColumn("pref", lit(1) / (datediff(current_date(), $"date") * profile_decay + 1)).groupBy("userid", "itemid").agg(sum("pref").as("pref"))
    val item_score = df_sales.withColumn("sum_item", lit(1)).groupBy("itemid").agg(sum("sum_item").as("sum_item"))

    // 连接用户偏好，用户相似度，商品热度
    val df_user_prefer1 = df_sales8.join(user_score, $"useridI" === $"userid", "inner").join(item_score, "itemid")
//      +------+-------+----------------+-------+------+-------------------+--------+
//      |itemid|useridI|         useridJ|similar|userid|               pref|sum_item|
//      +------+-------+----------------+-------+------+-------------------+--------+
//      | 96651|  21342|1806040397877006|0.17648| 21342| 0.8230452674897121|     155|
//      | 64245|  21342|1806040397877006|0.17648| 21342|0.41152263374485604|      15|
//      | 96446|  21342|1806040397877006|0.17648| 21342|0.41152263374485604|      17|
//      +------+-------+----------------+-------+------+-------------------+--------+

    // 偏好 × 相似度 × 商品热度降权
    val df_user_prefer2 = df_user_prefer1.withColumn("score", col("pref") * col("similar") * (lit(1) / log(col("sum_item") * hot_item_regular + math.E))).select("useridJ", "itemid", "score")

    // 取推荐top，把已经购买过的去除
    val df_user_prefer3 = df_user_prefer2.groupBy("useridJ", "itemid").agg(sum("score").as("score")).withColumnRenamed("useridJ", "userid")
    val df_user_prefer4 = df_user_prefer3.join(user_score, Seq("userid", "itemid"), "left").filter("pref is null")
    val usercf_recommend = df_user_prefer4.select($"userid", $"itemid".cast("String"), $"score").withColumn("rank", row_number().over(Window.partitionBy("userid").orderBy($"score".desc))).filter(s"rank <= ${recommend_num}").groupBy("userid").agg(collect_list("itemid").as("recommend"))

    // usercf用户相似度保存到hbase
    saveHbase(usercf_similar, "SHOPFORCE:USERCF_SIMILAR")

    // usercf推荐结果保存到hbase
    saveHbase(usercf_recommend, "USERCF_RECOMMEND")
   
   spark.close()
  }

}

