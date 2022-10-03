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
package org.apache.pulsar.broker.loadbalance.extensible.strategy;

import java.util.List;
import java.util.Optional;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.pulsar.broker.loadbalance.extensible.BaseLoadManagerContext;
import org.apache.pulsar.broker.loadbalance.extensible.LoadManagerContext;
import org.apache.pulsar.common.naming.ServiceUnitId;

public abstract class AbstractBrokerSelectionStrategy implements BrokerSelectionStrategy {

    @Override
    public Optional<String> select(List<String> brokers, ServiceUnitId bundle, LoadManagerContext context) {
        if (CollectionUtils.isEmpty(brokers)) {
            return Optional.empty();
        }

        if (brokers.size() == 1) {
            return Optional.of(brokers.get(0));
        }

        if (!(context instanceof BaseLoadManagerContext)) {
            throw new IllegalStateException("The context must be BaseContext.");
        }

        return doSelect(brokers, bundle, (BaseLoadManagerContext) context);
    }

    public abstract Optional<String> doSelect(List<String> brokers,
                                              ServiceUnitId bundle,
                                              BaseLoadManagerContext context);
}