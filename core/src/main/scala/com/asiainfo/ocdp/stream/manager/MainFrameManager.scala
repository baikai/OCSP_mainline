package com.asiainfo.ocdp.stream.manager

import java.io.File
import java.util.{Timer, TimerTask}

import akka.actor.{ActorSystem, Props}
import com.asiainfo.ocdp.stream.common.Logging
import com.asiainfo.ocdp.stream.config.{MainFrameConf, TaskConf}
import com.asiainfo.ocdp.stream.constant.{CommonConstant, ExceptionConstant, TaskConstant}
import com.asiainfo.ocdp.stream.service.TaskServer
import com.asiainfo.ocdp.stream.tools.{DateFormatUtils, ListFileWalker, MonitorUtils}
import org.apache.commons.io.filefilter.{FileFilterUtils, HiddenFileFilter}
import org.apache.commons.lang.StringUtils

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * Created by tsingfu on 15/8/26.
 */
object MainFrameManager extends Logging {
  logBegin
  private val waiter = new ContextWaiter
  val delaySeconds = MainFrameConf.systemProps.getInt("delaySeconds", 10)
  val periodSeconds = MainFrameConf.systemProps.getInt("periodSeconds", 30)
  val startTimeOutSeconds = MainFrameConf.systemProps.getInt("startTimeOutSeconds", 120)

  var lastCheckTime = System.currentTimeMillis()

  // 对表STREAM_TASK的务服句柄
  val taskServer = new TaskServer()

  // getStatus:0 停止 1 启动中 2运行中 3停止中
  // 装载taskConf.getStatus=１的taskID和系统时间
  val pre_start_tasks = mutable.Map[String, Long]()
  // 装载taskConf.getStatus=3的taskID和系统时间
  val pre_stop_tasks = mutable.Map[String, Long]()

  val current_time = System.currentTimeMillis()

  val RETRY_RESET_TIMEDOUT = 60 * 60 * 24 * 1000
  val RETRY_MIN_INTERVAL = 30 * 1000
  
  taskServer.getAllTaskInfos().foreach(taskConf => {
    if (taskConf.getStatus == TaskConstant.PRE_START)
      pre_start_tasks.put(taskConf.getId, current_time)
  })

  // Create an Akka system
  val system = ActorSystem("TaskSystem")

  private val timer = new Timer("Task build timer", true)

  private val task = new TimerTask {
    override def run() {
      try {
        buildTask()
      } catch {
        case e: Exception =>
          taskServer.insertExcepiton(ExceptionConstant.MainFramID, "", ExceptionConstant.ERR_MAINFRAME_EXCEPTION, ExceptionConstant.getExceptionInfo(ExceptionConstant.ERR_MAINFRAME_EXCEPTION))
          logError("Error start new app ", e)
          waiter.notifyStop()
      }
    }
  }

  if (delaySeconds > 0) {
    logInfo(
      "Starting check task list status with delay of " + delaySeconds + " secs " +
        "and period of " + periodSeconds + " secs")
    timer.schedule(task, delaySeconds * 1000, periodSeconds * 1000)
  }

  def main(args: Array[String]) {
    waiter.waitForStopOrError()
    timer.cancel()
    logEnd
    sys.exit()
  }

  def buildTask() {
    taskServer.getAllTaskInfos().foreach(taskConf => {
      val taskId = taskConf.getId
      taskConf.getStatus match {
        case TaskConstant.PRE_START => {
          if (pre_start_tasks.contains(taskId)) {
            val start_time = pre_start_tasks.get(taskId).get
            if (start_time + startTimeOutSeconds * 1000 <= System.currentTimeMillis()) {
              taskServer.stopTask(taskId)
              pre_start_tasks.remove(taskId)
              logInfo("Task " + taskId + " prepare start " + startTimeOutSeconds + " s has time out , stop it ! please check MainFrameManager log message !")
            }
          } else {
            // create the task actor to submit a new app and shutdown itself
            val task = system.actorOf(Props[Task], name = "task_" + taskId + "time_" +System.currentTimeMillis())
            task ! makeCMD(taskConf)
            pre_start_tasks.put(taskId, System.currentTimeMillis())
            if (MainFrameConf.systemProps.getBoolean(MainFrameConf.MONITOR_TASK_MONITOR_ENABLE, false)){
              MonitorUtils.updateTaskStatisticsHistoryStatus(taskId)
            }
            logInfo("Task " + taskId + " prepare to start !")
          }
        }

        case TaskConstant.RUNNING => {
          checkTaskMonitor(taskId)
          checkHearbeat(taskId,taskConf)
          if (pre_start_tasks.contains(taskId)) {
            pre_start_tasks.remove(taskId)
            logInfo("Task " + taskId + " start successfully !")
          }
        }

        case TaskConstant.PRE_STOP => {
          if (pre_stop_tasks.contains(taskId)) {
            val stop_time = pre_stop_tasks.get(taskId).get
            if (stop_time + startTimeOutSeconds * 1000 >= System.currentTimeMillis()) {
              //              taskServer.stopTask(taskId)
              pre_stop_tasks.remove(taskId)
              logInfo("Task " + taskId + " prepare stop " + startTimeOutSeconds + " s has time out , stop fail ! please check driver log message !")
            }
          } else {
            pre_stop_tasks.put(taskId, System.currentTimeMillis())
            logInfo("Task " + taskId + " prepare to stop !")
          }
        }

        case TaskConstant.STOP => {
          if (pre_stop_tasks.contains(taskId)) {
            pre_stop_tasks.remove(taskId)
            logInfo("stop Task " + taskId + "  successfully !")
          }

          if (pre_start_tasks.contains(taskId)) {
            pre_start_tasks.remove(taskId)
          }
        }

        case TaskConstant.RETRY => {

          if (pre_start_tasks.contains(taskId)) {
            pre_start_tasks.remove(taskId)
          }

          val start_time = taskConf.start_time
          val cur_retry = taskConf.retry
          val max_retry = taskConf.cur_retry
          if (cur_retry < max_retry) {
            val cur_time = System.currentTimeMillis()

            if (cur_time > start_time + RETRY_MIN_INTERVAL ) {
              taskServer.RestartTask(taskId)
              logInfo("Retry task " + taskId + " for " + cur_retry + " time!")

              if (cur_time > start_time + RETRY_RESET_TIMEDOUT ) {
                logInfo("task " + taskId + " had start long time before, reset try count")
                taskServer.updateRetry(taskId, 0)
              }
              else
                taskServer.updateRetry(taskId, cur_retry + 1)
            }

          } else {
            taskServer.stopTask(taskId)
            logInfo("task " + taskId + " retry for " + cur_retry + " times! Stop now!")
          }
        }

        case _ => logInfo("No task is need operate !")
      }

    })
  }

