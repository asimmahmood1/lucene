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
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * A per-document byte[] with presorted values. This is fundamentally an iterator over the int ord
 * values per document, with random access APIs to resolve an int ord to BytesRef.
 *
 * <p>Per-Document values in a SortedDocValues are deduplicated, dereferenced, and sorted into a
 * dictionary of unique values. A pointer to the dictionary value (ordinal) can be retrieved for
 * each document. Ordinals are dense and in increasing sorted order.
 */
public abstract class SortedDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected SortedDocValues() {}

  /**
   * Returns the ordinal for the current docID. It is illegal to call this method after {@link
   * #advanceExact(int)} returned {@code false}.
   *
   * @return ordinal for the document: this is dense, starts at 0, then increments by 1 for the next
   *     value in sorted order.
   */
  public abstract int ordValue() throws IOException;

  /**
   * Retrieves the value for the specified ordinal. The returned {@link BytesRef} may be re-used
   * across calls to {@link #lookupOrd(int)} so make sure to {@link BytesRef#deepCopyOf(BytesRef)
   * copy it} if you want to keep it around.
   *
   * @param ord ordinal to lookup (must be &gt;= 0 and &lt; {@link #getValueCount()})
   * @see #ordValue()
   */
  public abstract BytesRef lookupOrd(int ord) throws IOException;

  /**
   * Returns the number of unique values.
   *
   * @return number of unique values in this SortedDocValues. This is also equivalent to one plus
   *     the maximum ordinal.
   */
  public abstract int getValueCount();

  /**
   * If {@code key} exists, returns its ordinal, else returns {@code -insertionPoint-1}, like {@code
   * Arrays.binarySearch}.
   *
   * @param key Key to look up
   */
  public int lookupTerm(BytesRef key) throws IOException {
    int low = 0;
    int high = getValueCount() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      final BytesRef term = lookupOrd(mid);
      int cmp = term.compareTo(key);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  /**
   * Prepare for a subsequent {@link #lookupOrd(int)} call by prefetching the
   * term dictionary block containing the given ordinal. The default implementation
   * is a no-op. Codec implementations override this to issue an async prefetch
   * of the LZ4-compressed term dictionary block, so that the subsequent
   * {@code lookupOrd(ord)} finds the data already in cache.
   *
   * <p><b>IMPORTANT:</b> Calling this for a single ordinal immediately before
   * {@code lookupOrd} on the same thread provides minimal benefit — the prefetch
   * and read happen back-to-back with no time for async IO to complete. The real
   * benefit comes from batching: prefetch N ordinals, then read N ordinals.
   *
   * <p><b>Recommended usage — sort query (TermOrdValComparator.copyAt):</b>
   * <pre>{@code
   * // Called per competitive hit during bulk collection.
   * // The prefetch has time to complete while BytesRefBuilder is allocated.
   * termsIndex.prepareSeekExact(ord);
   * tempBRs[slot] = new BytesRefBuilder();
   * tempBRs[slot].copyBytes(termsIndex.lookupOrd(ord));  // block is warm
   * }</pre>
   *
   * <p><b>Recommended usage — term aggregation result building:</b>
   * <pre>{@code
   * // Phase 1: prefetch all LZ4 blocks for collected ordinals
   * for (int i = 0; i < bucketCount; i++) {
   *     sdv.prepareSeekExact(collectedOrds[i]);
   * }
   * // Phase 2: read terms — all blocks warm from phase 1
   * for (int i = 0; i < bucketCount; i++) {
   *     BytesRef term = sdv.lookupOrd(collectedOrds[i]);
   *     buckets[i].setKey(term);
   * }
   * }</pre>
   *
   * @param ord ordinal to prepare for lookup
   */
  public void prepareSeekExact(int ord) throws IOException {
    // default no-op — codec implementations override with prefetch
  }

  /**
   * Bulk retrieval of ordinal values for a batch of documents with prefetch support.
   * This API serves two purposes: (1) reduces virtual function call overhead by batching,
   * and (2) enables codec implementations to prefetch the underlying packed integer ordinal
   * data before reading, so that cache misses are resolved asynchronously.
   *
   * <p>The default implementation iterates per-doc. Codec implementations override this to
   * prefetch the ordinal data slice before reading, avoiding synchronous IO on the calling thread.
   *
   * <p><b>Recommended usage — sort query (TopFieldCollector with keyword sort):</b>
   * <pre>{@code
   * // Collect a batch of doc IDs from DocIdStream
   * int count = stream.intoArray(docBuffer);
   * // Prefetch + read all ordinals in one call
   * sortedDocValues.ordValues(count, docBuffer, ordBuffer, -1);
   * // Compare using pre-fetched ordinals — zero IO
   * for (int i = 0; i < count; i++) {
   *     if (bottomOrd - ordBuffer[i] > 0) { ... }
   * }
   * }</pre>
   *
   * <p><b>Recommended usage — per-doc collection path in aggregations:</b>
   * <pre>{@code
   * sortedDocValues.ordValues(batchSize, docs, ords, -1);
   * for (int i = 0; i < batchSize; i++) {
   *     if (ords[i] >= 0) collectBucket(ords[i]);
   * }
   * }</pre>
   *
   * <p><b>NOTE</b>: The {@code docs} array is required to be sorted in ascending order with no
   * duplicates.
   *
   * @param size the number of values to retrieve
   * @param docs the buffer of doc IDs whose ordinals should be looked up
   * @param ords the buffer of ordinals to fill
   * @param defaultOrd the ordinal to put in the buffer when a document doesn't have a value
   */
  public void ordValues(int size, int[] docs, int[] ords, int defaultOrd) throws IOException {
    for (int i = 0; i < size; ++i) {
      if (advanceExact(docs[i])) {
        ords[i] = ordValue();
      } else {
        ords[i] = defaultOrd;
      }
    }
  }

  /**
   * Async prefetch hint for a batch of doc IDs. Issues prefetch calls for the byte ranges
   * that a subsequent {@code ordValues()} call on the same docs would read. This is a hint —
   * it warms cache but does not affect correctness.
   *
   * @param size the number of doc IDs to prefetch
   * @param docs sorted ascending array of doc IDs (no duplicates)
   */
  public void prefetchOrdValues(int size, int[] docs) throws IOException {
    // default no-op — codec implementations override with async prefetch
  }

  /**
   * Returns a {@link TermsEnum} over the values. The enum supports {@link TermsEnum#ord()} and
   * {@link TermsEnum#seekExact(long)}.
   */
  public TermsEnum termsEnum() throws IOException {
    return new SortedDocValuesTermsEnum(this);
  }

  /**
   * Returns a {@link TermsEnum} over the values, filtered by a {@link CompiledAutomaton} The enum
   * supports {@link TermsEnum#ord()}.
   */
  public TermsEnum intersect(CompiledAutomaton automaton) throws IOException {
    TermsEnum in = termsEnum();
    switch (automaton.type) {
      case NONE:
        return TermsEnum.EMPTY;
      case ALL:
        return in;
      case SINGLE:
        return new SingleTermsEnum(in, automaton.term);
      case NORMAL:
        return new AutomatonTermsEnum(in, automaton);
      default:
        // unreachable
        throw new RuntimeException("unhandled case");
    }
  }
}
