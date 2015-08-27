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
package io.hops.ha.common;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import io.hops.StorageConnector;
import io.hops.exception.StorageException;
import io.hops.metadata.util.RMStorageFactory;
import io.hops.metadata.yarn.dal.AppSchedulingInfoBlacklistDataAccess;
import io.hops.metadata.yarn.dal.AppSchedulingInfoDataAccess;
import io.hops.metadata.yarn.dal.ContainerDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerAppLastScheduledContainerDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerAppLiveContainersDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerAppNewlyAllocatedContainersDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerAppReservationsDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerAppSchedulingOpportunitiesDataAccess;
import io.hops.metadata.yarn.dal.RMContainerDataAccess;
import io.hops.metadata.yarn.dal.ResourceDataAccess;
import io.hops.metadata.yarn.dal.ResourceRequestDataAccess;
import io.hops.metadata.yarn.dal.capacity.FiCaSchedulerAppReservedContainersDataAccess;
import io.hops.metadata.yarn.entity.AppSchedulingInfo;
import io.hops.metadata.yarn.entity.AppSchedulingInfoBlacklist;
import io.hops.metadata.yarn.entity.Container;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppLastScheduledContainer;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppContainer;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppReservedContainerInfo;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppSchedulingOpportunities;
import io.hops.metadata.yarn.entity.ToPersistContainersInfo;
import io.hops.metadata.yarn.entity.RMContainer;
import io.hops.metadata.yarn.entity.Resource;
import io.hops.metadata.yarn.entity.ResourceRequest;
import io.hops.metadata.yarn.entity.SchedulerAppReservations;
import io.hops.metadata.yarn.entity.capacity.FiCaSchedulerAppReservedContainers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.impl.pb.ContainerPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ResourceRequestPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.yarn.api.records.Priority;

/**
 * Contains data structures of FiCaSchedulerApp.
 */
public class FiCaSchedulerAppInfo {

  private static final Log LOG = LogFactory.getLog(FiCaSchedulerAppInfo.class);
  private final TransactionStateImpl transactionState;
  private AppSchedulingInfo fiCaSchedulerAppToAdd;
  private Map<Integer, Resource> resourcesToUpdate = new HashMap<Integer, Resource>();
  
  protected ApplicationAttemptId applicationAttemptId;
  private Map<ContainerId, ToPersistContainersInfo> liveContainersToAdd = new HashMap<ContainerId, ToPersistContainersInfo>();
  private Map<ContainerId, ToPersistContainersInfo> liveContainersToRemove = new HashMap<ContainerId, ToPersistContainersInfo>();
  private HashMap<ContainerId, ToPersistContainersInfo> newlyAllocatedContainersToAdd = new HashMap<ContainerId, ToPersistContainersInfo>();
  private HashMap< ContainerId, ToPersistContainersInfo> newlyAllocatedContainersToRemove = new HashMap<ContainerId, ToPersistContainersInfo>();

  private Set<ResourceRequest>
      requestsToAdd=new HashSet<ResourceRequest>();
  private Set<ResourceRequest>
      requestsToRemove=new HashSet<ResourceRequest>();

  private Map<Integer, Resource>
      toUpdateResources=new HashMap<Integer, Resource>(3);

  private Set<String> blacklistToAdd = new HashSet<String>();
  private Set<String> blacklistToRemove=new HashSet<String>();

  private boolean remove = false;

  private Map<ContainerId, FiCaSchedulerAppReservedContainerInfo> reservedContainersToAdd = new HashMap<ContainerId, FiCaSchedulerAppReservedContainerInfo>();
  private Map<ContainerId, FiCaSchedulerAppReservedContainerInfo> reservedContainersToRemove = new HashMap<ContainerId, FiCaSchedulerAppReservedContainerInfo>();

  private Map<Priority, Long> lastScheduledContainerToAdd;

  Multiset<Priority> schedulingOpportunitiesToAdd;
  Multiset<Priority> reReservations;

