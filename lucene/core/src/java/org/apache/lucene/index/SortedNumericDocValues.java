/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.index;

import java.io.IOException;

/** A list of per-document numeric values, sorted according to {@link Long#compare(long, long)}. */
public abstract class SortedNumericDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected SortedNumericDocValues() {}

  /**
   * Iterates to the next value in the current document. Do not call this more than {@link
   * #docValueCount} times for the document.
   */
  public abstract long nextValue() throws IOException;

  /**
   * Retrieves the number of values for the current document. This must always be greater than zero.
   * It is illegal to call this method after {@link #advanceExact(int)} returned {@code false}.
   */
  public abstract int docValueCount();

  /**
   * Prefetch the data needed to iterate values for a batch of documents. This prepares
   * the underlying storage (DISI existence bitmap, address index, and value data) so that
   * subsequent {@link #advanceExact(int)}, {@link #docValueCount()}, and {@link #nextValue()}
   * calls find the data already in cache rather than blocking on synchronous IO.
   *
   * <p>The default implementation is a no-op. Codec implementations override this to
   * prefetch three layers of data:
   * <ol>
   *   <li>DISI blocks (sparse fields only) — which docs have values</li>
   *   <li>Address index blocks — maps each doc to its value range in the flat values array</li>
   *   <li>Value data blocks — the actual packed integer values</li>
   * </ol>
   *
   * <p><b>IMPORTANT:</b> The docs array must be sorted ascending with no duplicates.
   * Call this once for a batch, then iterate the batch normally. The prefetch is
   * non-speculative — every prefetched block will be read by the subsequent iteration.
   *
   * <p><b>Recommended usage — date_histogram aggregation:</b>
   * <pre>{@code
   * // Batch of doc IDs from the collector
   * sndv.prefetchRange(docs, batchSize);
   * // Iterate normally — DISI, addresses, and values are all warm
   * for (int i = 0; i < batchSize; i++) {
   *     if (sndv.advanceExact(docs[i])) {
   *         int count = sndv.docValueCount();
   *         for (int j = 0; j < count; j++) {
   *             long value = sndv.nextValue();
   *             histogram.collect(value);
   *         }
   *     }
   * }
   * }</pre>
   *
   * <p><b>Recommended usage — composite aggregation:</b>
   * <pre>{@code
   * sndv.prefetchRange(docs, batchSize);
   * for (int i = 0; i < batchSize; i++) {
   *     if (sndv.advanceExact(docs[i])) {
   *         for (int j = 0; j < sndv.docValueCount(); j++) {
   *             queue.addIfCompetitive(sndv.nextValue());
   *         }
   *     }
   * }
   * }</pre>
   *
   * @param docs sorted array of doc IDs to prefetch data for
   * @param size number of valid entries in the docs array
   */
  public void prefetchRange(int[] docs, int size) throws IOException {
    // default no-op — codec implementations override with prefetch
  }

  /**
   * Prefetch the data for a contiguous range of documents starting at {@code startDoc}.
   * This is more efficient than {@link #prefetchRange(int[], int)} for dense sequential
   * doc IDs because it avoids materializing an {@code int[]} array — the implementation
   * can compute the byte range directly from {@code startDoc} and {@code count}.
   *
   * @param startDoc first doc ID in the range
   * @param count number of documents in the range
   */
  public void prefetchRange(int startDoc, int count) throws IOException {
    // default no-op — codec implementations override with prefetch
  }
}
