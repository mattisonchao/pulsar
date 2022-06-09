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
package org.apache.pulsar.broker.loadbalance.extensible;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.loadbalance.impl.PulsarResourceDescription;
import org.apache.pulsar.broker.loadbalance.impl.SimpleResourceUnit;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.stats.Metrics;
import org.apache.pulsar.policies.data.loadbalancer.LoadManagerReport;

/**
 * Wrapper class allowing classes of instance BrokerDiscoveryImpl to be compatible with the interface LoadManager.
 */
public class ExtensibleLoadManagerWrapper implements LoadManager {

    private final BrokerDiscoveryImpl brokerDiscovery;

    public ExtensibleLoadManagerWrapper(BrokerDiscoveryImpl brokerDiscovery) {
        this.brokerDiscovery = brokerDiscovery;
    }

    @Override
    public void start() throws PulsarServerException {
        brokerDiscovery.start();
    }

    @Override
    public void initialize(PulsarService pulsar) {
        brokerDiscovery.initialize(pulsar);
    }

    @Override
    public boolean isCentralized() {
        return true;
    }

    @Override
    public Optional<ResourceUnit> getLeastLoaded(ServiceUnitId su) throws Exception {
        return brokerDiscovery.discover(su).map(s -> {
            String webServiceUrl = getBrokerWebServiceUrl(s);
            String brokerZnodeName = getBrokerZnodeName(s, webServiceUrl);
            return new SimpleResourceUnit(webServiceUrl,
                    new PulsarResourceDescription(),
                    Map.of(ResourceUnit.PROPERTY_KEY_BROKER_ZNODE_NAME, brokerZnodeName));
        });
    }

    private String getBrokerWebServiceUrl(String broker) {
        Optional<BrokerLookupData> localDataOpt = this.brokerDiscovery.getBrokerRegistry().lookup(broker);
        return localDataOpt
                .map((localData) -> localData.getWebServiceUrl() != null
                        ? localData.getWebServiceUrl() : localData.getWebServiceUrlTls())
                .orElse(String.format("http://%s", broker));
    }

    private String getBrokerZnodeName(String broker, String webServiceUrl) {
        String scheme = webServiceUrl.substring(0, webServiceUrl.indexOf("://"));
        return String.format("%s://%s", scheme, broker);
    }

    @Override
    public void disableBroker() throws Exception {
        brokerDiscovery.getBrokerRegistry().unregister();
    }

    @Override
    public Set<String> getAvailableBrokers() throws Exception {
        return new HashSet<>(brokerDiscovery.getBrokerRegistry().getAvailableBrokers());
    }

    @Override
    public CompletableFuture<Set<String>> getAvailableBrokersAsync() {
        return brokerDiscovery.getBrokerRegistry().getAvailableBrokersAsync().thenApply(HashSet::new);
    }

    @Override
    public void stop() throws PulsarServerException {
        brokerDiscovery.stop();
    }

    @Override
    public List<Metrics> getLoadBalancingMetrics() {
        return null;
    }

    @Override
    public LoadManagerReport generateLoadReport() throws Exception {
        return null;
    }

    @Override
    public void doLoadShedding() {
        brokerDiscovery.getNamespaceUnloadScheduler().execute();
    }

    @Override
    public void doNamespaceBundleSplit() {
        // No-op.
    }

    @Override
    @Deprecated
    public void setLoadReportForceUpdateFlag() {
        // No-op.
    }

    @Override
    @Deprecated
    public void writeLoadReportOnZookeeper() throws Exception {
        // No-op, this operation is not useful, the load data reporter will automatically write.
    }

    @Override
    @Deprecated
    public void writeResourceQuotasToZooKeeper() throws Exception {
        // No-op, this operation is not useful, the load data reporter will automatically write.
    }
}