  // Not used by fifo scheduler
  public void persist(StorageConnector connector) throws StorageException {
    long start = System.currentTimeMillis();
    persistApplicationToAdd();
    //connector.flush();
//    long time1 = System.currentTimeMillis() -start;
    persistReservedContainersToAdd();
    
    persistReservedContainersToRemove();
    persistLastScheduledContainersToAdd();
    persistSchedulingOpportunitiesToAdd();
    persistReReservations();
    persistNewlyAllocatedContainersToAdd();
    persistNewlyAllocatedContainersToRemove();
    persistLiveContainersToAdd();
    persistLiveContainersToRemove();
    persistRequestsToAdd();
    persistRequestsToRemove();
    persistBlackListsToAdd();
    persistBlackListsToRemove();
    persistToUpdateResources(connector);
    persistRemoval();
  }

  public FiCaSchedulerAppInfo(ApplicationAttemptId applicationAttemptId, TransactionStateImpl transactionState) {
    this.transactionState = transactionState;
    this.applicationAttemptId = applicationAttemptId;
  }

  public FiCaSchedulerAppInfo(SchedulerApplicationAttempt schedulerApp, TransactionStateImpl transactionState) {
    this.transactionState = transactionState;
    this.applicationAttemptId = schedulerApp.getApplicationAttemptId();
    fiCaSchedulerAppToAdd = new AppSchedulingInfo(applicationAttemptId.
            toString(),
            applicationAttemptId.getApplicationId().toString(),
            schedulerApp.getQueueName(),
            schedulerApp.getUser(),
            schedulerApp.getLastContainerId(),
            schedulerApp.isPending(),
            schedulerApp.isStopped());

    //Persist Resources here
    if (schedulerApp.getCurrentConsumption() != null) {
      resourcesToUpdate.put(Resource.CURRENTCONSUMPTION, new Resource(
              applicationAttemptId.toString(),
              Resource.CURRENTCONSUMPTION, Resource.SCHEDULERAPPLICATIONATTEMPT,
              schedulerApp.getCurrentConsumption().getMemory(),
              schedulerApp.getCurrentConsumption().getVirtualCores(),0));
    }
    if (schedulerApp.getCurrentReservation() != null) {
      resourcesToUpdate.put(Resource.CURRENTRESERVATION, new Resource(
              applicationAttemptId.toString(),
              Resource.CURRENTRESERVATION, Resource.SCHEDULERAPPLICATIONATTEMPT,
              schedulerApp.getCurrentReservation().getMemory(),
              schedulerApp.getCurrentReservation().getVirtualCores(),0));
    }
    if (schedulerApp.getResourceLimit() != null) {
      resourcesToUpdate.put(Resource.RESOURCELIMIT, new Resource(
              applicationAttemptId.toString(),
              Resource.RESOURCELIMIT, Resource.SCHEDULERAPPLICATIONATTEMPT,
              schedulerApp.getResourceLimit().getMemory(),
              schedulerApp.getResourceLimit().getVirtualCores(),0));
    }
    if (!schedulerApp.getLiveContainers().isEmpty()) {
      for(org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer:
              schedulerApp.getLiveContainersMap().values()){
      liveContainersToAdd
              .put(rmContainer.getContainerId(), convertToToPersistContainersInfo(rmContainer));
      }
    }
    if (!schedulerApp.getLastScheduledContainer().isEmpty()) {
      lastScheduledContainerToAdd.putAll(
              schedulerApp.getLastScheduledContainer());
    }
    if (!schedulerApp.getAppSchedulingInfo().getBlackList()
            .isEmpty()) {
      blacklistToAdd.addAll(
              schedulerApp.getAppSchedulingInfo().getBlackList());
    }

    remove = false;
  }

  public void updateAppInfo(SchedulerApplicationAttempt schedulerApp) {
        this.applicationAttemptId = schedulerApp.getApplicationAttemptId();
        fiCaSchedulerAppToAdd =
          new AppSchedulingInfo(applicationAttemptId.toString(),
              applicationAttemptId.getApplicationId().toString(),
              schedulerApp.getQueueName(),
              schedulerApp.getUser(),
              schedulerApp.getLastContainerId(),
              schedulerApp.isPending(),
              schedulerApp.isStopped());
  }

  public void toUpdateResource(Integer type,
      org.apache.hadoop.yarn.api.records.Resource res) {

    
    toUpdateResources.put(type, new Resource(applicationAttemptId.toString(), type,
            Resource.SCHEDULERAPPLICATIONATTEMPT,
            res.getMemory(),
            res.getVirtualCores(),0));
  }

