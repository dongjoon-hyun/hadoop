/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.federation.fairness;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.junit.Assert;
import org.junit.Test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.server.federation.router.RBFConfigKeys;

import static org.apache.hadoop.hdfs.server.federation.fairness.RouterRpcFairnessConstants.CONCURRENT_NS;
import static org.apache.hadoop.hdfs.server.federation.router.RBFConfigKeys.DFS_ROUTER_FAIR_MINIMUM_HANDLER_COUNT_KEY;
import static org.apache.hadoop.hdfs.server.federation.router.RBFConfigKeys.DFS_ROUTER_HANDLER_COUNT_KEY;
import static org.apache.hadoop.hdfs.server.federation.router.RBFConfigKeys.DFS_ROUTER_MONITOR_NAMENODE;

/**
 * Test functionality of {@link DynamicRouterRpcFairnessPolicyController).
 */
public class TestDynamicRouterRpcFairnessPolicyController {

  private static String nameServices = "ns1.nn1, ns1.nn2, ns2.nn1, ns2.nn2, ns3.nn1";

  @Test
  public void testDynamicControllerSimple() throws InterruptedException, TimeoutException {
//    verifyDynamicControllerSimple(true);
    verifyDynamicControllerSimple(false);
  }

  @Test
  public void testDynamicControllerAllPermitsAcquired() throws InterruptedException {
    verifyDynamicControllerAllPermitsAcquired(true);
    verifyDynamicControllerAllPermitsAcquired(false);
  }

  private void verifyDynamicControllerSimple(boolean manualRefresh)
      throws InterruptedException, TimeoutException {
    // 3 permits each ns
    DynamicRouterRpcFairnessPolicyController controller;
    if (manualRefresh) {
      controller = getFairnessPolicyController(20);
    } else {
      controller = getFairnessPolicyController(20, 4);
    }

    String[] nss = new String[] {"ns1", "ns2", "ns3", CONCURRENT_NS};
    // Initial permit counts should be 5:5:5
    verifyRemainingPermitCounts(new int[] {5, 5, 5, 5}, nss, controller);

    // Release all permits
    for (int i = 0; i < 5; i++) {
      controller.releasePermit("ns1");
      controller.releasePermit("ns2");
      controller.releasePermit("ns3");
      controller.releasePermit(CONCURRENT_NS);
    }

    // Inject dummy metrics
    // Split half half for ns1 and concurrent
    Map<String, LongAdder> rejectedPermitsPerNs = new HashMap<>();
    Map<String, LongAdder> acceptedPermitsPerNs = new HashMap<>();
    injectDummyMetrics(rejectedPermitsPerNs, "ns1", 10);
    injectDummyMetrics(rejectedPermitsPerNs, "ns2", 0);
    injectDummyMetrics(rejectedPermitsPerNs, "ns3", 10);
    injectDummyMetrics(rejectedPermitsPerNs, CONCURRENT_NS, 10);
    controller.setMetrics(rejectedPermitsPerNs, acceptedPermitsPerNs);

    // Current permits count should be 6:3:6:6
    int[] newPermitCounts = new int[] {6, 3, 6, 6};

    if (manualRefresh) {
      controller.refreshPermitsCap();
    } else {
      Thread.sleep(5000);
    }
    verifyRemainingPermitCounts(newPermitCounts, nss, controller);

  }

  public void verifyDynamicControllerAllPermitsAcquired(boolean manualRefresh)
      throws InterruptedException {
    // 10 permits each ns
    DynamicRouterRpcFairnessPolicyController controller;
    if (manualRefresh) {
      controller = getFairnessPolicyController(40);
    } else {
      controller = getFairnessPolicyController(40, 4);
    }

    String[] nss = new String[] {"ns1", "ns2", "ns3", CONCURRENT_NS};
    verifyRemainingPermitCounts(new int[] {10, 10, 10, 10}, nss, controller);

    // Inject dummy metrics
    Map<String, LongAdder> rejectedPermitsPerNs = new HashMap<>();
    Map<String, LongAdder> acceptedPermitsPerNs = new HashMap<>();
    injectDummyMetrics(rejectedPermitsPerNs, "ns1", 13);
    injectDummyMetrics(rejectedPermitsPerNs, "ns2", 13);
    injectDummyMetrics(rejectedPermitsPerNs, "ns3", 13);
    injectDummyMetrics(rejectedPermitsPerNs, CONCURRENT_NS, 1);
    // New permit capacity will be 13:13:13:3
    controller.setMetrics(rejectedPermitsPerNs, acceptedPermitsPerNs);
    if (manualRefresh) {
      controller.refreshPermitsCap();
    } else {
      Thread.sleep(5000);
    }
    Assert.assertEquals("{\"concurrent\":-7,\"ns2\":3,\"ns1\":3,\"ns3\":3}",
        controller.getAvailableHandlerOnPerNs());

    // Can acquire 3 more permits for ns1, ns2, ns3
    verifyRemainingPermitCounts(new int[] {3, 3, 3, 0}, nss, controller);
    // Need to release at least 8 permits for concurrent before it has any free permits
    Assert.assertFalse(controller.acquirePermit(CONCURRENT_NS));
    for (int i = 0; i < 7; i++) {
      controller.releasePermit(CONCURRENT_NS);
    }
    Assert.assertFalse(controller.acquirePermit(CONCURRENT_NS));
    controller.releasePermit(CONCURRENT_NS);
    Assert.assertTrue(controller.acquirePermit(CONCURRENT_NS));
  }

  private void verifyRemainingPermitCounts(int[] remainingPermitCounts, String[] nss,
      RouterRpcFairnessPolicyController controller) {
    assert remainingPermitCounts.length == nss.length;
    for (int i = 0; i < remainingPermitCounts.length; i++) {
      verifyRemainingPermitCount(remainingPermitCounts[i], nss[i], controller);
    }
  }

  private void verifyRemainingPermitCount(int remainingPermitCount, String nameservice,
      RouterRpcFairnessPolicyController controller) {
    for (int i = 0; i < remainingPermitCount; i++) {
      Assert.assertTrue(controller.acquirePermit(nameservice));
    }
    Assert.assertFalse(controller.acquirePermit(nameservice));
  }

  private void injectDummyMetrics(Map<String, LongAdder> metrics, String ns, long value) {
    metrics.computeIfAbsent(ns, k -> new LongAdder()).add(value);
  }

  private DynamicRouterRpcFairnessPolicyController getFairnessPolicyController(int handlers,
      long refreshInterval) {
    return new DynamicRouterRpcFairnessPolicyController(createConf(handlers, 3), refreshInterval);
  }

  private DynamicRouterRpcFairnessPolicyController getFairnessPolicyController(int handlers) {
    return new DynamicRouterRpcFairnessPolicyController(createConf(handlers, 3), Long.MAX_VALUE);
  }

  private Configuration createConf(int handlers, int minHandlersPerNs) {
    Configuration conf = new HdfsConfiguration();
    conf.setInt(DFS_ROUTER_HANDLER_COUNT_KEY, handlers);
    conf.set(DFS_ROUTER_MONITOR_NAMENODE, nameServices);
    conf.setInt(DFS_ROUTER_FAIR_MINIMUM_HANDLER_COUNT_KEY, minHandlersPerNs);
    conf.setClass(RBFConfigKeys.DFS_ROUTER_FAIRNESS_POLICY_CONTROLLER_CLASS,
        DynamicRouterRpcFairnessPolicyController.class, RouterRpcFairnessPolicyController.class);
    return conf;
  }
}