  def makeCMD(conf: TaskConf): TaskCommand = {
    val owner = conf.owner
    MainFrameConf.flushSystemProps
    val spark_home = MainFrameConf.systemProps.get("SPARK_HOME")
    var cmd = spark_home + "/bin/spark-submit "
    if (StringUtils.isNotEmpty(owner)){
      cmd = s"sudo -u ${owner} ${spark_home}/bin/spark-submit "
    }

    val deploy_mode = " --deploy-mode client"
    val master = " --master " + MainFrameConf.systemProps.get("master")

    var appJars = ""

    var jars = new StringBuilder(" --jars ")
    ListFileWalker(HiddenFileFilter.VISIBLE, FileFilterUtils.suffixFileFilter(".jar")).list(new File(CommonConstant.baseDir)).foreach(file =>{
      if (file.getName.startsWith("ocsp-core")){
        appJars = file.getAbsolutePath
      }
      jars.append(file.getAbsolutePath).append(",")
    })

    ListFileWalker(HiddenFileFilter.VISIBLE, FileFilterUtils.suffixFileFilter(".jar")).list(new File(CommonConstant.baseDir, "../web/uploads").getAbsoluteFile).foreach(file =>{
      jars.append(file.getAbsolutePath).append(",")
    })

    jars = jars.dropRight(1)

    if (StringUtils.isBlank(appJars)){
      logError("Can not find core jar")
    }

    val streamClass = " --class com.asiainfo.ocdp.stream.manager.StreamApp"
    val executor_memory = " --executor-memory " + conf.getExecutor_memory

    val driver_memory_value = if(StringUtils.isEmpty(conf.getDriver_memory)) "1g" else conf.getDriver_memory
    val driver_memory = " --driver-memory " + driver_memory_value

    val tid = conf.getId

    if (master.contains("spark")) {
      val total_executor_cores = " --total-executor-cores " + conf.getTotal_executor_cores
      var supervise = ""
      if (MainFrameConf.systemProps.get("supervise", "false").eq("true"))
        supervise = " --supervise "

			cmd += streamClass + master + deploy_mode + supervise + executor_memory + driver_memory + total_executor_cores + jars + " " + appJars + " " + tid
    } else if (master.contains("yarn")) {
      val num_executors = " --num-executors " + conf.getNum_executors
      var queue = " --queue "
      if (StringUtils.isNotEmpty(conf.getQueue)) {
        queue += conf.getQueue
      } else {
        //如果获取不到queue的配置那么就不添加--queue参数，yarn会自动分配任务到默认队列中
        logInfo("The value of queue is invalid, remove --queue parameter")
        queue = ""
      }
			cmd += streamClass + master + deploy_mode + executor_memory + driver_memory + num_executors + queue + jars + " " + appJars + " " + tid
    }
    logInfo("Executor submit shell : " + cmd)
    TaskCommand(tid, cmd)
  }

  private def checkTaskMonitor(taskId: String) = {
    if (MainFrameConf.systemProps.getBoolean(MainFrameConf.MONITOR_TASK_MONITOR_ENABLE, false)){
      val currentTime = System.currentTimeMillis()
      val intervalMins = MainFrameConf.systemProps.getLong("ocsp.monitor.task-monitor.retain-check-interval-mins", 2880L)

      if ((currentTime - lastCheckTime) > intervalMins * 60 * 1000) {
        logInfo(s"CurrentTime is ${DateFormatUtils.dateMs2Str(currentTime)} and lastCheckTime is ${DateFormatUtils.dateMs2Str(lastCheckTime)}")

        MonitorUtils.deleteTaskStatisticsHistory(MainFrameConf.systemProps.getLong("ocsp.monitor.task-monitor.retain-mins", 10080L))

        lastCheckTime = currentTime
      }
    }
  }

  private def checkHearbeat(taskId: String, taskConf: TaskConf) {
    val TASK_TIMEDOUT = (taskConf.receive_interval * 2 + 30)*1000
    val curTime = System.currentTimeMillis()
    val res = Try(taskServer.getHeartbeat(taskId))
    res match {
      case Success(heartbeat) => {
        if (curTime - heartbeat > TASK_TIMEDOUT) {
          taskServer.updateException(taskId, taskConf.appID, ExceptionConstant.ERR_SPARK_JOB_FINISHED, ExceptionConstant.getExceptionInfo(ExceptionConstant.ERR_SPARK_JOB_FINISHED))
        }
      }
      case Failure(e) => {
        logError("error to get task hearbeat from db " + e.getStackTrace)
      }
    }
  }
}

case class TaskCommand(taskId: String, cmd: String)