  public void setRequestsToAdd(
      org.apache.hadoop.yarn.api.records.ResourceRequest val) {
    ResourceRequest hopResourceRequest =
              new ResourceRequest(applicationAttemptId.toString(),
                  val.getPriority().getPriority(), val.getResourceName(),
                  ((ResourceRequestPBImpl) val).getProto().toByteArray());
    this.requestsToAdd.add(hopResourceRequest);
    requestsToRemove.remove(hopResourceRequest);
  }

  public void setRequestsToRemove(
      org.apache.hadoop.yarn.api.records.ResourceRequest val) {
    ResourceRequest hopResourceRequest =
              new ResourceRequest(applicationAttemptId.toString(),
                  val.getPriority().getPriority(), val.getResourceName(),
                  ((ResourceRequestPBImpl) val).getProto().toByteArray());
    if(!requestsToAdd.remove(hopResourceRequest)){
      this.requestsToRemove.add(hopResourceRequest);
    }
  }

  public void setBlacklistToRemove(List<String> blacklistToRemove) {
    if(!blacklistToAdd.remove(blacklistToRemove)){
      this.blacklistToRemove.addAll(blacklistToRemove);
    }
  }

  public void setBlacklistToAdd(Collection<String> set) {
    this.blacklistToAdd.addAll(set);
    this.blacklistToRemove.removeAll(set);
  }

  public void setLiveContainersToAdd(ContainerId key, org.apache.hadoop.yarn.
      server.resourcemanager.rmcontainer.RMContainer val) {
    this.liveContainersToAdd.put(key, convertToToPersistContainersInfo(val));
    transactionState.addRMContainerToUpdate((RMContainerImpl)val);
    liveContainersToRemove.remove(key);
  }

  private ToPersistContainersInfo convertToToPersistContainersInfo(
          org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer) {
    FiCaSchedulerAppContainer fiCaSchedulerAppContainer
            = new FiCaSchedulerAppContainer(
                    applicationAttemptId.toString(), rmContainer.
                    getContainerId().toString());

    RMContainer hopRMContainer = createRMContainer(rmContainer);

    Container hopContainer = new Container(rmContainer.getContainerId().
            toString(),
            getRMContainerBytes(rmContainer.getContainer()));
    LOG.debug("adding ha_container " + hopContainer.getContainerId());

    return new ToPersistContainersInfo(hopRMContainer, fiCaSchedulerAppContainer,
            hopContainer);
  }
  
  public void setLiveContainersToRemove(org.apache.hadoop.yarn.server.
      resourcemanager.rmcontainer.RMContainer rmc) {
    if(liveContainersToAdd.remove(rmc.getContainerId())==null){
      liveContainersToRemove.put(rmc.getContainerId(), convertToToPersistContainersInfo(rmc));
    }
    
  }

  private byte[] getRMContainerBytes(org.apache.hadoop.yarn.api.records.Container Container){
    if(Container instanceof ContainerPBImpl){
      return ((ContainerPBImpl) Container).getProto()
            .toByteArray();
    }else{
      return new byte[0];
    }
  }
  public void setNewlyAllocatedContainersToAdd(org.apache.hadoop.yarn.server.
      resourcemanager.rmcontainer.RMContainer rmContainer) {
    RMContainer hopRMContainer = createRMContainer(rmContainer);

    Container hopContainer = new Container(rmContainer.getContainerId().
            toString(),getRMContainerBytes(rmContainer.getContainer()));

    FiCaSchedulerAppContainer toAdd
            = new FiCaSchedulerAppContainer(
                    applicationAttemptId.toString(),
                    rmContainer.getContainerId().toString());

    ToPersistContainersInfo containerInfo
            = new ToPersistContainersInfo(hopRMContainer, toAdd,
                    hopContainer);

    transactionState.addRMContainerToUpdate((RMContainerImpl)rmContainer);
    this.newlyAllocatedContainersToAdd.put(rmContainer.getContainerId(),
            containerInfo);
    this.newlyAllocatedContainersToRemove.remove(rmContainer.getContainerId());
  }

  public void setNewlyAllocatedContainersToRemove(org.apache.hadoop.yarn.server.
      resourcemanager.rmcontainer.RMContainer rmContainer) {
    if (newlyAllocatedContainersToAdd.remove(rmContainer.getContainerId())
            == null) {
      FiCaSchedulerAppContainer toRemove
              = new FiCaSchedulerAppContainer(
                      applicationAttemptId.toString(), rmContainer.toString());
      ToPersistContainersInfo containerInfo
              = new ToPersistContainersInfo(null, toRemove, null);

      this.newlyAllocatedContainersToRemove.put(rmContainer.getContainerId(),
              containerInfo);
    }
  }

