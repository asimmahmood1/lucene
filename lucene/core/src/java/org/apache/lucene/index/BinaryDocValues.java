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
import org.apache.lucene.util.BytesRef;

/** A per-document numeric value. */
public abstract class BinaryDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected BinaryDocValues() {}

  /**
   * Returns the binary value for the current document ID. It is illegal to call this method after
   * {@link #advanceExact(int)} returned {@code false}.
   *
   * @return binary value
   */
  public abstract BytesRef binaryValue() throws IOException;

  /**
   * Async prefetch hint for a batch of doc IDs. Issues prefetch calls for the byte ranges
   * that subsequent {@code advanceExact()} + {@code binaryValue()} calls on the same docs
   * would read. This is a hint — it warms cache but does not affect correctness.
   *
   * <p>For fixed-length binary fields, this prefetches the data slice at the computed offsets.
   * For variable-length binary fields, this prefetches both the address index and the data.
   *
   * @param docs sorted ascending array of doc IDs (no duplicates)
   * @param size number of valid entries in the docs array
   */
  public void prefetchBinaryValues(int[] docs, int size) throws IOException {
    // default no-op — codec implementations override with async prefetch
  }
}
