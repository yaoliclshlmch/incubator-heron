/**
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

package org.apache.heron.packing.roundrobin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import org.apache.heron.api.generated.TopologyAPI;
import org.apache.heron.api.utils.TopologyUtils;
import org.apache.heron.common.basics.ByteAmount;
import org.apache.heron.common.utils.topology.TopologyTests;
import org.apache.heron.packing.AssertPacking;
import org.apache.heron.spi.common.Config;
import org.apache.heron.spi.packing.PackingException;
import org.apache.heron.spi.packing.PackingPlan;
import org.apache.heron.spi.packing.Resource;
import org.apache.heron.spi.utils.PackingTestUtils;

public class RoundRobinPackingTest {
  private static final String BOLT_NAME = "bolt";
  private static final String SPOUT_NAME = "spout";
  private static final double DELTA = 0.1;

  private TopologyAPI.Topology getTopology(
      int spoutParallelism, int boltParallelism,
      org.apache.heron.api.Config topologyConfig) {
    return TopologyTests.createTopology("testTopology", topologyConfig, SPOUT_NAME, BOLT_NAME,
        spoutParallelism, boltParallelism);
  }

  private PackingPlan getRoundRobinPackingPlan(TopologyAPI.Topology topology) {
    Config config = PackingTestUtils.newTestConfig(topology);

    RoundRobinPacking packing = new RoundRobinPacking();
    packing.initialize(config, topology);
    return packing.pack();
  }

  private PackingPlan getRoundRobinRePackingPlan(
      TopologyAPI.Topology topology, Map<String, Integer> componentChanges) {
    Config config = PackingTestUtils.newTestConfig(topology);

    RoundRobinPacking packing = new RoundRobinPacking();
    packing.initialize(config, topology);
    PackingPlan pp = packing.pack();
    return packing.repack(pp, componentChanges);
  }

  @Test(expected = PackingException.class)
  public void testCheckFailure() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    // Explicit set insufficient ram for container
    ByteAmount containerRam = ByteAmount.fromGigabytes(0);

    topologyConfig.setContainerRamRequested(containerRam);

    TopologyAPI.Topology topology =  getTopology(spoutParallelism, boltParallelism, topologyConfig);
    getRoundRobinPackingPlan(topology);
  }

  @Test
  public void testDefaultResources() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;
    Integer totalInstances = spoutParallelism + boltParallelism;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    // No explicit resources required
    TopologyAPI.Topology topologyNoExplicitResourcesConfig =
        getTopology(spoutParallelism, boltParallelism, topologyConfig);
    PackingPlan packingPlanNoExplicitResourcesConfig =
        getRoundRobinPackingPlan(topologyNoExplicitResourcesConfig);

    Assert.assertEquals(numContainers,
        packingPlanNoExplicitResourcesConfig.getContainers().size());
    Assert.assertEquals(totalInstances, packingPlanNoExplicitResourcesConfig.getInstanceCount());
  }

  /**
   * Test the scenario container level resource config are set
   */
  @Test
  public void testContainerRequestedResources() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;
    Integer totalInstances = spoutParallelism + boltParallelism;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);
    // Explicit set resources for container
    ByteAmount containerRam = ByteAmount.fromGigabytes(10);
    ByteAmount containerDisk = ByteAmount.fromGigabytes(20);
    double containerCpu = 30;

    topologyConfig.setContainerRamRequested(containerRam);
    topologyConfig.setContainerDiskRequested(containerDisk);
    topologyConfig.setContainerCpuRequested(containerCpu);
    TopologyAPI.Topology topologyExplicitResourcesConfig =
        getTopology(spoutParallelism, boltParallelism, topologyConfig);
    PackingPlan packingPlanExplicitResourcesConfig =
        getRoundRobinPackingPlan(topologyExplicitResourcesConfig);

    Assert.assertEquals(numContainers,
        packingPlanExplicitResourcesConfig.getContainers().size());
    Assert.assertEquals(totalInstances, packingPlanExplicitResourcesConfig.getInstanceCount());

    for (PackingPlan.ContainerPlan containerPlan
        : packingPlanExplicitResourcesConfig.getContainers()) {
      Assert.assertEquals(containerCpu, containerPlan.getRequiredResource().getCpu(), DELTA);
      Assert.assertTrue(String.format(// due to round-off when using divide()
          "expected: %s but was: %s", containerRam, containerPlan.getRequiredResource().getRam()),
          Math.abs(
              containerRam.minus(containerPlan.getRequiredResource().getRam()).asBytes()) <= 1);
      Assert.assertEquals(containerDisk, containerPlan.getRequiredResource().getDisk());

      // All instances' resource requirement should be equal
      // So the size of set should be 1
      Set<Resource> resources = new HashSet<>();
      for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
        resources.add(instancePlan.getResource());
      }

      Assert.assertEquals(1, resources.size());
      int instancesCount = containerPlan.getInstances().size();
      Assert.assertEquals(containerRam
          .minus(RoundRobinPacking.DEFAULT_RAM_PADDING_PER_CONTAINER).divide(instancesCount),
          resources.iterator().next().getRam());
    }
  }

  /**
   * Test the scenario container level resource config are set
   */
  @Test
  public void testContainerRequestedResourcesWhenRamPaddingSet() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;
    Integer totalInstances = spoutParallelism + boltParallelism;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);
    // Explicit set resources for container
    ByteAmount containerRam = ByteAmount.fromGigabytes(10);
    ByteAmount containerDisk = ByteAmount.fromGigabytes(20);
    ByteAmount containerRamPadding = ByteAmount.fromMegabytes(512);
    double containerCpu = 30;

    topologyConfig.setContainerRamRequested(containerRam);
    topologyConfig.setContainerDiskRequested(containerDisk);
    topologyConfig.setContainerCpuRequested(containerCpu);
    topologyConfig.setContainerRamPadding(containerRamPadding);
    TopologyAPI.Topology topologyExplicitResourcesConfig =
        getTopology(spoutParallelism, boltParallelism, topologyConfig);
    PackingPlan packingPlanExplicitResourcesConfig =
        getRoundRobinPackingPlan(topologyExplicitResourcesConfig);

    Assert.assertEquals(numContainers,
        packingPlanExplicitResourcesConfig.getContainers().size());
    Assert.assertEquals(totalInstances, packingPlanExplicitResourcesConfig.getInstanceCount());

    for (PackingPlan.ContainerPlan containerPlan
        : packingPlanExplicitResourcesConfig.getContainers()) {
      Assert.assertEquals(containerCpu, containerPlan.getRequiredResource().getCpu(), DELTA);
      Assert.assertTrue(String.format(// due to round-off when using divide()
          "expected: %s but was: %s", containerRam, containerPlan.getRequiredResource().getRam()),
          Math.abs(
              containerRam.minus(containerPlan.getRequiredResource().getRam()).asBytes()) <= 1);
      Assert.assertEquals(containerDisk, containerPlan.getRequiredResource().getDisk());

      // All instances' resource requirement should be equal
      // So the size of set should be 1
      Set<Resource> resources = new HashSet<>();
      for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
        resources.add(instancePlan.getResource());
      }

      Assert.assertEquals(1, resources.size());
      int instancesCount = containerPlan.getInstances().size();
      Assert.assertEquals(containerRam
              .minus(containerRamPadding).divide(instancesCount),
          resources.iterator().next().getRam());
    }
  }

  /**
   * Test the scenario ram map config is partially set
   */
  @Test
  public void testCompleteRamMapRequested() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;
    Integer totalInstances = spoutParallelism + boltParallelism;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    // Explicit set resources for container
    // the value should be ignored, since we set the complete component ram map
    ByteAmount containerRam = ByteAmount.fromBytes(Long.MAX_VALUE);

    // Explicit set component ram map
    ByteAmount boltRam = ByteAmount.fromGigabytes(1);
    ByteAmount spoutRam = ByteAmount.fromGigabytes(2);

    topologyConfig.setContainerRamRequested(containerRam);
    topologyConfig.setComponentRam(BOLT_NAME, boltRam);
    topologyConfig.setComponentRam(SPOUT_NAME, spoutRam);

    TopologyAPI.Topology topologyExplicitRamMap =
        getTopology(spoutParallelism, boltParallelism, topologyConfig);
    PackingPlan packingPlanExplicitRamMap =
        getRoundRobinPackingPlan(topologyExplicitRamMap);

    AssertPacking.assertContainers(packingPlanExplicitRamMap.getContainers(),
        BOLT_NAME, SPOUT_NAME, boltRam, spoutRam, containerRam);
    Assert.assertEquals(totalInstances, packingPlanExplicitRamMap.getInstanceCount());
  }

  /**
   * Test the scenario ram map config is partially set
   */
  @Test
  public void testPartialRamMap() throws Exception {
    int numContainers = 2;
    int spoutParallelism = 4;
    int boltParallelism = 3;
    Integer totalInstances = spoutParallelism + boltParallelism;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    // Explicit set resources for container
    ByteAmount containerRam = ByteAmount.fromGigabytes(10);

    // Explicit set component ram map
    ByteAmount boltRam = ByteAmount.fromGigabytes(1);

    topologyConfig.setContainerRamRequested(containerRam);
    topologyConfig.setComponentRam(BOLT_NAME, boltRam);

    TopologyAPI.Topology topologyExplicitRamMap =
        getTopology(spoutParallelism, boltParallelism, topologyConfig);
    PackingPlan packingPlanExplicitRamMap =
        getRoundRobinPackingPlan(topologyExplicitRamMap);
    Assert.assertEquals(totalInstances, packingPlanExplicitRamMap.getInstanceCount());

    // Ram for bolt should be the value in component ram map
    for (PackingPlan.ContainerPlan containerPlan
        : packingPlanExplicitRamMap.getContainers()) {
      Assert.assertEquals(containerRam, containerPlan.getRequiredResource().getRam());
      int boltCount = 0;
      int instancesCount = containerPlan.getInstances().size();
      for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
        if (instancePlan.getComponentName().equals(BOLT_NAME)) {
          Assert.assertEquals(boltRam, instancePlan.getResource().getRam());
          boltCount++;
        }
      }

      // Ram for spout should be:
      // (containerRam - all ram for bolt - ram for padding) / (# of spouts)
      int spoutCount = instancesCount - boltCount;
      for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
        if (instancePlan.getComponentName().equals(SPOUT_NAME)) {
          Assert.assertEquals(
              containerRam
                  .minus(boltRam.multiply(boltCount))
                  .minus(RoundRobinPacking.DEFAULT_RAM_PADDING_PER_CONTAINER)
                  .divide(spoutCount),
              instancePlan.getResource().getRam());
        }
      }
    }
  }

  /**
   * test even packing of instances
   */
  @Test
  public void testEvenPacking() throws Exception {
    int numContainers = 2;
    int componentParallelism = 4;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    TopologyAPI.Topology topology =
        getTopology(componentParallelism, componentParallelism, topologyConfig);

    int numInstance = TopologyUtils.getTotalInstance(topology);
    // Two components
    Assert.assertEquals(2 * componentParallelism, numInstance);
    PackingPlan output = getRoundRobinPackingPlan(topology);
    Assert.assertEquals(numContainers, output.getContainers().size());
    Assert.assertEquals((Integer) numInstance, output.getInstanceCount());

    for (PackingPlan.ContainerPlan container : output.getContainers()) {
      Assert.assertEquals(numInstance / numContainers, container.getInstances().size());

      // Verify each container got 2 spout and 2 bolt and container 1 got
      assertComponentCount(container, "spout", 2);
      assertComponentCount(container, "bolt", 2);
    }
  }


  /**
   * test re-packing of instances
   */
  @Test
  public void testRePacking() throws Exception {
    int numContainers = 2;
    int componentParallelism = 4;

    // Set up the topology and its config
    org.apache.heron.api.Config topologyConfig = new org.apache.heron.api.Config();
    topologyConfig.put(org.apache.heron.api.Config.TOPOLOGY_STMGRS, numContainers);

    TopologyAPI.Topology topology =
        getTopology(componentParallelism, componentParallelism, topologyConfig);

    int numInstance = TopologyUtils.getTotalInstance(topology);
    // Two components
    Assert.assertEquals(2 * componentParallelism, numInstance);

    Map<String, Integer> componentChanges = new HashMap<>();
    componentChanges.put(SPOUT_NAME, -1);
    componentChanges.put(BOLT_NAME,  +1);
    PackingPlan output = getRoundRobinRePackingPlan(topology, componentChanges);
    Assert.assertEquals(numContainers, output.getContainers().size());
    Assert.assertEquals((Integer) numInstance, output.getInstanceCount());

    int spoutCount = 0;
    int boltCount = 0;
    for (PackingPlan.ContainerPlan container : output.getContainers()) {
      Assert.assertEquals(numInstance / numContainers, container.getInstances().size());

      for (PackingPlan.InstancePlan instancePlan : container.getInstances()) {
        if (SPOUT_NAME.equals(instancePlan.getComponentName())) {
          spoutCount++;
        } else if (BOLT_NAME.equals(instancePlan.getComponentName())) {
          boltCount++;
        }
      }
    }
    Assert.assertEquals(componentParallelism - 1, spoutCount);
    Assert.assertEquals(componentParallelism + 1, boltCount);
  }

  private static void assertComponentCount(
      PackingPlan.ContainerPlan containerPlan, String componentName, int expectedCount) {
    int count = 0;
    for (PackingPlan.InstancePlan instancePlan : containerPlan.getInstances()) {
      if (componentName.equals(instancePlan.getComponentName())) {
        count++;
      }
    }
    Assert.assertEquals(expectedCount, count);
  }

}