  private void persistRequestsToAdd() throws StorageException {
    if (requestsToAdd != null) {
      //Persist AppSchedulingInfo requests map and ResourceRequest
      ResourceRequestDataAccess resRequestDA =
          (ResourceRequestDataAccess) RMStorageFactory
              .getDataAccess(ResourceRequestDataAccess.class);
      List<ResourceRequest> toAddResourceRequests =
          new ArrayList<ResourceRequest>();
      for (ResourceRequest key : requestsToAdd) {
       
          
          LOG.debug(
              "adding ha_resourcerequest " + applicationAttemptId.toString());
          toAddResourceRequests.add(key);
        
      }
      resRequestDA.addAll(toAddResourceRequests);
    }
  }

  private void persistRequestsToRemove() throws StorageException {
    if (requestsToRemove != null && !requestsToRemove.isEmpty()) {
      //Remove AppSchedulingInfo requests map and ResourceRequest
      ResourceRequestDataAccess resRequestDA =
          (ResourceRequestDataAccess) RMStorageFactory
              .getDataAccess(ResourceRequestDataAccess.class);
      List<ResourceRequest> toRemoveResourceRequests =
          new ArrayList<ResourceRequest>();
      for (ResourceRequest key : requestsToRemove) {
        
        toRemoveResourceRequests.add(key);

      }
      resRequestDA.removeAll(toRemoveResourceRequests);
    }
  }

  private void persistApplicationToAdd() throws StorageException {
    if (fiCaSchedulerAppToAdd != null && !remove) {
      //Persist ApplicationAttemptId
      //Persist FiCaScheduler App - SchedulerApplicationAttempt instance of SchedulerApplication object
      //Also here we persist the appSchedulingInfo because it is created at the time the FiCaSchedulerApp object is created
      AppSchedulingInfoDataAccess asinfoDA =
          (AppSchedulingInfoDataAccess) RMStorageFactory
              .getDataAccess(AppSchedulingInfoDataAccess.class);
      ResourceDataAccess resourceDA = (ResourceDataAccess) RMStorageFactory
          .getDataAccess(ResourceDataAccess.class);

      LOG.debug("adding appscheduling info " + applicationAttemptId.toString() +
          " appid " + applicationAttemptId.getApplicationId().toString());
     
      asinfoDA.add(fiCaSchedulerAppToAdd);

      if (!resourcesToUpdate.isEmpty()) {
        
        resourceDA.addAll(resourcesToUpdate.values());
      }
    }
  }

  private void persistLiveContainersToAdd() throws StorageException {
    if (liveContainersToAdd != null) {
      //Persist LiveContainers
      RMContainerDataAccess rmcDA = (RMContainerDataAccess) RMStorageFactory
          .getDataAccess(RMContainerDataAccess.class);
      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory
          .getDataAccess(ContainerDataAccess.class);
      List<Container> toAddContainers = new ArrayList<Container>();
      FiCaSchedulerAppLiveContainersDataAccess fsalcDA =
          (FiCaSchedulerAppLiveContainersDataAccess) RMStorageFactory.
              getDataAccess(FiCaSchedulerAppLiveContainersDataAccess.class);
      List<FiCaSchedulerAppContainer> toAddLiveContainers =
          new ArrayList<FiCaSchedulerAppContainer>();
      for (ToPersistContainersInfo container : liveContainersToAdd.values()) {
          toAddLiveContainers.add(container.getFiCaSchedulerAppContainer());

          toAddContainers.add(container.getContainer());
      }
      cDA.addAll(toAddContainers);
      fsalcDA.addAll(toAddLiveContainers);
      
    }
  }

  private void persistLiveContainersToRemove() throws StorageException {
    if (liveContainersToRemove != null && !liveContainersToRemove.isEmpty()) {
      FiCaSchedulerAppLiveContainersDataAccess fsalcDA =
          (FiCaSchedulerAppLiveContainersDataAccess) RMStorageFactory.
              getDataAccess(FiCaSchedulerAppLiveContainersDataAccess.class);
      List<FiCaSchedulerAppContainer> toRemoveLiveContainers =
          new ArrayList<FiCaSchedulerAppContainer>();
      for (ToPersistContainersInfo container : liveContainersToRemove.values()) {
        toRemoveLiveContainers.add(container.getFiCaSchedulerAppContainer());
      }
      fsalcDA.removeAll(toRemoveLiveContainers);
    }
  }

