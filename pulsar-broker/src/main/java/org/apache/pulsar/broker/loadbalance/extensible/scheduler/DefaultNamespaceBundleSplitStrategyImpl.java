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
package org.apache.pulsar.broker.loadbalance.extensible.scheduler;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.extensible.BaseLoadManagerContext;
import org.apache.pulsar.broker.loadbalance.extensible.data.BrokerLoadData;
import org.apache.pulsar.broker.loadbalance.extensible.data.LoadDataStore;
import org.apache.pulsar.broker.loadbalance.impl.LoadManagerShared;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.policies.data.loadbalancer.BundleData;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.policies.data.loadbalancer.TimeAverageMessageData;

/**
 * Determines which bundles should be split based on various thresholds.
 *
 * Migrate from {@link org.apache.pulsar.broker.loadbalance.impl.BundleSplitterTask}
 */
@Slf4j
public class DefaultNamespaceBundleSplitStrategyImpl implements NamespaceBundleSplitStrategy {

    public DefaultNamespaceBundleSplitStrategyImpl() {}

    @Override
    public Set<String> findBundlesToSplit(BaseLoadManagerContext context, NamespaceService namespaceService) {
        Set<String> bundleCache = new HashSet<>();
        final ServiceConfiguration conf = context.brokerConfiguration();
        int maxBundleCount = conf.getLoadBalancerNamespaceMaximumBundles();
        long maxBundleTopics = conf.getLoadBalancerNamespaceBundleMaxTopics();
        long maxBundleSessions = conf.getLoadBalancerNamespaceBundleMaxSessions();
        long maxBundleMsgRate = conf.getLoadBalancerNamespaceBundleMaxMsgRate();
        long maxBundleBandwidth = conf.getLoadBalancerNamespaceBundleMaxBandwidthMbytes() * LoadManagerShared.MIBI;
        LoadDataStore<BrokerLoadData> brokerLoadDataStore = context.brokerLoadDataStore();
        LoadDataStore<BundleData> bundleLoadDataStore = context.bundleLoadDataStore();
        brokerLoadDataStore.forEach((broker, brokerLoadData) -> {
            for (final Map.Entry<String, NamespaceBundleStats> entry : brokerLoadData.getLastStats().entrySet()) {
                final String bundle = entry.getKey();
                final NamespaceBundleStats stats = entry.getValue();
                if (stats.topics < 2) {
                    log.info("The count of topics on the bundle {} is less than 2, skip split!", bundle);
                    continue;
                }
                double totalMessageRate = 0;
                double totalMessageThroughput = 0;
                // Attempt to consider long-term message data, otherwise effectively ignore.
                Optional<BundleData> bundleLoadDataOpt = bundleLoadDataStore.get(bundle);
                if (bundleLoadDataOpt.isPresent()) {
                    final TimeAverageMessageData longTermData = bundleLoadDataOpt.get().getLongTermData();
                    totalMessageRate = longTermData.totalMsgRate();
                    totalMessageThroughput = longTermData.totalMsgThroughput();
                }
                if (stats.topics > maxBundleTopics || (maxBundleSessions > 0 && (stats.consumerCount
                        + stats.producerCount > maxBundleSessions))
                        || totalMessageRate > maxBundleMsgRate || totalMessageThroughput > maxBundleBandwidth) {
                    final String namespace = LoadManagerShared.getNamespaceNameFromBundleName(bundle);
                    try {
                        final int bundleCount = namespaceService.getBundleCount(NamespaceName.get(namespace));
                        if (bundleCount < maxBundleCount) {
                            bundleCache.add(bundle);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug(
                                        "Could not split namespace bundle {} because namespace {} has too many bundles:"
                                                + "{}", bundle, namespace, bundleCount);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Error while getting bundle count for namespace {}", namespace, e);
                    }
                }
            }
        });
        return bundleCache;
    }
}