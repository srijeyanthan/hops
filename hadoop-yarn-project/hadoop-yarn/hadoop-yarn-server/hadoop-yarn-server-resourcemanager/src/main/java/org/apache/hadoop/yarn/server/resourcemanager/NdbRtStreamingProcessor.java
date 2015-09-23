/*
 * Copyright (C) 2015 hops.io.
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
package org.apache.hadoop.yarn.server.resourcemanager;
import java.util.List;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.util.ConverterUtils;

/**
 *
 * @author sri
 */
public class NdbRtStreamingProcessor implements Runnable {

  private static final Log LOG = LogFactory.getLog(
          NdbRtStreamingProcessor.class);
  private boolean running = false;
  private final RMContext context;
  private RMNode rmNode;
  public NdbRtStreamingProcessor(RMContext context) {
    this.context = context;
  }

  public void printStreamingRTComps(StreamingRTComps streamingRTComps) {
    List<org.apache.hadoop.yarn.api.records.ApplicationId> applicationIdList = streamingRTComps.getFinishedApp();
    for (org.apache.hadoop.yarn.api.records.ApplicationId appId : applicationIdList) {
      LOG.info("<Processor> Finished application : appid : " + appId.toString()+ "node id : "+streamingRTComps.getNodeId());
    }

    Set<org.apache.hadoop.yarn.api.records.ContainerId> containerIdList = streamingRTComps.getContainersToClean();
    for (org.apache.hadoop.yarn.api.records.ContainerId conId : containerIdList) {
      LOG.debug("<Processor> Containers to clean  containerid: " + conId.toString());
    }
    LOG.debug("RTReceived: " + streamingRTComps.getNodeId() + " nexthb: "+streamingRTComps.isNextHeartbeat());

  }

  @Override
  public void run() {
    running = true;
    while (running) {
      if (!context.getRMGroupMembershipService().isLeader()) {
        try {

          StreamingRTComps streamingRTComps = null;
          streamingRTComps = (StreamingRTComps) NdbRtStreamingReceiver.blockingRTQueue.take();
          if (streamingRTComps != null) {
            printStreamingRTComps(streamingRTComps);

            NodeId nodeId = ConverterUtils.toNodeId(streamingRTComps.getNodeId());
            rmNode = context.getActiveRMNodes().get(nodeId);
            if (rmNode != null) {
              rmNode.setContainersToCleanUp(streamingRTComps.getContainersToClean());
              rmNode.setAppsToCleanup(streamingRTComps.getFinishedApp());
              rmNode.setNextHeartBeat(streamingRTComps.isNextHeartbeat());
            }
          }
        } catch (InterruptedException ex) {
          LOG.error(ex, ex);
        }

      }
    }

  }

  public void stop() {
    running = false;
  }

}