  private void persistNewlyAllocatedContainersToAdd() throws StorageException {
    if (newlyAllocatedContainersToAdd != null) {
      //Persist NewllyAllocatedContainers list
      FiCaSchedulerAppNewlyAllocatedContainersDataAccess fsanDA =
          (FiCaSchedulerAppNewlyAllocatedContainersDataAccess) RMStorageFactory
              .getDataAccess(
                  FiCaSchedulerAppNewlyAllocatedContainersDataAccess.class);
      List<FiCaSchedulerAppContainer>
          toAddNewlyAllocatedContainersList =
          new ArrayList<FiCaSchedulerAppContainer>();
      //Persist RMContainers
      RMContainerDataAccess rmcDA = (RMContainerDataAccess) RMStorageFactory
          .getDataAccess(RMContainerDataAccess.class);
      //Persist Container
      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory
          .getDataAccess(ContainerDataAccess.class);
      List<Container> toAddContainers = new ArrayList<Container>();

      for (ToPersistContainersInfo rmContainer : newlyAllocatedContainersToAdd.values()) {
        toAddNewlyAllocatedContainersList.add(rmContainer.getFiCaSchedulerAppContainer());
        toAddContainers.add(rmContainer.getContainer());
      }
      fsanDA.addAll(toAddNewlyAllocatedContainersList);
      cDA.addAll(toAddContainers);
    }
  }

  private void persistNewlyAllocatedContainersToRemove()
      throws StorageException {
    if (newlyAllocatedContainersToRemove != null) {
      //Remove NewllyAllocatedContainers list
      FiCaSchedulerAppNewlyAllocatedContainersDataAccess fsanDA =
          (FiCaSchedulerAppNewlyAllocatedContainersDataAccess) RMStorageFactory
              .getDataAccess(
                  FiCaSchedulerAppNewlyAllocatedContainersDataAccess.class);
      List<FiCaSchedulerAppContainer>
          toRemoveNewlyAllocatedContainersList =
          new ArrayList<FiCaSchedulerAppContainer>();


      for (ToPersistContainersInfo rmContainer : 
              newlyAllocatedContainersToRemove.values()) {
        LOG.debug("remove newlyAllocatedContainers " +
            applicationAttemptId.toString() + " container " +
            rmContainer.toString());
        
        toRemoveNewlyAllocatedContainersList.add(rmContainer.getFiCaSchedulerAppContainer());
      }
      fsanDA.removeAll(toRemoveNewlyAllocatedContainersList);
    }
  }

  private void persistBlackListsToAdd() throws StorageException {
    if (blacklistToAdd != null) {
      AppSchedulingInfoBlacklistDataAccess blDA =
          (AppSchedulingInfoBlacklistDataAccess) RMStorageFactory.
              getDataAccess(AppSchedulingInfoBlacklistDataAccess.class);
      List<AppSchedulingInfoBlacklist> toAddblackListed =
          new ArrayList<AppSchedulingInfoBlacklist>();
      for (String blackList : blacklistToAdd) {
        if (!blacklistToRemove.remove(blackList)) {
          AppSchedulingInfoBlacklist hopBlackList =
              new AppSchedulingInfoBlacklist(applicationAttemptId.
                  toString(), blackList);
          LOG.debug("adding ha_appschedulinginfo_blacklist " +
              hopBlackList.getAppschedulinginfo_id());
          toAddblackListed.add(hopBlackList);
        }
      }
      blDA.addAll(toAddblackListed);
    }
  }

  private void persistBlackListsToRemove() throws StorageException {
    if (blacklistToRemove != null && !blacklistToRemove.isEmpty()) {
      AppSchedulingInfoBlacklistDataAccess blDA =
          (AppSchedulingInfoBlacklistDataAccess) RMStorageFactory.
              getDataAccess(AppSchedulingInfoBlacklistDataAccess.class);
      List<AppSchedulingInfoBlacklist> toRemoveblackListed =
          new ArrayList<AppSchedulingInfoBlacklist>();
      for (String blackList : blacklistToRemove) {
        AppSchedulingInfoBlacklist hopBlackList =
            new AppSchedulingInfoBlacklist(applicationAttemptId.
                toString(), blackList);
        LOG.debug("remove BlackLists " + hopBlackList);
        toRemoveblackListed.add(hopBlackList);
      }
      blDA.removeAll(toRemoveblackListed);
    }
  }

