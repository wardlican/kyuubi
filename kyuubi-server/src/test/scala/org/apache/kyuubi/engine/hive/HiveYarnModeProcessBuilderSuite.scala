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
package org.apache.kyuubi.engine.hive

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.engine.deploy.yarn.EngineYarnModeSubmitter.{KYUUBI_ENGINE_DEPLOY_YARN_MODE_HADOOP_CONF_KEY, KYUUBI_ENGINE_DEPLOY_YARN_MODE_HIVE_CONF_KEY, KYUUBI_ENGINE_DEPLOY_YARN_MODE_YARN_CONF_KEY}
import org.apache.kyuubi.engine.hive.HiveProcessBuilder.HIVE_HADOOP_CLASSPATH_KEY

class HiveYarnModeProcessBuilderSuite extends KyuubiFunSuite {

  test("hive yarn mode process builder") {
    val conf = KyuubiConf().set("kyuubi.on", "off")
    val builder = new HiveYarnModeProcessBuilder("kyuubi", conf, "") {
      override def env: Map[String, String] =
        super.env + ("HIVE_CONF_DIR" -> "/etc/hive/conf") + (HIVE_HADOOP_CLASSPATH_KEY -> "/hadoop")
    }
    val commands = builder.toString.split('\n')
    assert(commands.head.contains("bin/java"), "wrong exec")
    assert(builder.toString.contains("--conf kyuubi.session.user=kyuubi"))
    assert(commands.exists(ss => ss.contains("kyuubi-hive-sql-engine")), "wrong classpath")
    assert(builder.toString.contains("--conf kyuubi.on=off"))
    assert(builder.toString.contains(
      s"--conf $KYUUBI_ENGINE_DEPLOY_YARN_MODE_HIVE_CONF_KEY=/etc/hive/conf"))
  }

  test("hadoop conf dir") {
    val conf = KyuubiConf().set("kyuubi.on", "off")
    val builder = new HiveYarnModeProcessBuilder("kyuubi", conf, "") {
      override def env: Map[String, String] =
        super.env + ("HADOOP_CONF_DIR" -> "/etc/hadoop/conf") +
          (HIVE_HADOOP_CLASSPATH_KEY -> "/hadoop")
    }
    assert(builder.toString.contains(
      s"--conf $KYUUBI_ENGINE_DEPLOY_YARN_MODE_HADOOP_CONF_KEY=/etc/hadoop/conf"))
  }

  test("yarn conf dir") {
    val conf = KyuubiConf().set("kyuubi.on", "off")
    val builder = new HiveYarnModeProcessBuilder("kyuubi", conf, "") {
      override def env: Map[String, String] =
        super.env + ("YARN_CONF_DIR" -> "/etc/hadoop/yarn/conf") +
          (HIVE_HADOOP_CLASSPATH_KEY -> "/hadoop")
    }
    assert(builder.toString.contains(
      s"--conf $KYUUBI_ENGINE_DEPLOY_YARN_MODE_YARN_CONF_KEY=/etc/hadoop/yarn/conf"))
  }
}
