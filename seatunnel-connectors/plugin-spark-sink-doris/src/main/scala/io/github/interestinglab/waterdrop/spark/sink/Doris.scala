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

package io.github.interestinglab.waterdrop.spark.sink

import java.util

import io.github.interestinglab.waterdrop.common.config.{CheckResult, TypesafeConfigUtils}
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import io.github.interestinglab.waterdrop.spark.SparkEnvironment
import io.github.interestinglab.waterdrop.spark.batch.SparkBatchSink
import org.apache.log4j.Logger
import org.apache.spark.sql.{Dataset, Row}

import scala.collection.{JavaConversions, mutable}
import scala.collection.mutable.ListBuffer

class Doris extends SparkBatchSink with Serializable {

  var apiUrl: String = _
  var batch_size: Int = 100
  var column_separator: String = "\t"
  var propertiesMap = new mutable.HashMap[String,String]()

  override def output(data: Dataset[Row], env: SparkEnvironment): Unit = {
    val user: String = config.getString(Config.USER)
    val password: String = config.getString(Config.PASSWORD)
    if (propertiesMap.contains(Config.COLUMN_SEPARATOR)) {
      column_separator =  propertiesMap(Config.COLUMN_SEPARATOR)
    }
    val sparkSession = env.getSparkSession
    import sparkSession.implicits._
    val dataFrame = data.map(x => x.toString().replaceAll("\\[|\\]", "").replace(",", column_separator))
    dataFrame.foreachPartition { partition =>
      var count: Int = 0
      val buffer = new ListBuffer[String]
      val dorisUtil = new DorisUtil(propertiesMap.toMap, apiUrl, user, password)
      for (message <- partition) {
        count += 1
        buffer += message
        if (count > batch_size) {
          dorisUtil.saveMessages(buffer.mkString("\n"))
          buffer.clear()
          count = 0
        }
      }
      dorisUtil.saveMessages(buffer.mkString("\n"))
    }
  }

  override def checkConfig(): CheckResult = {
    val requiredOptions = List(Config.HOST, Config.DATABASE, Config.TABLE_NAME,Config.USER,Config.PASSWORD)
    val nonExistsOptions = requiredOptions.map(optionName => (optionName, config.hasPath(optionName))).filter { p =>
      val (optionName, exists) = p
      !exists
    }
    if (nonExistsOptions.nonEmpty) {
      new CheckResult(false, "Please specify " + nonExistsOptions
        .map { option =>
          val (name, exists) = option
          "[" + name + "]"
        }.mkString(", ") + " as non-empty string"
      )
    } else if (config.hasPath(Config.USER) && !config.hasPath(Config.PASSWORD) || config.hasPath(Config.PASSWORD) && !config.hasPath(Config.USER)) {
      new CheckResult(false, Config.CHECK_USER_ERROR)
    } else {
      val host: String = config.getString(Config.HOST)
      val dataBase: String = config.getString(Config.DATABASE)
      val tableName: String = config.getString(Config.TABLE_NAME)
      this.apiUrl = s"http://$host/api/$dataBase/$tableName/_stream_load"
      if (TypesafeConfigUtils.hasSubConfig(config,Config.ARGS_PREFIX)) {
        val properties = TypesafeConfigUtils.extractSubConfig(config, Config.ARGS_PREFIX, false)
        val iterator = properties.entrySet().iterator()
        while (iterator.hasNext) {
          val map = iterator.next()
          val split = map.getKey.split("\\.")
          if (split.size == 2) {
            propertiesMap.put(split(1),String.valueOf(map.getValue.unwrapped))
          }
        }
      }
      new CheckResult(true,Config.CHECK_SUCCESS)
    }
  }

  override def prepare(prepareEnv: SparkEnvironment): Unit = {
    if (config.hasPath(Config.BULK_SIZE) && config.getInt(Config.BULK_SIZE) > 0) {
      batch_size = config.getInt(Config.BULK_SIZE)
    }
  }
}
