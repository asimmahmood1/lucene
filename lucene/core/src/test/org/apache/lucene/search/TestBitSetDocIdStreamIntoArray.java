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
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.FixedBitSet;

/**
 * Tests for {@link BitSetDocIdStream#intoArray} boundary conditions and correctness.
 *
 * <p>The key bug this validates: when a FixedBitSet of size N has all bits set and intoArray is
 * called with an array smaller than N, the iteration must not call nextSetBit(N) which would
 * trigger ArrayIndexOutOfBoundsException. This happens in DenseConjunctionBulkScorer when
 * WINDOW_SIZE=4096 and all docs in the window match.
 */
public class TestBitSetDocIdStreamIntoArray extends LuceneTestCase {

  /**
   * Exact reproduction of the production bug: FixedBitSet(4096) with all bits set, intoArray
   * with array size 4096 (PrefetchConfig.getBatchSize()). The old implementation using
   * nextSetBit(i+1) would call nextSetBit(4096) on a 4096-bit set, causing AIOOBE.
   */
  public void testAllBitsSetWindowSize4096() throws IOException {
    int windowSize = 4096;
    FixedBitSet bitSet = new FixedBitSet(windowSize);
    bitSet.set(0, windowSize); // all bits set

    int offset = 0;
    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, offset);

    int[] array = new int[windowSize];
    int count = stream.intoArray(array);

