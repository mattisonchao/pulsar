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
package org.apache.pulsar.broker.loadbalance.extensible.data;

import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The load data store interface.
 *
 * @param <T> The Load data type.
 */
public interface LoadDataStore<T> extends Closeable {

    /**
     * Push load data to store.
     *
     * @param key
     *           The load data key.
     * @param loadData
     *           The load data.
     */
    void push(String key, T loadData) throws LoadDataStoreException;

    /**
     * Async push load data to store.
     *
     * @param key
     *           The load data key.
     * @param loadData
     *           The load data.
     */
    CompletableFuture<Void> pushAsync(String key, T loadData);

    /**
     * Get load data by key.
     *
     * @param key
     *           The load data key.
     */
    Optional<T> get(String key);

    /**
     * Async get load data by key.
     *
     * @param key
     *           The load data key.
     */
    CompletableFuture<Optional<T>> getAsync(String key);

    CompletableFuture<Void> removeAsync(String key);

    void remove(String key) throws LoadDataStoreException;

    void forEach(BiConsumer<String, T> action);

    /**
     * Listen the load data change.
     */
    void listen(BiConsumer<String, T> listener);

    /**
     * The load data key count.
     */
    int size();
}