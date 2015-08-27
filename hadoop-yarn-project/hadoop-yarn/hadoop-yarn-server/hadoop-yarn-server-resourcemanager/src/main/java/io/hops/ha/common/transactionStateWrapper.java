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

import io.hops.StorageConnector;
import io.hops.exception.StorageException;
import io.hops.metadata.yarn.dal.ContainerIdToCleanDataAccess;
import io.hops.metadata.yarn.dal.ContainerStatusDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerNodeDataAccess;
import io.hops.metadata.yarn.dal.FinishedApplicationsDataAccess;
import io.hops.metadata.yarn.dal.JustLaunchedContainersDataAccess;
import io.hops.metadata.yarn.dal.LaunchedContainersDataAccess;
import io.hops.metadata.yarn.dal.NodeDataAccess;
import io.hops.metadata.yarn.dal.NodeHBResponseDataAccess;
import io.hops.metadata.yarn.dal.PendingEventDataAccess;
import io.hops.metadata.yarn.dal.QueueMetricsDataAccess;
import io.hops.metadata.yarn.dal.RMContainerDataAccess;
import io.hops.metadata.yarn.dal.RMContextInactiveNodesDataAccess;
import io.hops.metadata.yarn.dal.RMNodeDataAccess;
import io.hops.metadata.yarn.dal.ResourceDataAccess;
import io.hops.metadata.yarn.dal.UpdatedContainerInfoDataAccess;
import io.hops.metadata.yarn.dal.capacity.CSLeafQueueUserInfoDataAccess;
import io.hops.metadata.yarn.dal.capacity.CSQueueDataAccess;
import io.hops.metadata.yarn.dal.fair.FSSchedulerNodeDataAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.server.resourcemanager.ApplicationMasterService;
import static org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.LOG;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;

public class transactionStateWrapper extends TransactionStateImpl {

  private final TransactionStateImpl ts;
  private final AtomicInteger rpcCounter = new AtomicInteger(0);
  int rpcId;
  long startTime = System.currentTimeMillis();
  String rpcType;
  Map<String, Long> handleStarts = new HashMap<String, Long>();
  Map<String, Long> handleDurations = new HashMap<String, Long>();
  Map<Integer, Long> timeInit = new HashMap<Integer, Long>();
  
  public transactionStateWrapper(TransactionStateImpl ts, 
          TransactionType type) {
    super(type);
    this.ts = ts;
  }

  public void addTime(int i){
    timeInit.put(i, System.currentTimeMillis()-startTime);
  }
  public synchronized void incCounter(Enum type) {
    handleStarts.put(type.name(), System.currentTimeMillis());
    ts.incCounter(type);
    rpcCounter.incrementAndGet();
  }
  
  private String printDetailedDurations(){
    String durations = " (";
      for(String types: handleDurations.keySet()){
        durations= durations + types + ": " + handleDurations.get(types).toString() + ",";
      }
      durations = durations + ") ";
      
      for(int i: timeInit.keySet()){
        durations= durations + i + " " + timeInit.get(i) + ", ";
      }
      return durations;
  }
  public synchronized void decCounter(Enum type) throws IOException {
    int val = rpcCounter.decrementAndGet();
    handleDurations.put(type.name(), System.currentTimeMillis() - handleStarts.get(type.name()));
    if (val == 0) {
      long duration = System.currentTimeMillis() - startTime;
      
      if (duration > 100) {
        LOG.info("finishing rpc >100: " + rpcId + " "
                + rpcType + " after " + duration + printDetailedDurations());
      }
//      else if (duration > 10) {
//        LOG.info("finishing rpc >10: "  + rpcId + " "
//                + rpcType + " after " + duration + printDetailedDurations());
//      } else {
//        LOG.info("finishing rpc: "  + rpcId + " " + rpcType
//                + " after " + duration + printDetailedDurations());
//      }
    }
    ts.decCounter(type);
  }


  public int getCounter() {
    return ts.getCounter();
  }

  public int getRPCID(){
    return rpcId;
  }
  
  public void addRPCId(int rpcId, String callingFuncition){
    this.rpcId = rpcId;
    rpcType=callingFuncition;
    ts.addRPCId(rpcId, callingFuncition);
  }

  @Override
  public void commit(boolean first) throws IOException {
    ts.commit(first);
  }

  @Override
  public void addAppId(ApplicationId appId) {
    ts.addAppId(appId);
  }

  public FairSchedulerNodeInfo getFairschedulerNodeInfo() {
    return ts.getFairschedulerNodeInfo();
  }

  public void persistFairSchedulerNodeInfo(FSSchedulerNodeDataAccess FSSNodeDA)
          throws StorageException {
    ts.persistFairSchedulerNodeInfo(FSSNodeDA);
  }

  public SchedulerApplicationInfo getSchedulerApplicationInfos(
          ApplicationId appId) {
    addAppId(appId);
    return ts.getSchedulerApplicationInfos(appId);
  }

  public void persist() throws IOException {
    ts.persist();
  }

  public void persistSchedulerApplicationInfo(QueueMetricsDataAccess QMDA,
          StorageConnector connector)
          throws StorageException {
    ts.persistSchedulerApplicationInfo(QMDA, connector);
  }

  public CSQueueInfo getCSQueueInfo() {
    return ts.getCSQueueInfo();
  }

