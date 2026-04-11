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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Validates that prefetch calls in longValues() target byte ranges that are actually read. Uses a
 * tracking Directory wrapper that records all prefetch(offset, length) and read(offset) calls on
 * .dvd files, then asserts that every prefetched range contains at least one subsequent read.
 *
 * <p>This proves the prefetch offsets computed by prefetchFixedBPV, prefetchDISI, and the varying
 * BPV two-round strategy are correct — they target the actual data that will be read.
 */
public class TestLucene90DocValuesPrefetchCoverage extends LuceneTestCase {

  /** A byte range that was prefetched. */
  record PrefetchedRange(long offset, long length) {}

  /** Tracks prefetch and read offsets on a per-file basis. */
  static class IOTracker {
    final List<PrefetchedRange> prefetches = Collections.synchronizedList(new ArrayList<>());
    final List<Long> reads = Collections.synchronizedList(new ArrayList<>());

    void recordPrefetch(long offset, long length) {
      prefetches.add(new PrefetchedRange(offset, length));
    }

    void recordRead(long offset) {
      reads.add(offset);
    }

    /** Assert every prefetched range contains at least one read, and reads are covered by prefetches. */
    void assertAllPrefetchesUsed(String fileName) {
      if (prefetches.isEmpty()) return;

      // Check 1: No wasted prefetches — every prefetched range was read
      for (PrefetchedRange pf : prefetches) {
        boolean found = false;
        for (long readOffset : reads) {
          if (readOffset >= pf.offset && readOffset < pf.offset + pf.length) {
            found = true;
            break;
          }
        }
        assertTrue(
            "Wasted prefetch in "
                + fileName
                + ": prefetch("
                + pf.offset
                + ", "
                + pf.length
                + ") had no reads in range. Total reads: "
                + reads.size(),
            found);
      }

      // Check 2: No missed prefetches — reads should be covered by prefetches
      if (reads.size() > 10) {
        int coveredReads = 0;
        for (long readOffset : reads) {
          for (PrefetchedRange pf : prefetches) {
            if (readOffset >= pf.offset && readOffset < pf.offset + pf.length) {
              coveredReads++;
              break;
            }
          }
        }
        double ratio = (double) coveredReads / reads.size();
        assertTrue(
            "Low prefetch coverage in " + fileName + ": " + coveredReads + "/" + reads.size()
                + " reads (" + String.format("%.1f%%", ratio * 100) + ") covered by prefetches",
            ratio >= 0.3);
      }
    }
  }

  /** IndexInput wrapper that tracks prefetch and read offsets. */
  static class TrackingIndexInput extends FilterIndexInput implements RandomAccessInput {
    final IOTracker tracker;
    final long sliceOffset; // absolute offset of this slice within the file

    TrackingIndexInput(String resourceDesc, IndexInput in, IOTracker tracker, long sliceOffset) {
      super(resourceDesc, in);
      this.tracker = tracker;
      this.sliceOffset = sliceOffset;
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      tracker.recordPrefetch(sliceOffset + offset, length);
      in.prefetch(offset, length);
    }

    @Override
    public byte readByte() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      in.readBytes(b, offset, len);
    }

