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

import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.extensible.BaseLoadManagerContext;
import org.apache.pulsar.broker.loadbalance.impl.LoadManagerShared;
import org.apache.pulsar.client.admin.PulsarAdminException;

/**
 * As a leader, it will select bundles for the namespace service to unload
 * so that they may be reassigned to new brokers.
 */
@Slf4j
public class NamespaceUnloadScheduler implements LoadManagerScheduler {

    private final List<NamespaceUnloadStrategy> namespaceUnloadStrategyPipeline;

    private final PulsarService pulsar;

    private final BaseLoadManagerContext context;

    private final ServiceConfiguration configuration;

    final Map<String, Long> recentlyUnloadedBundles;

    public NamespaceUnloadScheduler(PulsarService pulsar, BaseLoadManagerContext context) {
        this.namespaceUnloadStrategyPipeline = new ArrayList<>();
        this.recentlyUnloadedBundles = new HashMap<>();
        this.pulsar = pulsar;
        this.context = context;
        this.configuration = context.brokerConfiguration();
    }

    @Override
    public void execute() {
        if (!(configuration.isLoadBalancerEnabled()
                && configuration.isLoadBalancerSheddingEnabled())
                || !isLeader()) {
            return;
        }
        if (context.brokerRegistry().getAvailableBrokers().size() <= 1) {
            log.info("Only 1 broker available: no load shedding will be performed");
            return;
        }
        // Remove bundles who have been unloaded for longer than the grace period from the recently unloaded map.
        final long timeout = System.currentTimeMillis()
                - TimeUnit.MINUTES.toMillis(configuration.getLoadBalancerSheddingGracePeriodMinutes());
        recentlyUnloadedBundles.keySet().removeIf(e -> recentlyUnloadedBundles.get(e) < timeout);

        for (NamespaceUnloadStrategy strategy : namespaceUnloadStrategyPipeline) {
            final Multimap<String, String> bundlesToUnload = strategy.findBundlesForUnloading(context);

            bundlesToUnload.asMap().forEach((broker, bundles) -> {
                bundles.forEach(bundle -> {
                    final String namespaceName = LoadManagerShared.getNamespaceNameFromBundleName(bundle);
                    final String bundleRange = LoadManagerShared.getBundleRangeFromBundleName(bundle);

                    log.info("[{}] Unloading bundle: {} from broker {}",
                            strategy.getClass().getSimpleName(), bundle, broker);
                    try {
                        pulsar.getAdminClient().namespaces().unloadNamespaceBundle(namespaceName, bundleRange);
                        recentlyUnloadedBundles.put(bundle, System.currentTimeMillis());
                    } catch (PulsarServerException | PulsarAdminException e) {
                        log.warn("Error when trying to perform load shedding on {} for broker {}", bundle, broker, e);
                    }
                });
            });
        }

    }

    private boolean isLeader() {
        return pulsar.getLeaderElectionService() != null && pulsar.getLeaderElectionService().isLeader();
    }
}