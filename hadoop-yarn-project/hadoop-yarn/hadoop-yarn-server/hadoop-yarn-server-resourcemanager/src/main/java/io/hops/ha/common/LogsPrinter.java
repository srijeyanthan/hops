/*
 * Copyright 2015 Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.hops.ha.common;

import io.hops.metadata.util.RMUtilities;
import io.hops.metadata.util.YarnAPIStorageFactory;
import io.hops.metadata.yarn.dal.AppSchedulingInfoBlacklistDataAccess;
import io.hops.metadata.yarn.dal.AppSchedulingInfoDataAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import static org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.LOG;

public class LogsPrinter implements Runnable {

    Map<String, List<Integer>> clusterJCounters= new HashMap<String, List<Integer>>();
    
    public LogsPrinter(){
      clusterJCounters.put("AppSchedulingInfoBlacklist add", new ArrayList<Integer>());
    }
    
    public void run() {
      while (true) {
        try{
          Thread.sleep(1000);
          String logout = "";
          int count = 0;
          LinkedBlockingQueue<String> logs = RMUtilities.getLogs();
          while(!logs.isEmpty()){
            logout = logout + logs.poll()+ ", ";
            count++;
          }
          count = count/2;
          String toPrint = "commit logs " + count + "|| " + RMUtilities.getCommitAvgDuration() + ", " + RMUtilities.getCommitAndQueueAvgDuration() + "\n avgt commit: " + RMUtilities.getavgt() + "\n" +
                  logout;
          LOG.info(toPrint);
          LOG.info(YarnAPIStorageFactory.printYarnState());
        }catch(InterruptedException e){
          LOG.error(e, e);
        }
      }
    }
    

  }