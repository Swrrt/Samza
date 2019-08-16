/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.job.yarn

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.yarn.api.ApplicationConstants
import org.apache.hadoop.yarn.api.records.ApplicationId
import org.apache.samza.SamzaException
import org.apache.samza.config.JobConfig.Config2Job
import org.apache.samza.config.{Config, JobConfig, ShellCommandConfig, YarnConfig}
import org.apache.samza.job.ApplicationStatus.{SuccessfulFinish, UnsuccessfulFinish}
import org.apache.samza.job.{ApplicationStatus, StreamJob}
import org.apache.samza.serializers.model.SamzaObjectMapper
import org.apache.samza.util.{CoordinatorStreamUtil, Util}
import org.slf4j.LoggerFactory

/**
 * Starts the application manager
 */
class ScalableYarnJob(config: Config, hadoopConfig: Configuration) extends YarnJob(config, hadoopConfig) {
  
  override def buildAmCmd() =  {
    // figure out if we have framework is deployed into a separate location
    val fwkPath = config.get(JobConfig.SAMZA_FWK_PATH, "")
    var fwkVersion = config.get(JobConfig.SAMZA_FWK_VERSION)
    if (fwkVersion == null || fwkVersion.isEmpty()) {
      fwkVersion = "STABLE"
    }
    logger.info("Inside YarnJob: fwk_path is %s, ver is %s use it directly " format(fwkPath, fwkVersion))

    //var cmdExec = "./__package/bin/run-jc.sh" // default location
    var cmdExec = "./__package/bin/run-am.sh"

    if (!fwkPath.isEmpty()) {
      // if we have framework installed as a separate package - use it
      //cmdExec = fwkPath + "/" + fwkVersion + "/bin/run-jc.sh"
      cmdExec = fwkPath + "/" + fwkVersion + "/bin/run-am.sh"

      logger.info("Using FWK path: " + "export SAMZA_LOG_DIR=%s && ln -sfn %s logs && exec %s 1>logs/%s 2>logs/%s".
             format(ApplicationConstants.LOG_DIR_EXPANSION_VAR, ApplicationConstants.LOG_DIR_EXPANSION_VAR, cmdExec,
                    ApplicationConstants.STDOUT, ApplicationConstants.STDERR))

    }
    cmdExec
  }
}
