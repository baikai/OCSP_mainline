package com.asiainfo.ocdp.stream.event

import com.asiainfo.ocdp.stream.common.{BroadcastConf, BroadcastManager, Logging}
import com.asiainfo.ocdp.stream.config.{EventConf, MainFrameConf}
import com.asiainfo.ocdp.stream.constant.EventConstant
import com.asiainfo.ocdp.stream.service.EventServer
import com.asiainfo.ocdp.stream.tools.{Json4sUtils, StreamWriterFactory}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{AnalysisException, DataFrame}

import scala.collection.mutable.ArrayBuffer

/**
 * Created by surq on 12/09/15
 */
class Event extends Serializable with Logging{

  var conf: EventConf = null

  val eventServer = new EventServer()
  val batchLimit = MainFrameConf.systemProps.getInt("cacheQryBatchSizeLimit")

  def init(eventconf: EventConf) {
    conf = eventconf
  }

  def buildEvent(df: DataFrame, uniqKeys: String) = {
    // 用户配置的本业务输出字段
    var mix_sel_expr = conf.select_expr.split(",")
    // 把主键追加到业务输出字段列中。查看用户配置的本业务输出字段中是否包括主键字段，如果没有则追加
    uniqKeys.split(":").map(key => mix_sel_expr = if (!mix_sel_expr.contains(key)) mix_sel_expr :+ key else mix_sel_expr)
    // 向输出字段中追加用字户定义字段，如固定字段“1”，“xxx”
    if (conf.get("ext_fields", null) != null)
      mix_sel_expr = mix_sel_expr ++ conf.get("ext_fields", null).split(",")

    try {
      // 根据业务条件过滤，并查询出输出字段
      val eventDF = df.filter(conf.filte_expr).selectExpr(mix_sel_expr: _*)

      // 事件复用的时候会用到，注意做eventDF.persist
      // if (EventConstant.NEEDCACHE == conf.getInt("needcache", 0)) cacheEvent(eventDF, uniqKeys)
      // 如果业务输出周期不为0，那么需要从codis中取出比兑营销时间，满足条件的输出
      val jsonRDD = if (EventConstant.RealtimeTransmission != conf.interval) checkEvent(eventDF, uniqKeys)
      else eventDF.toJSON
      outputEvent(jsonRDD, uniqKeys)
    }catch {
      case e: AnalysisException => logError(s"Make event failed since ${e}")
    }

  }

  /**
   * 过滤营销周期不满足的数据，输出需要营销的数据，并更新codis营销时间
   */
  import java.util.concurrent.ExecutorCompletionService

  import com.asiainfo.ocdp.stream.tools.CacheQryThreadPool

  import scala.collection.immutable
  def checkEvent(eventDF: DataFrame, uniqKeys: String): (RDD[String]) = {
    val time_EventId = EventConstant.EVENTCACHE_FIELD_TIMEEVENTID_PREFIX_KEY + conf.id

    BroadcastManager.broadcastEventConf(conf)
    val broadEventConf = BroadcastManager.getBroadEventConf

    val broadSysProps = BroadcastManager.getBroadSysProps
    val broadCodisProps = BroadcastManager.getBroadCodisProps
    val broadTaskConf = BroadcastManager.getBroadTaskConf

    eventDF.toJSON.mapPartitions(iter => {
      val conf = broadEventConf.value
      //Init Broadcast conf
      BroadcastConf.initProp(broadSysProps.value, broadCodisProps.value)

      val eventCacheService = new ExecutorCompletionService[immutable.Map[String, (String, Array[Byte])]](CacheQryThreadPool.threadPool)
      val batchList = new ArrayBuffer[Array[(String, String)]]()

      var batchArrayBuffer: ArrayBuffer[(String, String)] = null
      val jsonList = iter.toList
      val size = jsonList.size
      // 把json按指定的与codis库查询条数（batchLimit），分块更新
      for (index <- 0 until size) {
        if (index % batchLimit == 0) {
          // 把list放入线程池更新codis
          if (index != 0) batchList += batchArrayBuffer.toArray
          batchArrayBuffer = new ArrayBuffer[(String, String)]()
        }
        // 解析json数据，拼凑eventKey
        val line = jsonList(index)
        val current = Json4sUtils.jsonStr2Map(line)
        val eventKeyValue = uniqKeys.split(":").map(current(_)).mkString(":")
        // (eventCache:eventKeyValue,jsonValue)
        batchArrayBuffer += ((s"${EventConstant.EVENT_CACHE_PREFIX_NAME}_${broadTaskConf.value.name}:${eventKeyValue}", line))

        // 把list放入线程池更新codis
        if (index == size - 1) batchList += batchArrayBuffer.toArray
      }
      val outPutJsonList = eventServer.getEventCache(eventCacheService, batchList.toArray, time_EventId, conf.getInterval)

      outPutJsonList.iterator
    })
  }

  /**
   * rdd格式流输出
   */
  def outputEvent(rdd: RDD[String], uniqKeys: String) = {
    conf.outIFIds.map(ifconf => {
      val writer = StreamWriterFactory.getWriter(ifconf)
      writer.push(rdd, conf, uniqKeys)
    })
  }

  override def toString = s"Event($conf)"
}