  private void persistToUpdateResources(StorageConnector connector) throws StorageException {
    if (toUpdateResources != null) {
      ResourceDataAccess resourceDA = (ResourceDataAccess) RMStorageFactory
          .getDataAccess(ResourceDataAccess.class);
      ArrayList<Resource> toAddResources = new ArrayList<Resource>();
      for (Resource resource : toUpdateResources.values()) {
        toAddResources.add(resource);
      }
      resourceDA.addAll(toAddResources);
    }
  }

  public void remove(SchedulerApplicationAttempt schedulerApp) {
    if (fiCaSchedulerAppToAdd == null) {
      remove = true;
      fiCaSchedulerAppToAdd = new AppSchedulingInfo(applicationAttemptId.
              toString());

      if (schedulerApp.getCurrentConsumption() != null) {
        resourcesToUpdate.put(Resource.CURRENTCONSUMPTION, new Resource(
                applicationAttemptId.toString(),
                Resource.CURRENTCONSUMPTION,
                Resource.SCHEDULERAPPLICATIONATTEMPT,
                schedulerApp.getCurrentConsumption().getMemory(),
                schedulerApp.getCurrentConsumption().getVirtualCores(),0));
      }
      if (schedulerApp.getCurrentReservation() != null) {
        resourcesToUpdate.put(Resource.CURRENTRESERVATION, new Resource(
                applicationAttemptId.toString(),
                Resource.CURRENTRESERVATION,
                Resource.SCHEDULERAPPLICATIONATTEMPT,
                schedulerApp.getCurrentReservation().getMemory(),
                schedulerApp.getCurrentReservation().getVirtualCores(),0));
      }
      if (schedulerApp.getResourceLimit() != null) {
        resourcesToUpdate.put(Resource.RESOURCELIMIT, new Resource(
                applicationAttemptId.toString(),
                Resource.RESOURCELIMIT, Resource.SCHEDULERAPPLICATIONATTEMPT,
                schedulerApp.getResourceLimit().getMemory(),
                schedulerApp.getResourceLimit().getVirtualCores(),0));
      }
    } else {
      fiCaSchedulerAppToAdd = null;
    }
  }

  private void persistRemoval() throws StorageException {
    if (remove) {
      AppSchedulingInfoDataAccess asinfoDA =
          (AppSchedulingInfoDataAccess) RMStorageFactory
              .getDataAccess(AppSchedulingInfoDataAccess.class);
      ResourceDataAccess resourceDA = (ResourceDataAccess) RMStorageFactory.
          getDataAccess(ResourceDataAccess.class);


      asinfoDA.remove(fiCaSchedulerAppToAdd);


      resourceDA.removeAll(resourcesToUpdate.values());
    }
  }

  
  private FiCaSchedulerAppReservedContainerInfo convertToFiCaSchedulerAppReservedContainerInfo(
    org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer){
    FiCaSchedulerAppReservedContainers fiCaContainer = new FiCaSchedulerAppReservedContainers(
                applicationAttemptId.toString(),
                rmContainer.getReservedPriority().getPriority(),
                rmContainer.getReservedNode().toString(),
                rmContainer.getContainerId().toString());

        boolean isReserved = (rmContainer.getReservedNode() != null)
                && (rmContainer.getReservedPriority() != null);

        String reservedNode = isReserved ? rmContainer.getReservedNode().
                toString() : null;
        int reservedPriority = isReserved ? rmContainer.getReservedPriority().
                getPriority() : Integer.MIN_VALUE;
        int reservedMemory = isReserved ? rmContainer.getReservedResource().
                getMemory() : 0;
        int reservedVCores = isReserved ? rmContainer.getReservedResource().
                getVirtualCores() : 0;

        //Persist rmContainer
        io.hops.metadata.yarn.entity.RMContainer hopRMContainer = new io.hops.metadata.yarn.entity.RMContainer(
                rmContainer.getContainerId().toString(),
                rmContainer.getApplicationAttemptId().toString(),
                rmContainer.getNodeId().toString(),
                rmContainer.getUser(),
                reservedNode,
                reservedPriority,
                reservedMemory,
                reservedVCores,
                rmContainer.getStartTime(),
                rmContainer.getFinishTime(),
                rmContainer.getState().toString(),
                ((RMContainerImpl) rmContainer).getContainerState().toString(),
                ((RMContainerImpl) rmContainer).getContainerExitStatus()
        );

//     
        Container hopContainer = new Container(rmContainer.getContainerId().
                toString(),
                getRMContainerBytes(rmContainer.getContainer()));
        
    
     return new FiCaSchedulerAppReservedContainerInfo(fiCaContainer,
                    hopRMContainer, hopContainer);
  }
  
