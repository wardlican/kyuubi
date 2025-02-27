/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.spark.session

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import org.apache.hadoop.fs.Path
import org.apache.spark.api.python.KyuubiPythonGatewayServer
import org.apache.spark.sql.SparkSession

import org.apache.kyuubi.KyuubiSQLException
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_SESSION_HANDLE_KEY
import org.apache.kyuubi.engine.ShareLevel
import org.apache.kyuubi.engine.ShareLevel._
import org.apache.kyuubi.engine.spark.{KyuubiSparkUtil, SparkSQLEngine}
import org.apache.kyuubi.engine.spark.KyuubiSparkUtil.engineId
import org.apache.kyuubi.engine.spark.operation.SparkSQLOperationManager
import org.apache.kyuubi.session._
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.TProtocolVersion
import org.apache.kyuubi.util.ThreadUtils
import org.apache.kyuubi.util.ThreadUtils.scheduleTolerableRunnableWithFixedDelay

/**
 * A [[SessionManager]] constructed with [[SparkSession]] which give it the ability to talk with
 * Spark and let Spark do all the rest heavy work :)
 *
 *  @param name Service Name
 * @param spark A [[SparkSession]] instance that this [[SessionManager]] holds to create individual
 *              [[SparkSession]] for [[org.apache.kyuubi.session.Session]]s.
 */
class SparkSQLSessionManager private (name: String, spark: SparkSession)
  extends SessionManager(name) {

  def this(spark: SparkSession) = this(classOf[SparkSQLSessionManager].getSimpleName, spark)

  val operationManager = new SparkSQLOperationManager()

  private lazy val singleSparkSession = conf.get(ENGINE_SINGLE_SPARK_SESSION)
  private lazy val shareLevel = ShareLevel.withName(conf.get(ENGINE_SHARE_LEVEL))

  private lazy val userIsolatedSparkSession = conf.get(ENGINE_USER_ISOLATED_SPARK_SESSION)
  private lazy val userIsolatedIdleInterval =
    conf.get(ENGINE_USER_ISOLATED_SPARK_SESSION_IDLE_INTERVAL)
  private lazy val userIsolatedIdleTimeout =
    conf.get(ENGINE_USER_ISOLATED_SPARK_SESSION_IDLE_TIMEOUT)
  private val userIsolatedCacheLock = new Object
  private lazy val userIsolatedCache = new java.util.HashMap[String, SparkSession]()
  private lazy val userIsolatedCacheCount =
    new java.util.HashMap[String, (Integer, java.lang.Long)]()
  private var userIsolatedSparkSessionThread: Option[ScheduledExecutorService] = None

  private def startUserIsolatedCacheChecker(): Unit = {
    if (!userIsolatedSparkSession) {
      userIsolatedSparkSessionThread =
        Some(ThreadUtils.newDaemonSingleThreadScheduledExecutor("user-isolated-cache-checker"))
      userIsolatedSparkSessionThread.foreach { thread =>
        scheduleTolerableRunnableWithFixedDelay(
          thread,
          () => {
            userIsolatedCacheLock.synchronized {
              val iter = userIsolatedCacheCount.entrySet().iterator()
              while (iter.hasNext) {
                val kv = iter.next()
                if (kv.getValue._1 == 0 &&
                  kv.getValue._2 + userIsolatedIdleTimeout < System.currentTimeMillis()) {
                  userIsolatedCache.remove(kv.getKey)
                  iter.remove()
                }
              }
            }
          },
          userIsolatedIdleInterval,
          userIsolatedIdleInterval,
          TimeUnit.MILLISECONDS)
      }
    }
  }

  override def start(): Unit = {
    startUserIsolatedCacheChecker()
    super.start()
  }

  override def stop(): Unit = {
    super.stop()
    KyuubiPythonGatewayServer.shutdown()
    userIsolatedSparkSessionThread.foreach(_.shutdown())
  }

  private def getOrNewSparkSession(user: String): SparkSession = {
    if (singleSparkSession) {
      spark
    } else {
      shareLevel match {
        // it's unnecessary to create a new spark session in connection share level
        // since the session is only one
        case CONNECTION => spark
        case USER => newSparkSession(spark)
        case GROUP | SERVER if userIsolatedSparkSession => newSparkSession(spark)
        case GROUP | SERVER =>
          userIsolatedCacheLock.synchronized {
            if (userIsolatedCache.containsKey(user)) {
              val (count, _) = userIsolatedCacheCount.get(user)
              userIsolatedCacheCount.put(user, (count + 1, System.currentTimeMillis()))
              userIsolatedCache.get(user)
            } else {
              userIsolatedCacheCount.put(user, (1, System.currentTimeMillis()))
              val newSession = newSparkSession(spark)
              userIsolatedCache.put(user, newSession)
              newSession
            }
          }
      }
    }
  }

  private def newSparkSession(rootSparkSession: SparkSession): SparkSession = {
    val newSparkSession = rootSparkSession.newSession()
    KyuubiSparkUtil.initializeSparkSession(
      newSparkSession,
      conf.get(ENGINE_SESSION_SPARK_INITIALIZE_SQL))
    newSparkSession
  }

  override protected def createSession(
      protocol: TProtocolVersion,
      user: String,
      password: String,
      ipAddress: String,
      conf: Map[String, String]): Session = {
    conf.get(KYUUBI_SESSION_HANDLE_KEY).map(SessionHandle.fromUUID).flatMap(
      getSessionOption).getOrElse {
      val sparkSession =
        try {
          getOrNewSparkSession(user)
        } catch {
          case e: Exception => throw KyuubiSQLException(e)
        }

      new SparkSessionImpl(
        protocol,
        user,
        password,
        ipAddress,
        conf,
        this,
        sparkSession)
    }
  }

  override def closeSession(sessionHandle: SessionHandle): Unit = {
    if (!userIsolatedSparkSession) {
      val session = getSession(sessionHandle)
      if (session != null) {
        userIsolatedCacheLock.synchronized {
          if (userIsolatedCacheCount.containsKey(session.user)) {
            val (count, _) = userIsolatedCacheCount.get(session.user)
            userIsolatedCacheCount.put(session.user, (count - 1, System.currentTimeMillis()))
          }
        }
      }
    }
    try {
      super.closeSession(sessionHandle)
    } catch {
      case e: KyuubiSQLException =>
        warn(s"Error closing session ${sessionHandle}", e)
    }
    if (shareLevel == ShareLevel.CONNECTION) {
      info("Session stopped due to shared level is Connection.")
      stopSession()
    }
    if (conf.get(OPERATION_RESULT_SAVE_TO_FILE)) {
      val path = new Path(s"${conf.get(OPERATION_RESULT_SAVE_TO_FILE_DIR)}/" +
        s"$engineId/${sessionHandle.identifier}")
      path.getFileSystem(spark.sparkContext.hadoopConfiguration).delete(path, true)
      info(s"Delete session result file $path")
    }
  }

  private def stopSession(): Unit = {
    SparkSQLEngine.currentEngine.foreach(_.stop())
  }

  override protected def isServer: Boolean = false
}
