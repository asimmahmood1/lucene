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
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.FieldExistsQuery;

/** A per-document numeric value. */
public abstract class NumericDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected NumericDocValues() {}

  /**
   * Returns the numeric value for the current document ID. It is illegal to call this method after
   * {@link #advanceExact(int)} returned {@code false}.
   *
   * @return numeric value
   */
  public abstract long longValue() throws IOException;

  /**
   * Bulk retrieval of numeric doc values with prefetch support. This API serves two purposes:
   * (1) reduces virtual function call overhead by batching, and (2) enables codec implementations
   * to prefetch the underlying packed integer data before reading, so that cache misses are
   * resolved asynchronously rather than blocking the calling thread.
   *
   * <p><b>Recommended usage — sort queries (TopFieldCollector):</b>
   * <pre>{@code
   * // Collect a batch of doc IDs from DocIdStream
   * int count = stream.intoArray(docBuffer);  // up to 4096 docs
   * // Prefetch + read all values in one call
   * numericDocValues.longValues(count, docBuffer, valueBuffer, missingValue);
   * // Compare and collect using pre-fetched values — zero IO
   * for (int i = 0; i < count; i++) {
   *     if (Long.compare(bottom, valueBuffer[i]) > 0) { ... }
   * }
   * }</pre>
   *
   * <p><b>Recommended usage — aggregations:</b>
   * <pre>{@code
   * // Batch docs from the collector
   * numericDocValues.longValues(batchSize, docs, values, 0L);
   * // Aggregate using pre-fetched values
   * for (int i = 0; i < batchSize; i++) {
   *     histogram.collect(values[i]);
   * }
   * }</pre>
   *
   * <p>This API behaves as if implemented as below, which is the default implementation:
   *
   * <pre><code class="language-java">
   * public void longValues(int size, int[] docs, long[] values, long defaultValue) throws IOException {
   *   for (int i = 0; i &lt; size; ++i) {
   *     int doc = docs[i];
   *     long value;
   *     if (advanceExact(doc)) {
   *       value = longValue();
   *     } else {
   *       value = defaultValue;
   *     }
   *     values[i] = value;
   *   }
   * }
   * </code></pre>
   *
   * <p><b>NOTE</b>: The {@code docs} array is required to be sorted in ascending order with no
   * duplicates.
   *
   * <p><b>NOTE</b>: This API doesn't allow callers to know which doc IDs have a value or not. If
   * you need to exclude documents that don't have a value for this field, then you could apply a
   * {@link FieldExistsQuery} as a {@link Occur#FILTER} clause. Another option is to fall back to
   * using {@link #advanceExact} and {@link #longValue()} on ranges of doc IDs that may not be
   * dense, e.g.
   *
   * <pre><code class="language-java">
   * if (size > 0 &amp;&amp; values.advannceExact(docs[0]) &amp;&amp; values.docIDRunEnd() &gt; docs[size - 1]) {
   *   // use values#longValues to retrieve values
   * } else {
   *   // some docs may not have a value, use #advanceExact and #longValue
   * }
   * </code></pre>
   *
   * @param size the number of values to retrieve
   * @param docs the buffer of doc IDs whose values should be looked up
   * @param values the buffer of values to fill
   * @param defaultValue the value to put in the buffer when a document doesn't have a value
   */
  public void longValues(int size, int[] docs, long[] values, long defaultValue)
      throws IOException {
    for (int i = 0; i < size; ++i) {
      int doc = docs[i];
      long value;
      if (advanceExact(doc)) {
        value = longValue();
      } else {
        value = defaultValue;
      }
      values[i] = value;
    }
  }

  /**
   * Async prefetch hint for a batch of doc IDs. Issues {@code RandomAccessInput.prefetch()}
   * calls for the byte ranges that a subsequent {@code longValues()} call on the same docs
   * would read. This is a hint — it warms cache but does not affect correctness.
   *
   * @param size the number of doc IDs to prefetch
   * @param docs sorted ascending array of doc IDs (no duplicates)
   */
  public void prefetchLongValues(int size, int[] docs) throws IOException {
    // default no-op — codec implementations override with async prefetch
  }

  /**
   * Bulk read values for a contiguous range of doc IDs {@code [minDoc, minDoc+size)}.
   * Optimized for the {@code collectRange} path where doc IDs are sequential,
   * avoiding the need to materialize an {@code int[]} of doc IDs.
   *
   * <p>The default implementation delegates to {@link #longValues(int, int[], long[], long)}
   * by filling a temporary array. Codec implementations should override this to compute
   * byte ranges directly from {@code (minDoc, size)} without array materialization.
   *
   * @param minDoc the first doc ID in the range (inclusive)
   * @param size the number of consecutive docs to read
   * @param values the buffer to fill with values (must have length >= size)
   * @param defaultValue the value for docs without a value
   */
  public void longValuesRange(int minDoc, int size, long[] values, long defaultValue)
      throws IOException {
    for (int i = 0; i < size; i++) {
      if (advanceExact(minDoc + i)) {
        values[i] = longValue();
      } else {
        values[i] = defaultValue;
      }
    }
  }

  /**
   * Async prefetch hint for a contiguous range of doc IDs {@code [minDoc, minDoc+size)}.
   * Optimized for the {@code collectRange} path where doc IDs are sequential.
   *
   * <p>The default implementation is a no-op. Codec implementations should override this
   * to issue {@code RandomAccessInput.prefetch()} for the byte range covering the values
   * of docs in {@code [minDoc, minDoc+size)}.
   *
   * @param minDoc the first doc ID in the range (inclusive)
   * @param size the number of consecutive docs to prefetch
   */
  public void prefetchRange(int minDoc, int size) throws IOException {
    // default no-op — codec implementations override with async prefetch
  }
}