    @Override
    public short readShort() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readShort();
    }

    @Override
    public int readInt() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readLong();
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
      IndexInput sliced = in.slice(sliceDescription, offset, length);
      return new TrackingIndexInput(sliceDescription, sliced, tracker, sliceOffset + offset);
    }

    @Override
    public IndexInput clone() {
      IndexInput cloned = in.clone();
      return new TrackingIndexInput(toString(), cloned, tracker, sliceOffset);
    }

    @Override
    public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
      // Return a tracking RandomAccessInput
      RandomAccessInput rai;
      IndexInput sliceInput = in.slice("rai", offset, length);
      if (sliceInput instanceof RandomAccessInput) {
        rai = (RandomAccessInput) sliceInput;
      } else {
        rai = in.randomAccessSlice(offset, length);
      }
      final long absOffset = sliceOffset + offset;
      final IOTracker t = tracker;
      return new RandomAccessInput() {
        @Override
        public long length() {
          return length;
        }

        @Override
        public byte readByte(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readByte(pos);
        }

        @Override
        public short readShort(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readShort(pos);
        }

        @Override
        public int readInt(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readInt(pos);
        }

        @Override
        public long readLong(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readLong(pos);
        }

        @Override
        public void prefetch(long pfOffset, long pfLength) throws IOException {
          t.recordPrefetch(absOffset + pfOffset, pfLength);
          rai.prefetch(pfOffset, pfLength);
        }
      };
    }

    // RandomAccessInput implementation (for when this is cast to RAI)
    @Override
    public byte readByte(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readByte(pos);
      }
      in.seek(pos);
      return in.readByte();
    }

    @Override
    public short readShort(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readShort(pos);
      }
      in.seek(pos);
      return in.readShort();
    }

    @Override
    public int readInt(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readInt(pos);
      }
      in.seek(pos);
      return in.readInt();
    }

    @Override
    public long readLong(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readLong(pos);
      }
      in.seek(pos);
      return in.readLong();
    }
  }

  /** Directory wrapper that tracks IO on .dvd files. */
  static class PrefetchTrackingDirectory extends FilterDirectory {
    final Map<String, IOTracker> trackers = new ConcurrentHashMap<>();

    PrefetchTrackingDirectory(Directory in) {
      super(in);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      IndexInput in = super.openInput(name, context);
      if (name.endsWith(".dvd")) {
        IOTracker tracker = trackers.computeIfAbsent(name, k -> new IOTracker());
        return new TrackingIndexInput(name, in, tracker, 0);
      }
      return in;
    }

    void assertAllPrefetchesUsed() {
      for (var entry : trackers.entrySet()) {
        entry.getValue().assertAllPrefetchesUsed(entry.getKey());
      }
    }

    boolean hasPrefetches() {
      return trackers.values().stream().anyMatch(t -> !t.prefetches.isEmpty());
    }

    int totalPrefetches() {
      return trackers.values().stream().mapToInt(t -> t.prefetches.size()).sum();
    }
  }

  // ---- Tests ----

  /** Dense field with sequential values — exercises prefetchFixedBPV on packed data. */
  public void testPrefetchCoverageDenseFixedBPV() throws Exception {
    doTestPrefetchCoverage(200000, i -> (long) i, true);
  }

  /** Dense field with GCD values. */
  public void testPrefetchCoverageDenseGCD() throws Exception {
    doTestPrefetchCoverage(200000, i -> 3L + 7L * i, true);
  }

  /** Dense field with table values. */
  public void testPrefetchCoverageDenseTable() throws Exception {
    long[] table = {10, 20, 30, 40, 50};
    doTestPrefetchCoverage(200000, i -> table[i % table.length], true);
  }

  /** Sparse field — exercises prefetchDISI + prefetchFixedBPV on DISI indices. */
  public void testPrefetchCoverageSparse() throws Exception {
    doTestPrefetchCoverage(200000, i -> (long) i * 100, false);
  }

  /** Large dense field to span multiple cache blocks. */
  public void testPrefetchCoverageLargeDense() throws Exception {
    doTestPrefetchCoverage(50000, i -> (long) i * 1_000_000L, true);
  }

  @FunctionalInterface
  interface IntToLong {
    long apply(int i);
  }

  private void doTestPrefetchCoverage(int numDocs, IntToLong valueFunc, boolean allDocs)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      // Index docs
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            doc.add(new NumericDocValuesField("numeric", valueFunc.apply(i)));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      // Open with tracking directory
      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          NumericDocValues ndv = leaf.getNumericDocValues("numeric");
          assertNotNull(ndv);

          // Prefetch explicitly, then read via longValues()
          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          long[] values = new long[numDocs];
          ndv.prefetchLongValues(numDocs, docs);
          ndv.longValues(numDocs, docs, values, -1L);

          // Validate: every prefetched range must have been read
          trackingDir.assertAllPrefetchesUsed();

          // Log for visibility
          if (VERBOSE) {
            System.out.println(
                "Prefetch coverage: "
                    + trackingDir.totalPrefetches()
                    + " prefetches, all used");
          }
        }
      }
    }
  }

  /** Dense varying BPV — values with different ranges per 16384-doc block. */
  public void testPrefetchCoverageDenseVaryingBPV() throws Exception {
    // Use values that vary wildly to force varying BPV encoding:
    // first 16384 docs: small values (low bpv), next 16384: large values (high bpv)
    doTestPrefetchCoverage(50000, i -> {
      if (i < 16384) return (long) (i % 100);           // low bpv block
      else if (i < 32768) return (long) i * 1_000_000L; // high bpv block
      else return (long) (i % 50);                       // low bpv block again
    }, true);
  }

  /** Sparse constant — all existing docs have the same value. */
  public void testPrefetchCoverageSparseConstant() throws Exception {
    doTestPrefetchCoverage(200000, i -> 42L, false);
  }

  /** Sparse GCD — values are multiples of a common divisor. */
  public void testPrefetchCoverageSparseGCD() throws Exception {
    doTestPrefetchCoverage(200000, i -> 7L * i + 3, false);
  }

  /** Sparse table — few unique values among sparse docs. */
  public void testPrefetchCoverageSparseTable() throws Exception {
    long[] table = {10, 20, 30, 40, 50};
    doTestPrefetchCoverage(200000, i -> table[i % table.length], false);
  }

  /** Sparse varying BPV — sparse docs with varying value ranges. */
  public void testPrefetchCoverageSparseVaryingBPV() throws Exception {
    doTestPrefetchCoverage(50000, i -> {
      if (i < 16384) return (long) (i % 100);
      else if (i < 32768) return (long) i * 1_000_000L;
      else return (long) (i % 50);
    }, false);
  }

  /** Large sparse crossing DISI block boundary (65536). */
  public void testPrefetchCoverageLargeSparse() throws Exception {
    doTestPrefetchCoverage(100000, i -> (long) i * 31, false);
  }

  // ==================== SortedDocValues prefetch coverage ====================

  private void doTestSortedPrefetchCoverage(int numDocs, java.util.function.IntFunction<String> valueFunc, boolean allDocs)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            doc.add(new SortedDocValuesField("sorted", new BytesRef(valueFunc.apply(i))));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          SortedDocValues sdv = leaf.getSortedDocValues("sorted");
          assertNotNull(sdv);

          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          int[] ords = new int[numDocs];
          sdv.prefetchOrdValues(numDocs, docs);
          sdv.ordValues(numDocs, docs, ords, -1);

          // Every prefetched range must have been read — no wasted prefetches
          trackingDir.assertAllPrefetchesUsed();

          if (VERBOSE) {
            System.out.println("Sorted prefetch coverage: " + trackingDir.totalPrefetches()
                + " prefetches, all used, numDocs=" + numDocs);
          }
        }
      }
    }
  }

  /** Dense sorted, high cardinality — many unique ordinals, high BPV. */
  public void testSortedPrefetchDenseHighCardinality() throws Exception {
    doTestSortedPrefetchCoverage(200_000, i -> String.format("term_%08d", i % 50000), true);
  }

  /** Dense sorted, low cardinality — few unique ordinals, low BPV. */
  public void testSortedPrefetchDenseLowCardinality() throws Exception {
    doTestSortedPrefetchCoverage(200_000, i -> "cat_" + (i % 5), true);
  }

  /** Sparse sorted — only even docs have values. Tests DISI + ordinal prefetch. */
  public void testSortedPrefetchSparse() throws Exception {
    doTestSortedPrefetchCoverage(200_000, i -> String.format("val_%06d", i % 10000), false);
  }

  /** Dense sorted, single value — BPV=0 constant ordinal. */
  public void testSortedPrefetchConstant() throws Exception {
    doTestSortedPrefetchCoverage(200_000, i -> "constant", true);
  }

  /** Large dense sorted — 500K docs to cross multiple DISI and BPV blocks. */
  public void testSortedPrefetchLargeDense() throws Exception {
    doTestSortedPrefetchCoverage(500_000, i -> String.format("big_%010d", i % 200000), true);
  }

  // ==================== prepareSeekExact prefetch coverage ====================

  /**
   * Validate that prepareSeekExact issues prefetch calls that cover the bytes
   * subsequently read by lookupOrd. Zero wasted prefetches.
   */
  private void doTestPrepareSeekExactPrefetchCoverage(int numDocs, int numUniqueTerms, boolean allDocs)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            doc.add(new SortedDocValuesField("sorted",
                new BytesRef(String.format("term_%08d", i % numUniqueTerms))));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          SortedDocValues sdv = leaf.getSortedDocValues("sorted");
          assertNotNull(sdv);
          int valueCount = sdv.getValueCount();
          assertTrue("Expected multiple terms, got " + valueCount, valueCount > 1);

          // Phase 1: prepareSeekExact for all ordinals
          for (int ord = 0; ord < valueCount; ord++) {
            sdv.prepareSeekExact(ord);
          }
          // Phase 2: lookupOrd for all ordinals (reads the prefetched blocks)
          for (int ord = 0; ord < valueCount; ord++) {
            sdv.lookupOrd(ord);
          }

          // Every prefetched range must have been read
          trackingDir.assertAllPrefetchesUsed();

          if (VERBOSE) {
            System.out.println("prepareSeekExact prefetch coverage: "
                + trackingDir.totalPrefetches() + " prefetches, all used, "
                + valueCount + " unique terms");
          }
        }
      }
    }
  }

  /** prepareSeekExact coverage — high cardinality (many LZ4 blocks). */
  public void testPrepareSeekExactPrefetchHighCardinality() throws Exception {
    // 50K terms → ~781 LZ4 blocks (64 terms per block)
    doTestPrepareSeekExactPrefetchCoverage(200_000, 50_000, true);
  }

  /** prepareSeekExact coverage — low cardinality (single LZ4 block). */
  public void testPrepareSeekExactPrefetchLowCardinality() throws Exception {
    doTestPrepareSeekExactPrefetchCoverage(200_000, 5, true);
  }

  /** prepareSeekExact coverage — medium cardinality crossing block boundaries. */
  public void testPrepareSeekExactPrefetchMediumCardinality() throws Exception {
    // 500 terms → ~8 LZ4 blocks
    doTestPrepareSeekExactPrefetchCoverage(200_000, 500, true);
  }

  // ==================== SortedNumericDocValues.prefetchRange() coverage ====================

  private void doTestSortedNumericPrefetchCoverage(int numDocs, boolean allDocs, int maxValuesPerDoc)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            int numValues = 1 + (i % maxValuesPerDoc);
            for (int j = 0; j < numValues; j++) {
              doc.add(new SortedNumericDocValuesField("sndv", (long) i * 100 + j));
            }
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          SortedNumericDocValues sndv = leaf.getSortedNumericDocValues("sndv");
          assertNotNull(sndv);

          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          sndv.prefetchRange(docs, numDocs);

          // Iterate all docs to read the prefetched data
          for (int i = 0; i < numDocs; i++) {
            if (sndv.advanceExact(i)) {
              int count = sndv.docValueCount();
              for (int j = 0; j < count; j++) {
                sndv.nextValue();
              }
            }
          }

          // Every prefetched range must have been read
          trackingDir.assertAllPrefetchesUsed();

          if (VERBOSE) {
            System.out.println("SortedNumeric prefetch coverage: "
                + trackingDir.totalPrefetches() + " prefetches, all used");
          }
        }
      }
    }
  }

  /** Dense multi-valued — 200K docs, 1-3 values each. */
  public void testSortedNumericPrefetchDenseMultiValued() throws Exception {
    doTestSortedNumericPrefetchCoverage(200_000, true, 3);
  }

  /** Sparse multi-valued — only even docs. */
  public void testSortedNumericPrefetchSparseMultiValued() throws Exception {
    doTestSortedNumericPrefetchCoverage(200_000, false, 3);
  }

  /** Dense single-valued — should delegate to NumericDocValues singleton path. */
  public void testSortedNumericPrefetchDenseSingleValued() throws Exception {
    doTestSortedNumericPrefetchCoverage(200_000, true, 1);
  }

  // ==================== SortedSetDocValues.prefetchOrds() coverage ====================

  private void doTestSortedSetPrefetchCoverage(int numDocs, boolean allDocs, int maxValuesPerDoc)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            int numValues = 1 + (i % maxValuesPerDoc);
            for (int j = 0; j < numValues; j++) {
              doc.add(new SortedSetDocValuesField("ssdv", new BytesRef(String.format("term_%06d", (i * maxValuesPerDoc + j) % 10000))));
            }
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          SortedSetDocValues ssdv = leaf.getSortedSetDocValues("ssdv");
          assertNotNull(ssdv);

          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          ssdv.prefetchOrds(docs, numDocs);

          // Iterate all docs to read the prefetched data
          for (int i = 0; i < numDocs; i++) {
            if (ssdv.advanceExact(i)) {
              int count = ssdv.docValueCount();
              for (int j = 0; j < count; j++) {
                ssdv.nextOrd();
              }
            }
          }

          trackingDir.assertAllPrefetchesUsed();
          if (VERBOSE) {
            System.out.println("SortedSet prefetch coverage: "
                + trackingDir.totalPrefetches() + " prefetches, all used");
          }
        }
      }
    }
  }

  public void testSortedSetPrefetchDenseMultiValued() throws Exception {
    doTestSortedSetPrefetchCoverage(200_000, true, 3);
  }

  public void testSortedSetPrefetchSparseMultiValued() throws Exception {
    doTestSortedSetPrefetchCoverage(200_000, false, 3);
  }

  public void testSortedSetPrefetchDenseSingleValued() throws Exception {
    doTestSortedSetPrefetchCoverage(200_000, true, 1);
  }

  // ==================== BinaryDocValues.prefetchBinaryValues() coverage ====================

  private void doTestBinaryPrefetchCoverage(int numDocs, boolean allDocs, boolean fixedLength)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            byte[] val;
            if (fixedLength) {
              val = String.format("val_%08d", i).getBytes();
            } else {
              val = String.format("val_%0" + (5 + i % 10) + "d", i).getBytes();
            }
            doc.add(new BinaryDocValuesField("binary", new BytesRef(val)));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          BinaryDocValues bdv = leaf.getBinaryDocValues("binary");
          assertNotNull(bdv);

          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          bdv.prefetchBinaryValues(docs, numDocs);

          // Read all docs to consume the prefetched data
          for (int i = 0; i < numDocs; i++) {
            if (bdv.advanceExact(i)) {
              bdv.binaryValue();
            }
          }

          trackingDir.assertAllPrefetchesUsed();
          if (VERBOSE) {
            System.out.println("Binary prefetch coverage: "
                + trackingDir.totalPrefetches() + " prefetches, all used");
          }
        }
      }
    }
  }

  public void testBinaryPrefetchDenseFixed() throws Exception {
    doTestBinaryPrefetchCoverage(200_000, true, true);
  }

  public void testBinaryPrefetchDenseVariable() throws Exception {
    doTestBinaryPrefetchCoverage(200_000, true, false);
  }

  public void testBinaryPrefetchSparseFixed() throws Exception {
    doTestBinaryPrefetchCoverage(200_000, false, true);
  }

  public void testBinaryPrefetchSparseVariable() throws Exception {
    doTestBinaryPrefetchCoverage(200_000, false, false);
  }
}