  public void persistCSQueueInfo(CSQueueDataAccess CSQDA,
          CSLeafQueueUserInfoDataAccess csLQUIDA) throws StorageException {

    ts.persistCSQueueInfo(CSQDA, csLQUIDA);
  }

  public FiCaSchedulerNodeInfoToUpdate getFicaSchedulerNodeInfoToUpdate(
          String nodeId) {
    return ts.getFicaSchedulerNodeInfoToUpdate(nodeId);
  }

  public void addFicaSchedulerNodeInfoToAdd(String nodeId,
          org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode node) {

    ts.addFicaSchedulerNodeInfoToAdd(nodeId, node);
  }

  public void addFicaSchedulerNodeInfoToRemove(String nodeId,
          org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode node) {
    ts.addFicaSchedulerNodeInfoToRemove(nodeId, node);
  }

  public void addApplicationToAdd(RMAppImpl app) {
    ts.addApplicationToAdd(app);
  }

  public void addApplicationStateToRemove(ApplicationId appId) {
    ts.addApplicationStateToRemove(appId);
  }

  public void addAppAttempt(RMAppAttempt appAttempt) {
    ts.addAppAttempt(appAttempt);
  }

  public void addAllocateResponse(ApplicationAttemptId id,
          ApplicationMasterService.AllocateResponseLock allocateResponse) {
    ts.addAllocateResponse(id, allocateResponse);
  }

  public void removeAllocateResponse(ApplicationAttemptId id) {
    ts.removeAllocateResponse(id);
  }

  public void addRMContainerToUpdate(RMContainerImpl rmContainer) {
    ts.addRMContainerToUpdate(rmContainer);
  }

  public void persistFicaSchedulerNodeInfo(ResourceDataAccess resourceDA,
          FiCaSchedulerNodeDataAccess ficaNodeDA,
          RMContainerDataAccess rmcontainerDA,
          LaunchedContainersDataAccess launchedContainersDA)
          throws StorageException {
    ts.persistFicaSchedulerNodeInfo(resourceDA, ficaNodeDA, rmcontainerDA,
            launchedContainersDA);
  }

  public RMContextInfo getRMContextInfo() {
    return ts.getRMContextInfo();
  }

  public void persistRmcontextInfo(RMNodeDataAccess rmnodeDA,
          ResourceDataAccess resourceDA, NodeDataAccess nodeDA,
          RMContextInactiveNodesDataAccess rmctxinactivenodesDA)
          throws StorageException {
    ts.persistRmcontextInfo(rmnodeDA, resourceDA, nodeDA, rmctxinactivenodesDA);
  }

  public void persistRMNodeToUpdate(RMNodeDataAccess rmnodeDA)
          throws StorageException {
    ts.persistRMNodeToUpdate(rmnodeDA);
  }

  public void toUpdateRMNode(
          org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode rmnodeToAdd) {
    ts.toUpdateRMNode(rmnodeToAdd);
  }

  public RMNodeInfo getRMNodeInfo(NodeId rmNodeId) {
    return ts.getRMNodeInfo(rmNodeId);
  }

  public void persistRMNodeInfo(NodeHBResponseDataAccess hbDA,
          ContainerIdToCleanDataAccess cidToCleanDA,
          JustLaunchedContainersDataAccess justLaunchedContainersDA,
          UpdatedContainerInfoDataAccess updatedContainerInfoDA,
          FinishedApplicationsDataAccess faDA, ContainerStatusDataAccess csDA,PendingEventDataAccess persistedEventsDA)
          throws StorageException {
    ts.persistRMNodeInfo(hbDA, cidToCleanDA, justLaunchedContainersDA,
            updatedContainerInfoDA, faDA, csDA,persistedEventsDA);
  }

  public void updateUsedResource(
          org.apache.hadoop.yarn.api.records.Resource usedResource) {
    ts.updateUsedResource(usedResource);
  }

  public void updateClusterResource(
          org.apache.hadoop.yarn.api.records.Resource clusterResource) {
    ts.updateClusterResource(clusterResource);
  }

  public void persistFiCaSchedulerNodeToAdd(ResourceDataAccess resourceDA,
          FiCaSchedulerNodeDataAccess ficaNodeDA,
          RMContainerDataAccess rmcontainerDA,
          LaunchedContainersDataAccess launchedContainersDA)
          throws StorageException {
    ts.persistFiCaSchedulerNodeToAdd(resourceDA, ficaNodeDA, rmcontainerDA,
            launchedContainersDA);
  }

  public void addPendingEventToAdd(String rmnodeId, byte type, byte status) {
    ts.addPendingEventToAdd(rmnodeId, type, status);
  }

  public void addPendingEventToAdd(String rmnodeId, byte type, byte status,
          RMNodeImpl rmNode) {
    ts.addPendingEventToAdd(rmnodeId, type, status, rmNode);
  }

  public RMNodeImpl getRMNode() {
    return ts.getRMNode();
  }

  /**
   * Remove pending event from DB. In this case, the event id is not needed,
   * hence set to MIN.
   * <p/>
   *
   * @param id
   * @param rmnodeId
   * @param type
   * @param status
   */
  public void addPendingEventToRemove(int id, String rmnodeId, byte type,
          byte status) {
    ts.addPendingEventToRemove(id, rmnodeId, type, status);
  }

  public void persistPendingEvents(PendingEventDataAccess persistedEventsDA)
          throws StorageException {
    ts.persistPendingEvents(persistedEventsDA);
  }
}