    assertEquals(windowSize, count);
    for (int i = 0; i < count; i++) {
      assertEquals("doc at index " + i, i, array[i]);
    }
    assertFalse(stream.mayHaveRemaining());
  }

  /**
   * Same bug with non-zero offset (simulates DenseConjunctionBulkScorer where windowBase > 0).
   */
  public void testAllBitsSetWithOffset() throws IOException {
    int windowSize = 4096;
    int offset = 8192; // second window
    FixedBitSet bitSet = new FixedBitSet(windowSize);
    bitSet.set(0, windowSize);

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, offset);

    int[] array = new int[windowSize];
    int count = stream.intoArray(array);

    assertEquals(windowSize, count);
    for (int i = 0; i < count; i++) {
      assertEquals("doc at index " + i, offset + i, array[i]);
    }
  }

  /**
   * Array smaller than bitset — must drain in multiple calls without AIOOBE.
   * This is the exact scenario from TopFieldCollector.collect(DocIdStream) where
   * batchSize (4096) equals the window size and all docs match.
   */
  public void testMultipleDrainsAllBitsSet() throws IOException {
    int windowSize = 4096;
    int batchSize = 1024; // smaller array forces multiple intoArray calls
    FixedBitSet bitSet = new FixedBitSet(windowSize);
    bitSet.set(0, windowSize);

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);

    List<Integer> collected = new ArrayList<>();
    int[] array = new int[batchSize];
    for (int count = stream.intoArray(array); count > 0; count = stream.intoArray(array)) {
      for (int i = 0; i < count; i++) {
        collected.add(array[i]);
      }
    }

    assertEquals(windowSize, collected.size());
    for (int i = 0; i < windowSize; i++) {
      assertEquals(i, collected.get(i).intValue());
    }
  }

  /** Word-aligned boundary: bitset size is exact multiple of 64. */
  public void testWordAlignedBoundary64() throws IOException {
    for (int size : new int[] {64, 128, 192, 256, 512, 1024, 2048, 4096}) {
      FixedBitSet bitSet = new FixedBitSet(size);
      bitSet.set(0, size);

      BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
      int[] array = new int[size];
      int count = stream.intoArray(array);

      assertEquals("size=" + size, size, count);
      for (int i = 0; i < count; i++) {
        assertEquals("size=" + size + " index=" + i, i, array[i]);
      }
    }
  }

  /** Non-word-aligned boundary: bitset size is NOT a multiple of 64. */
  public void testNonWordAlignedBoundary() throws IOException {
    for (int size : new int[] {1, 7, 33, 63, 65, 100, 127, 129, 255, 1000, 4095, 4097}) {
      FixedBitSet bitSet = new FixedBitSet(size);
      bitSet.set(0, size);

      BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
      int[] array = new int[size];
      int count = stream.intoArray(array);

      assertEquals("size=" + size, size, count);
      for (int i = 0; i < count; i++) {
        assertEquals("size=" + size + " index=" + i, i, array[i]);
      }
    }
  }

  /** Sparse bitset — only some bits set. */
  public void testSparseBitSet() throws IOException {
    int size = 4096;
    FixedBitSet bitSet = new FixedBitSet(size);
    List<Integer> expected = new ArrayList<>();
    // Set every 7th bit
    for (int i = 0; i < size; i += 7) {
      bitSet.set(i);
      expected.add(i);
    }

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
    List<Integer> collected = new ArrayList<>();
    int[] array = new int[128];
    for (int count = stream.intoArray(array); count > 0; count = stream.intoArray(array)) {
      for (int i = 0; i < count; i++) {
        collected.add(array[i]);
      }
    }

    assertEquals(expected, collected);
  }

  /** Single bit set at the last position — boundary of the bitset. */
  public void testSingleBitAtEnd() throws IOException {
    for (int size : new int[] {64, 128, 4096}) {
      FixedBitSet bitSet = new FixedBitSet(size);
      bitSet.set(size - 1);

      BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
      int[] array = new int[size];
      int count = stream.intoArray(array);

      assertEquals("size=" + size, 1, count);
      assertEquals(size - 1, array[0]);
    }
  }

  /** Empty bitset — no bits set. */
  public void testEmptyBitSet() throws IOException {
    FixedBitSet bitSet = new FixedBitSet(4096);
    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);

    int[] array = new int[4096];
    int count = stream.intoArray(array);
    assertEquals(0, count);
  }

  /** Array size 1 — drain one doc at a time. */
  public void testArraySizeOne() throws IOException {
    int size = 256;
    FixedBitSet bitSet = new FixedBitSet(size);
    bitSet.set(0, size);

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
    int[] array = new int[1];
    List<Integer> collected = new ArrayList<>();
    for (int count = stream.intoArray(array); count > 0; count = stream.intoArray(array)) {
      assertEquals(1, count);
      collected.add(array[0]);
    }

    assertEquals(size, collected.size());
    for (int i = 0; i < size; i++) {
      assertEquals(i, collected.get(i).intValue());
    }
  }

  /** Partial drain with upTo parameter. */
  public void testPartialDrainWithUpTo() throws IOException {
    int size = 4096;
    FixedBitSet bitSet = new FixedBitSet(size);
    bitSet.set(0, size);
    int offset = 1000;

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, offset);

    // Drain first 100 docs
    int[] array = new int[4096];
    int count = stream.intoArray(offset + 100, array);
    assertEquals(100, count);
    for (int i = 0; i < count; i++) {
      assertEquals(offset + i, array[i]);
    }

    // Drain next 200 docs
    count = stream.intoArray(offset + 300, array);
    assertEquals(200, count);
    for (int i = 0; i < count; i++) {
      assertEquals(offset + 100 + i, array[i]);
    }

    assertTrue(stream.mayHaveRemaining());
  }

  /**
   * Randomized test: random bitset size, random density, random array size, random offset.
   * Validates that intoArray produces the same docs as forEach.
   */
  public void testRandomized() throws IOException {
    for (int iter = 0; iter < 100; iter++) {
      int size = random().nextInt(1, 8193); // 1 to 8192
      int offset = random().nextInt(0, 100000);
      double density = random().nextDouble();
      int arraySize = random().nextInt(1, size + 1);

      FixedBitSet bitSet = new FixedBitSet(size);
      List<Integer> expected = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        if (random().nextDouble() < density) {
          bitSet.set(i);
          expected.add(offset + i);
        }
      }

      // Collect via intoArray
      BitSetDocIdStream stream1 = new BitSetDocIdStream(bitSet, offset);
      List<Integer> fromIntoArray = new ArrayList<>();
      int[] array = new int[arraySize];
      for (int count = stream1.intoArray(array); count > 0; count = stream1.intoArray(array)) {
        for (int i = 0; i < count; i++) {
          fromIntoArray.add(array[i]);
        }
      }

      // Collect via forEach
      BitSetDocIdStream stream2 = new BitSetDocIdStream(bitSet, offset);
      List<Integer> fromForEach = new ArrayList<>();
      stream2.forEach(doc -> fromForEach.add(doc));

      assertEquals(
          "iter=" + iter + " size=" + size + " density=" + density + " arraySize=" + arraySize,
          expected,
          fromIntoArray);
      assertEquals(
          "forEach mismatch iter=" + iter,
          expected,
          fromForEach);
    }
  }

  /**
   * Stress test: large bitset (65536 = 1024 words) with all bits set, small array (64).
   * Forces many iterations through the word boundary logic.
   */
  public void testLargeBitSetSmallArray() throws IOException {
    int size = 65536;
    FixedBitSet bitSet = new FixedBitSet(size);
    bitSet.set(0, size);

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
    List<Integer> collected = new ArrayList<>();
    int[] array = new int[64];
    for (int count = stream.intoArray(array); count > 0; count = stream.intoArray(array)) {
      for (int i = 0; i < count; i++) {
        collected.add(array[i]);
      }
    }

    assertEquals(size, collected.size());
    for (int i = 0; i < size; i++) {
      assertEquals(i, collected.get(i).intValue());
    }
  }

  /**
   * Test that consecutive intoArray calls with upTo=NO_MORE_DOCS work correctly
   * (this is how TopFieldCollector.collect(DocIdStream) calls it).
   */
  public void testConsecutiveCallsNoMoreDocs() throws IOException {
    int size = 4096;
    FixedBitSet bitSet = new FixedBitSet(size);
    // Set bits in a pattern: first 64, skip 64, next 64, etc.
    for (int i = 0; i < size; i++) {
      if ((i / 64) % 2 == 0) {
        bitSet.set(i);
      }
    }

    BitSetDocIdStream stream = new BitSetDocIdStream(bitSet, 0);
    List<Integer> collected = new ArrayList<>();
    int[] array = new int[128];
    // Use no-arg intoArray (upTo = NO_MORE_DOCS)
    for (int count = stream.intoArray(array); count > 0; count = stream.intoArray(array)) {
      for (int i = 0; i < count; i++) {
        collected.add(array[i]);
      }
    }

    // Verify against expected
    List<Integer> expected = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      if ((i / 64) % 2 == 0) {
        expected.add(i);
      }
    }
    assertEquals(expected, collected);
  }
}
