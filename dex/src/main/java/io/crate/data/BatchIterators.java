/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.data;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collector;

public class BatchIterators {

    /**
     * Use {@code collector} to consume all elements from {@code it}
     *
     * This does *not* automatically close the BatchIterator when the end is reached.
     *
     * @param <T> element type
     * @param <A> state type
     * @param <R> result type
     * @return future containing the result
     */
    public static <T, A, R> CompletableFuture<R> collect(BatchIterator<T> it, Collector<T, A, R> collector) {
        return collect(it, collector.supplier().get(), collector, new CompletableFuture<>());
    }

    /**
     * Use {@code collector} to consume all elements from {@code it}
     *
     * This does *not* automatically close the BatchIterator when the end is reached.
     *
     * @param <T> element type
     * @param <A> state type
     * @param <R> result type
     * @return future containing the result, this is the future that has been provided as argument.
     */
    public static <T, A, R> CompletableFuture<R> collect(BatchIterator<T> it,
                                                         A state,
                                                         Collector<T, A, R> collector,
                                                         CompletableFuture<R> resultFuture) {
        BiConsumer<A, T> accumulator = collector.accumulator();
        boolean allLoaded;
        try {
            while (it.moveNext()) {
                accumulator.accept(state, it.currentElement());
            }
            allLoaded = it.allLoaded();
        } catch (Throwable t) {
            resultFuture.completeExceptionally(t);
            return resultFuture;
        }

        if (allLoaded) {
            resultFuture.complete(collector.finisher().apply(state));
        } else {
            it.loadNextBatch().whenComplete((r, t) -> {
                if (t == null) {
                    collect(it, state, collector, resultFuture);
                } else {
                    resultFuture.completeExceptionally(t);
                }
            });
        }
        return resultFuture;
    }

    /**
     * Partition the items of a BatchIterator into blocks of {@code size}.
     *
     * Example:
     * <pre>
     *     inputBi: [1, 2, 3, 4, 5]
     *
     *     partition(inputBi, 2, ArrayList::new, List::add) -> [[1, 2], [3, 4], [5]]
     * </pre>
     * @param supplier Used to create the state per partition
     * @param accumulator Used to add items to the partitions state
     * @param <T> input item type
     * @param <A> output item type
     */
    public static <T, A> BatchIterator<A> partition(BatchIterator<T> bi,
                                                    int size,
                                                    Supplier<A> supplier,
                                                    BiConsumer<A, T> accumulator) {
        return new MappedForwardingBatchIterator<T, A>() {

            private A element = null;
            private A state = supplier.get();
            private int idx = 0;

            @Override
            protected BatchIterator<T> delegate() {
                return bi;
            }

            @Override
            public boolean moveNext() {
                while (idx < size && bi.moveNext()) {
                    accumulator.accept(state, bi.currentElement());
                    idx++;
                }
                if (idx == size || (idx > 0 && bi.allLoaded())) {
                    element = state;
                    state = supplier.get();
                    idx = 0;
                    return true;
                }
                element = null;
                return false;
            }

            @Override
            public A currentElement() {
                return element;
            }
        };
    }
}
