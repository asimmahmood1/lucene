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
package org.apache.lucene.search;

import java.io.IOException;

/**
 * Optional interface for {@link LeafFieldComparator} implementations that support
 * batch-oriented comparison using pre-fetched values. This enables bulk collection
 * paths to perform competitive checks without per-doc IO.
 *
 * <p>Implementations store references to pre-fetched value and doc ID arrays via
 * {@link #setBatch}, then use index-based access for comparisons and copies.
 *
 * <p>This interface uses {@code long[]} for values since all numeric doc values
 * are stored as longs internally (ints, floats, and doubles are encoded as
 * sortable longs).
 *
 * @lucene.experimental
 */
public interface BulkValueComparator {

  /**
   * Provide a batch of pre-fetched values and their corresponding doc IDs.
   * Subsequent calls to {@code compareBottomAt}, {@code copyAt}, and
   * {@code compareTopAt} operate on this batch.
   *
   * @param values pre-fetched sort field values
   * @param docs   corresponding doc IDs (sorted ascending, no duplicates)
   * @param count  number of valid entries in the arrays
   */
  void setBatch(long[] values, int[] docs, int count);

  /**
   * Compare the bottom of the priority queue against the pre-fetched value
   * at index {@code idx}. Same semantics as
   * {@link LeafFieldComparator#compareBottom(int)}.
   *
   * @param idx index into the batch arrays
   * @return negative if bottom sorts after value[idx], positive if before, 0 if equal
   */
  int compareBottomAt(int idx) throws IOException;

  /**
   * Install the pre-fetched value at index {@code idx} into the given slot,
   * AND perform any bookkeeping that {@link LeafFieldComparator#copy(int, int)}
   * would do (e.g., competitive iterator state updates).
   *
   * @param slot the priority queue slot to copy into
   * @param idx  index into the batch arrays
   */
  void copyAt(int slot, int idx) throws IOException;

  /**
   * Compare the top value against the pre-fetched value at index {@code idx}.
   * Same semantics as {@link LeafFieldComparator#compareTop(int)}.
   * Used by PagingFieldCollector for searchAfter pagination.
   *
   * @param idx index into the batch arrays
   * @return negative if top sorts after value[idx], positive if before, 0 if equal
   */
  int compareTopAt(int idx) throws IOException;
}