  public void addReservedContainer(
          org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer) {
    
    
    
    FiCaSchedulerAppReservedContainerInfo reservedContainerInfo =
            convertToFiCaSchedulerAppReservedContainerInfo(rmContainer);
    
    reservedContainersToAdd.put(rmContainer.getContainerId(), reservedContainerInfo);
    transactionState.addRMContainerToUpdate((RMContainerImpl)rmContainer);
    reservedContainersToRemove.remove(rmContainer.getContainerId());
  }

  protected void persistReservedContainersToAdd() throws StorageException {
    if (reservedContainersToAdd != null) {
      FiCaSchedulerAppReservedContainersDataAccess reservedContDA
              = (FiCaSchedulerAppReservedContainersDataAccess) RMStorageFactory.
              getDataAccess(FiCaSchedulerAppReservedContainersDataAccess.class);
      List<FiCaSchedulerAppReservedContainers> toAddReservedContainers
              = new ArrayList<FiCaSchedulerAppReservedContainers>();

      RMContainerDataAccess rmcDA = (RMContainerDataAccess) RMStorageFactory.
              getDataAccess(RMContainerDataAccess.class);

      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory.
              getDataAccess(ContainerDataAccess.class);
      List<Container> toAddContainers = new ArrayList<Container>();

      for (FiCaSchedulerAppReservedContainerInfo rmContainer
              : reservedContainersToAdd.values()) {
        toAddReservedContainers.add(rmContainer.getFiCaContainer());
        toAddContainers.add(rmContainer.getHopContainer());
      }
      reservedContDA.addAll(toAddReservedContainers);
      cDA.addAll(toAddContainers);
    }
  }

  public void removeReservedContainer(
          org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer cont) {
    if(reservedContainersToAdd.remove(cont.getContainerId())==null){
          FiCaSchedulerAppReservedContainerInfo reservedContainerInfo =
            convertToFiCaSchedulerAppReservedContainerInfo(cont);
      reservedContainersToRemove.put(cont.getContainerId(),reservedContainerInfo);
    }
  }

  protected void persistReservedContainersToRemove() throws StorageException {
    if (reservedContainersToRemove != null && !reservedContainersToRemove.
            isEmpty()) {
      FiCaSchedulerAppReservedContainersDataAccess reservedContDA
              = (FiCaSchedulerAppReservedContainersDataAccess) RMStorageFactory.
              getDataAccess(FiCaSchedulerAppReservedContainersDataAccess.class);
      List<FiCaSchedulerAppReservedContainers> toRemoveReservedContainers
              = new ArrayList<FiCaSchedulerAppReservedContainers>();

      RMContainerDataAccess rmcDA = (RMContainerDataAccess) RMStorageFactory.
              getDataAccess(RMContainerDataAccess.class);
      List<io.hops.metadata.yarn.entity.RMContainer> toRemoveRMContainers
              = new ArrayList<io.hops.metadata.yarn.entity.RMContainer>();

      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory.
              getDataAccess(ContainerDataAccess.class);

      for (FiCaSchedulerAppReservedContainerInfo rmContainer
              : reservedContainersToRemove.values()) {
        //Remove reservedContainers
        toRemoveReservedContainers.add(rmContainer.getFiCaContainer());

        
        toRemoveRMContainers.add(rmContainer.getHopRMContainer());

      }
      reservedContDA.removeAll(toRemoveReservedContainers);
      rmcDA.removeAll(toRemoveRMContainers);
    }
  }

  public void addLastScheduledContainer(Priority p, long time) {
    if (lastScheduledContainerToAdd == null) {
      lastScheduledContainerToAdd = new HashMap<Priority, Long>();
    }
    lastScheduledContainerToAdd.put(p, time);
  }

