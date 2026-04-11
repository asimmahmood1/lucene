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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.TestUtil;

/**
 * Extends {@link TestLucene90DocValuesFormat} and overrides {@code assertDVIterate} to validate
 * three things for every inherited NumericsVsStoredFields test:
 *
 * <ol>
 *   <li>Value correctness: longValues() bulk API matches stored fields (ground truth)
 *   <li>Prefetch coverage: every prefetched byte range in .dvd files is subsequently read
 *   <li>No wasted prefetch: no prefetch targets data that is never read
 * </ol>
 *
 * <p>This gives confidence across all encoding variants (constant, table, GCD, plain, varying BPV,
 * sparse) that the prefetch offsets computed by prefetchFixedBPV, prefetchDISI, and the varying BPV
 * two-round strategy are correct.
 */
public class TestLucene90DocValuesBulkReadViaExistingSuite extends TestLucene90DocValuesFormat {

  @Override
  protected void assertDVIterate(Directory dir) throws IOException {
    // First: run the original per-doc validation
    super.assertDVIterate(dir);

    // Second: validate longValues() correctness AND prefetch coverage
    PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(dir);
    try (DirectoryReader ir = DirectoryReader.open(trackingDir)) {
      TestUtil.checkReader(ir);
      for (LeafReaderContext context : ir.leaves()) {
        LeafReader r = context.reader();
        int maxDoc = r.maxDoc();
        if (maxDoc == 0) continue;

        // Collect ground truth from stored fields
        long[] expected = new long[maxDoc];
        boolean[] hasValue = new boolean[maxDoc];
        var storedFields = r.storedFields();
        for (int i = 0; i < maxDoc; i++) {
          String storedValue = storedFields.document(i).get("stored");
          if (storedValue != null) {
            expected[i] = Long.parseLong(storedValue);
            hasValue[i] = true;
          }
        }

        long defaultValue = Long.MIN_VALUE + 42;

        // Full batch read via longValues()
        NumericDocValues ndv = DocValues.getNumeric(r, "dv");
        int[] docs = new int[maxDoc];
        for (int i = 0; i < maxDoc; i++) docs[i] = i;
        long[] actual = new long[maxDoc];
        ndv.longValues(maxDoc, docs, actual, defaultValue);

        for (int i = 0; i < maxDoc; i++) {
          if (hasValue[i]) {
            assertEquals("longValues() mismatch at doc " + i, expected[i], actual[i]);
          } else {
            assertEquals(
                "longValues() missing doc " + i + " should be default", defaultValue, actual[i]);
          }
        }

        // Small batch read (64 docs) to exercise chunked paths
        ndv = DocValues.getNumeric(r, "dv");
        int batchSize = 64;
        int[] batchDocs = new int[batchSize];
        long[] batchActual = new long[batchSize];
        for (int start = 0; start < maxDoc; start += batchSize) {
          int count = Math.min(batchSize, maxDoc - start);
          for (int i = 0; i < count; i++) batchDocs[i] = start + i;
          ndv.longValues(count, batchDocs, batchActual, defaultValue);
          for (int i = 0; i < count; i++) {
            long exp = hasValue[start + i] ? expected[start + i] : defaultValue;
            assertEquals(
                "longValues() batch mismatch at doc " + (start + i), exp, batchActual[i]);
          }
        }
      }
    }

    // Validate prefetch coverage: every prefetched range must have been read
    trackingDir.assertAllPrefetchesUsed();
  }

  // ---- Prefetch tracking infrastructure ----

  /** Tracks prefetch and read offsets on a per-file basis. */
  static class IOTracker {
    final List<long[]> prefetches = Collections.synchronizedList(new ArrayList<>());
    final List<Long> reads = Collections.synchronizedList(new ArrayList<>());

    void recordPrefetch(long offset, long length) {
      prefetches.add(new long[] {offset, length});
    }

    void recordRead(long offset) {
      reads.add(offset);
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
        String file = entry.getKey();
        IOTracker tracker = entry.getValue();
        for (long[] pf : tracker.prefetches) {
          long pfOffset = pf[0];
          long pfLength = pf[1];
          boolean found = false;
          for (long readOffset : tracker.reads) {
            if (readOffset >= pfOffset && readOffset < pfOffset + pfLength) {
              found = true;
              break;
            }
          }
          assertTrue(
              "Wasted prefetch in "
                  + file
                  + ": prefetch("
                  + pfOffset
                  + ", "
                  + pfLength
                  + ") — no reads in range. "
                  + tracker.prefetches.size()
                  + " prefetches, "
                  + tracker.reads.size()
                  + " reads total",
              found);
        }
      }
    }
  }

  /** IndexInput wrapper that tracks prefetch and read offsets with absolute file positions. */
  static class TrackingIndexInput extends FilterIndexInput implements RandomAccessInput {
    final IOTracker tracker;
    final long sliceOffset;

    TrackingIndexInput(String desc, IndexInput in, IOTracker tracker, long sliceOffset) {
      super(desc, in);
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
      return new TrackingIndexInput(
          sliceDescription, in.slice(sliceDescription, offset, length), tracker, sliceOffset + offset);
    }

    @Override
    public IndexInput clone() {
      return new TrackingIndexInput(toString(), in.clone(), tracker, sliceOffset);
    }

    @Override
    public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
      final long absOffset = sliceOffset + offset;
      final IOTracker t = tracker;
      // Get the underlying RAI
      IndexInput sliceInput = in.slice("rai", offset, length);
      final RandomAccessInput delegate =
          sliceInput instanceof RandomAccessInput
              ? (RandomAccessInput) sliceInput
              : in.randomAccessSlice(offset, length);
      return new RandomAccessInput() {
        @Override
        public long length() {
          return length;
        }

        @Override
        public byte readByte(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return delegate.readByte(pos);
        }

        @Override
        public short readShort(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return delegate.readShort(pos);
        }

        @Override
        public int readInt(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return delegate.readInt(pos);
        }

        @Override
        public long readLong(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return delegate.readLong(pos);
        }

        @Override
        public void prefetch(long pfOffset, long pfLength) throws IOException {
          t.recordPrefetch(absOffset + pfOffset, pfLength);
          delegate.prefetch(pfOffset, pfLength);
        }
      };
    }

    // RandomAccessInput implementation
    @Override
    public byte readByte(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      return in instanceof RandomAccessInput rai ? rai.readByte(pos) : seekAndRead(pos);
    }

    @Override
    public short readShort(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      return in instanceof RandomAccessInput rai ? rai.readShort(pos) : seekAndReadShort(pos);
    }

    @Override
    public int readInt(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      return in instanceof RandomAccessInput rai ? rai.readInt(pos) : seekAndReadInt(pos);
    }

    @Override
    public long readLong(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      return in instanceof RandomAccessInput rai ? rai.readLong(pos) : seekAndReadLong(pos);
    }

    private byte seekAndRead(long pos) throws IOException {
      in.seek(pos);
      return in.readByte();
    }

    private short seekAndReadShort(long pos) throws IOException {
      in.seek(pos);
      return in.readShort();
    }

    private int seekAndReadInt(long pos) throws IOException {
      in.seek(pos);
      return in.readInt();
    }

    private long seekAndReadLong(long pos) throws IOException {
      in.seek(pos);
      return in.readLong();
    }
  }
}
