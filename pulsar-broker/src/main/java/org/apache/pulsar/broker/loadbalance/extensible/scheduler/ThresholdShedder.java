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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.extensible.BaseLoadManagerContext;
import org.apache.pulsar.broker.loadbalance.extensible.data.BrokerLoadData;
import org.apache.pulsar.broker.loadbalance.extensible.data.LoadDataStore;
import org.apache.pulsar.broker.loadbalance.extensible.data.Unload;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load shedding strategy that unloads any broker that exceeds the average resource utilization of all brokers by a
 * configured threshold. As a consequence, this strategy tends to distribute load among all brokers. It does this by
 * first computing the average resource usage per broker for the whole cluster. The resource usage for each broker is
 * calculated using the following method:
 * {@link LocalBrokerData#getMaxResourceUsageWithWeight(double, double, double, double, double)}. The weights
 * for each resource are configurable. Historical observations are included in the running average based on the broker's
 * setting for loadBalancerHistoryResourcePercentage. Once the average resource usage is calculated, a broker's
 * current/historical usage is compared to the average broker usage. If a broker's usage is greater than the average
 * usage per broker plus the loadBalancerBrokerThresholdShedderPercentage, this load shedder proposes removing
 * enough bundles to bring the unloaded broker 5% below the current average broker usage. Note that recently
 * unloaded bundles are not unloaded again.
 */
public class ThresholdShedder implements NamespaceUnloadStrategy {
    private static final Logger log = LoggerFactory.getLogger(ThresholdShedder.class);
    private final List<Unload> selectedBundlesCache = new ArrayList<>();
    private static final double ADDITIONAL_THRESHOLD_PERCENT_MARGIN = 0.05;
    private static final double MB = 1024 * 1024;

    private static final long LOAD_LOG_SAMPLE_DELAY_IN_SEC = 5 * 60; // 5 mins
    private final Map<String, Double> brokerAvgResourceUsage = new HashMap<>();
    private long lastSampledLoadLogTS = 0;


    private static int toPercentage(double usage) {
        return (int) (usage * 100);
    }

    private boolean canSampleLog() {
        long now = System.currentTimeMillis() / 1000;
        boolean sampleLog = now - lastSampledLoadLogTS >= LOAD_LOG_SAMPLE_DELAY_IN_SEC;
        if (sampleLog) {
            lastSampledLoadLogTS = now;
        }
        return sampleLog;
    }

    @Override
    public List<Unload> findBundlesForUnloading(BaseLoadManagerContext context,
                                                Map<String, Long> recentlyUnloadedBundles) {
        final var conf = context.brokerConfiguration();
        selectedBundlesCache.clear();
        boolean sampleLog = canSampleLog();
        final double threshold = conf.getLoadBalancerBrokerThresholdShedderPercentage() / 100.0;
        final double minThroughputThreshold = conf.getLoadBalancerBundleUnloadMinThroughputThreshold() * MB;

        final double avgUsage = getBrokerAvgUsage(
                context.brokerLoadDataStore(), conf.getLoadBalancerHistoryResourcePercentage(), conf, sampleLog);
        if (sampleLog) {
            log.info("brokers' resource avgUsage:{}%", toPercentage(avgUsage));
        }

        if (avgUsage == 0) {
            log.warn("average max resource usage is 0");
            return selectedBundlesCache;
        }

        context.brokerLoadDataStore().forEach((broker, brokerData) -> {
            final double currentUsage = brokerAvgResourceUsage.getOrDefault(broker, 0.0);

            if (currentUsage < avgUsage + threshold) {
                if (sampleLog) {
                    log.info("[{}] broker is not overloaded, ignoring at this point, currentUsage:{}%",
                            broker, toPercentage(currentUsage));
                }
                return;
            }

            double percentOfTrafficToOffload =
                    currentUsage - avgUsage - threshold + ADDITIONAL_THRESHOLD_PERCENT_MARGIN;
            double brokerCurrentThroughput = brokerData.getMsgThroughputIn() + brokerData.getMsgThroughputOut();
            double minimumThroughputToOffload = brokerCurrentThroughput * percentOfTrafficToOffload;

            if (minimumThroughputToOffload < minThroughputThreshold) {
                if (sampleLog) {
                    log.info("[{}] broker is planning to shed throughput {} MByte/s less than "
                                    + "minimumThroughputThreshold {} MByte/s, skipping bundle unload.",
                            broker, minimumThroughputToOffload / MB, minThroughputThreshold / MB);
                }
                return;
            }
            log.info(
                    "Attempting to shed load on {}, which has max resource usage above avgUsage  and threshold {}%"
                            + " > {}% + {}% -- Offloading at least {} MByte/s of traffic, left throughput {} MByte/s",
                    broker, 100 * currentUsage, 100 * avgUsage, 100 * threshold, minimumThroughputToOffload / MB,
                    (brokerCurrentThroughput - minimumThroughputToOffload) / MB);

            MutableDouble trafficMarkedToOffload = new MutableDouble(0);
            MutableBoolean atLeastOneBundleSelected = new MutableBoolean(false);

            if (brokerData.getBundles().size() > 1) {
                brokerData.getLastStats().entrySet().stream()
                        .map((e) -> {
                            String bundle = e.getKey();
                            var bundleData = e.getValue();
                            // [WARN]: it was short-term msgThroughputIn and msgThroughputOut.
                            double throughput = bundleData.msgThroughputIn + bundleData.msgThroughputOut;
                            return Pair.of(bundle, throughput);
                        }).filter(e ->
                                !recentlyUnloadedBundles.containsKey(e.getLeft())
                        ).filter(e ->
                                brokerData.getBundles().contains(e.getLeft())
                        ).sorted((e1, e2) ->
                                Double.compare(e2.getRight(), e1.getRight())
                        ).forEach(e -> {
                            if (trafficMarkedToOffload.doubleValue() < minimumThroughputToOffload
                                    || atLeastOneBundleSelected.isFalse()) {
                                selectedBundlesCache.add(new Unload(broker, e.getLeft()));
                                trafficMarkedToOffload.add(e.getRight());
                                atLeastOneBundleSelected.setTrue();
                            }
                        });
            } else if (brokerData.getBundles().size() == 1) {
                log.warn(
                        "HIGH USAGE WARNING : Sole namespace bundle {} is overloading broker {}. "
                                + "No Load Shedding will be done on this broker",
                        brokerData.getBundles().iterator().next(), broker);
            } else {
                log.warn("Broker {} is overloaded despite having no bundles", broker);
            }
        });

        return selectedBundlesCache;
    }

    private double getBrokerAvgUsage(final LoadDataStore<BrokerLoadData> loadData, final double historyPercentage,
                                     final ServiceConfiguration conf, boolean sampleLog) {
        double totalUsage = 0.0;
        int totalBrokers = 0;

        for (Map.Entry<String, BrokerLoadData> entry : loadData.entrySet()) {
            BrokerLoadData localBrokerData = entry.getValue();
            String broker = entry.getKey();
            totalUsage += updateAvgResourceUsage(broker, localBrokerData, historyPercentage, conf, sampleLog);
            totalBrokers++;
        }

        return totalBrokers > 0 ? totalUsage / totalBrokers : 0;
    }

    private double updateAvgResourceUsage(String broker, BrokerLoadData brokerLoadData,
                                          final double historyPercentage, final ServiceConfiguration conf,
                                          boolean sampleLog) {
        Double historyUsage =
                brokerAvgResourceUsage.get(broker);
        double resourceUsage = brokerLoadData.getMaxResourceUsage(conf);

        if (sampleLog) {
            log.info("{} broker load: historyUsage={}%, resourceUsage={}%",
                    broker,
                    historyUsage == null ? 0 : toPercentage(historyUsage),
                    toPercentage(resourceUsage));
        }

        // wrap if resourceUsage is bigger than 1.0
        if (resourceUsage > 1.0) {
            log.error("{} broker resourceUsage is bigger than 100%. "
                            + "Some of the resource limits are mis-configured. "
                            + "Try to disable the error resource signals by setting their weights to zero "
                            + "or fix the resource limit configurations. "
                            + "Ref:https://pulsar.apache.org/docs/administration-load-balance/#thresholdshedder "
                            + "ResourceUsage:[{}], "
                            + "CPUResourceWeight:{}, MemoryResourceWeight:{}, DirectMemoryResourceWeight:{}, "
                            + "BandwithInResourceWeight:{}, BandwithOutResourceWeight:{}",
                    broker,
                    brokerLoadData.printResourceUsage(conf),
                    conf.getLoadBalancerCPUResourceWeight(),
                    conf.getLoadBalancerMemoryResourceWeight(),
                    conf.getLoadBalancerDirectMemoryResourceWeight(),
                    conf.getLoadBalancerBandwithInResourceWeight(),
                    conf.getLoadBalancerBandwithOutResourceWeight());

            resourceUsage = brokerLoadData.getMaxResourceUsageWithinLimit(conf);

            log.warn("{} broker recomputed max resourceUsage={}%. Skipped usage signals bigger than 100%",
                    broker, toPercentage(resourceUsage));
        }
        historyUsage = historyUsage == null
                ? resourceUsage : historyUsage * historyPercentage + (1 - historyPercentage) * resourceUsage;

        brokerAvgResourceUsage.put(broker, historyUsage);
        return historyUsage;
    }
}