  protected void persistLastScheduledContainersToAdd() throws StorageException {
    if (lastScheduledContainerToAdd != null) {
      FiCaSchedulerAppLastScheduledContainerDataAccess lsDA
              = (FiCaSchedulerAppLastScheduledContainerDataAccess) RMStorageFactory.
              getDataAccess(
                      FiCaSchedulerAppLastScheduledContainerDataAccess.class);
      List<FiCaSchedulerAppLastScheduledContainer> toAddLastScheduledCont
              = new ArrayList<FiCaSchedulerAppLastScheduledContainer>();

      for (Priority p : lastScheduledContainerToAdd.keySet()) {
        //Persist lastScheduledContainers
        toAddLastScheduledCont.add(new FiCaSchedulerAppLastScheduledContainer(
                applicationAttemptId.toString(), p.getPriority(),
                lastScheduledContainerToAdd.get(p)));
      }
      lsDA.addAll(toAddLastScheduledCont);
    }
  }

  public void addSchedulingOppurtunity(Priority p, int count) {
    if (schedulingOpportunitiesToAdd == null) {
      schedulingOpportunitiesToAdd = HashMultiset.create();
    }
    schedulingOpportunitiesToAdd.setCount(p, count);
  }

  protected void persistSchedulingOpportunitiesToAdd() throws StorageException {
    if (schedulingOpportunitiesToAdd != null) {
      FiCaSchedulerAppSchedulingOpportunitiesDataAccess soDA
              = (FiCaSchedulerAppSchedulingOpportunitiesDataAccess) RMStorageFactory.
              getDataAccess(
                      FiCaSchedulerAppSchedulingOpportunitiesDataAccess.class);
      List<FiCaSchedulerAppSchedulingOpportunities> toAddSO
              = new ArrayList<FiCaSchedulerAppSchedulingOpportunities>();

      for (Priority p : schedulingOpportunitiesToAdd.elementSet()) {
        toAddSO.add(new FiCaSchedulerAppSchedulingOpportunities(
                applicationAttemptId.toString(), p.
                getPriority(), schedulingOpportunitiesToAdd.count(p)));
      }
      soDA.addAll(toAddSO);
    }
  }

  public void addReReservation(Priority p) {
    if (reReservations == null) {
      reReservations = HashMultiset.create();
    }
    reReservations.add(p);
  }

  public void resetReReservations(Priority p) {
    if (reReservations != null) {
      reReservations.setCount(p, 0);
    }
  }

  protected void persistReReservations() throws StorageException {
    if (reReservations != null) {
      FiCaSchedulerAppReservationsDataAccess reservationsDA
              = (FiCaSchedulerAppReservationsDataAccess) RMStorageFactory.
              getDataAccess(FiCaSchedulerAppReservationsDataAccess.class);
      List<SchedulerAppReservations> toAddReservations
              = new ArrayList<SchedulerAppReservations>();

      for (Priority p : reReservations.elementSet()) {
        toAddReservations.add(new SchedulerAppReservations(applicationAttemptId.
                toString(), p.
                getPriority(), reReservations.count(p)));
      }
      reservationsDA.addAll(toAddReservations);
    }
  }

  private RMContainer createRMContainer(
          org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer) {

    boolean isReserved = (rmContainer.getReservedNode() != null)
            && (rmContainer.getReservedPriority() != null);
    String reservedNode = isReserved ? rmContainer.getReservedNode().
            toString() : null;
    int reservedPriority = isReserved ? rmContainer.getReservedPriority().
            getPriority() : Integer.MIN_VALUE;
    int reservedMemory = isReserved ? rmContainer.getReservedResource().
            getMemory() : 0;
    int reservedVCores = isReserved ? rmContainer.getReservedResource().
            getVirtualCores() : 0;

    RMContainer hopRMContainer = new RMContainer(rmContainer.getContainerId().
            toString(),
            rmContainer.getApplicationAttemptId().toString(),
            rmContainer.getNodeId().toString(), rmContainer.getUser(),
            reservedNode,
            reservedPriority,
            reservedMemory,
            reservedVCores,
            rmContainer.getStartTime(), rmContainer.getFinishTime(),
            rmContainer.getState().toString(),
            ((RMContainerImpl) rmContainer).getContainerState()
            .toString(),
            ((RMContainerImpl) rmContainer).getContainerExitStatus());
    return hopRMContainer;
  }
}
