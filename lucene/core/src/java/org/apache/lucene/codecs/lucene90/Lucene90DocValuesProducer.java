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

import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.SKIP_INDEX_JUMP_LENGTH_PER_LEVEL;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.SKIP_INDEX_MAX_LEVEL;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.TERMS_DICT_BLOCK_LZ4_SHIFT;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.TERMS_DICT_BLOCK_LZ4_SIZE;

import java.io.IOException;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesSkipIndexType;
import org.apache.lucene.index.DocValuesSkipper;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.search.PrefetchConfig;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.FileTypeHint;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.compress.LZ4;
import org.apache.lucene.util.packed.DirectMonotonicReader;
import org.apache.lucene.util.packed.DirectReader;

/** reader for {@link Lucene90DocValuesFormat} */
final class Lucene90DocValuesProducer extends DocValuesProducer {
  private final IntObjectHashMap<NumericEntry> numerics;
  private final IntObjectHashMap<BinaryEntry> binaries;
  private final IntObjectHashMap<SortedEntry> sorted;
  private final IntObjectHashMap<SortedSetEntry> sortedSets;
  private final IntObjectHashMap<SortedNumericEntry> sortedNumerics;
  private final IntObjectHashMap<DocValuesSkipperEntry> skippers;
  private final IndexInput data;
  private final int maxDoc;
  private int version = -1;
  private final boolean merging;

  /** expert: instantiates a new reader */
  Lucene90DocValuesProducer(
      SegmentReadState state,
      String dataCodec,
      String dataExtension,
      String metaCodec,
      String metaExtension)
      throws IOException {
    String metaName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);
    this.maxDoc = state.segmentInfo.maxDoc();
    numerics = new IntObjectHashMap<>();
    binaries = new IntObjectHashMap<>();
    sorted = new IntObjectHashMap<>();
    sortedSets = new IntObjectHashMap<>();
    sortedNumerics = new IntObjectHashMap<>();
    skippers = new IntObjectHashMap<>();
    merging = false;

    // read in the entries from the metadata file.
    try (ChecksumIndexInput in = state.directory.openChecksumInput(metaName)) {
      Throwable priorE = null;

      try {
        version =
            CodecUtil.checkIndexHeader(
                in,
                metaCodec,
                Lucene90DocValuesFormat.VERSION_START,
                Lucene90DocValuesFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);

        readFields(in, state.fieldInfos);

      } catch (Throwable exception) {
        priorE = exception;
      } finally {
        CodecUtil.checkFooter(in, priorE);
      }
    }

    String dataName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
    // Doc-values have a forward-only access pattern
    this.data = state.directory.openInput(dataName, state.context.withHints(FileTypeHint.DATA));
    boolean success = false;
    try {
      final int version2 =
          CodecUtil.checkIndexHeader(
              data,
              dataCodec,
              Lucene90DocValuesFormat.VERSION_START,
              Lucene90DocValuesFormat.VERSION_CURRENT,
              state.segmentInfo.getId(),
              state.segmentSuffix);
      if (version != version2) {
        throw new CorruptIndexException(
            "Format versions mismatch: meta=" + version + ", data=" + version2, data);
      }

      // NOTE: data file is too costly to verify checksum against all the bytes on open,
      // but for now we at least verify proper structure of the checksum footer: which looks
      // for FOOTER_MAGIC + algorithmID. This is cheap and can detect some forms of corruption
      // such as file truncation.
      CodecUtil.retrieveChecksum(data);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this.data);
      }
    }
  }

  // Used for cloning
  private Lucene90DocValuesProducer(
      IntObjectHashMap<NumericEntry> numerics,
      IntObjectHashMap<BinaryEntry> binaries,
      IntObjectHashMap<SortedEntry> sorted,
      IntObjectHashMap<SortedSetEntry> sortedSets,
      IntObjectHashMap<SortedNumericEntry> sortedNumerics,
      IntObjectHashMap<DocValuesSkipperEntry> skippers,
      IndexInput data,
      int maxDoc,
      int version,
      boolean merging) {
    this.numerics = numerics;
    this.binaries = binaries;
    this.sorted = sorted;
    this.sortedSets = sortedSets;
    this.sortedNumerics = sortedNumerics;
    this.skippers = skippers;
    this.data = data.clone();
    this.maxDoc = maxDoc;
    this.version = version;
    this.merging = merging;
  }

  @Override
  public DocValuesProducer getMergeInstance() {
    return new Lucene90DocValuesProducer(
        numerics,
        binaries,
        sorted,
        sortedSets,
        sortedNumerics,
        skippers,
        data,
        maxDoc,
        version,
        true);
  }

  private void readFields(IndexInput meta, FieldInfos infos) throws IOException {
    for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
      FieldInfo info = infos.fieldInfo(fieldNumber);
      if (info == null) {
        throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
      }
      byte type = meta.readByte();
      if (info.docValuesSkipIndexType() != DocValuesSkipIndexType.NONE) {
        skippers.put(info.number, readDocValueSkipperMeta(meta));
      }
      if (type == Lucene90DocValuesFormat.NUMERIC) {
        numerics.put(info.number, readNumeric(meta));
      } else if (type == Lucene90DocValuesFormat.BINARY) {
        binaries.put(info.number, readBinary(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED) {
        sorted.put(info.number, readSorted(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED_SET) {
        sortedSets.put(info.number, readSortedSet(meta));
      } else if (type == Lucene90DocValuesFormat.SORTED_NUMERIC) {
        sortedNumerics.put(info.number, readSortedNumeric(meta));
      } else {
        throw new CorruptIndexException("invalid type: " + type, meta);
      }
    }
  }

  private NumericEntry readNumeric(IndexInput meta) throws IOException {
    NumericEntry entry = new NumericEntry();
    readNumeric(meta, entry);
    return entry;
  }

  private DocValuesSkipperEntry readDocValueSkipperMeta(IndexInput meta) throws IOException {
    long offset = meta.readLong();
    long length = meta.readLong();
    long maxValue = meta.readLong();
    long minValue = meta.readLong();
    int docCount = meta.readInt();
    int maxDocID = meta.readInt();

    return new DocValuesSkipperEntry(offset, length, minValue, maxValue, docCount, maxDocID);
  }

  private void readNumeric(IndexInput meta, NumericEntry entry) throws IOException {
    entry.docsWithFieldOffset = meta.readLong();
    entry.docsWithFieldLength = meta.readLong();
    entry.jumpTableEntryCount = meta.readShort();
    entry.denseRankPower = meta.readByte();
    entry.numValues = meta.readLong();
    int tableSize = meta.readInt();
    if (tableSize > 256) {
      throw new CorruptIndexException("invalid table size: " + tableSize, meta);
    }
    if (tableSize >= 0) {
      entry.table = new long[tableSize];
      for (int i = 0; i < tableSize; ++i) {
        entry.table[i] = meta.readLong();
      }
    }
    if (tableSize < -1) {
      entry.blockShift = -2 - tableSize;
    } else {
      entry.blockShift = -1;
    }
    entry.bitsPerValue = meta.readByte();
    entry.minValue = meta.readLong();
    entry.gcd = meta.readLong();
    entry.valuesOffset = meta.readLong();
    entry.valuesLength = meta.readLong();
    entry.valueJumpTableOffset = meta.readLong();
  }

  private BinaryEntry readBinary(IndexInput meta) throws IOException {
    final BinaryEntry entry = new BinaryEntry();
    entry.dataOffset = meta.readLong();
    entry.dataLength = meta.readLong();
    entry.docsWithFieldOffset = meta.readLong();
    entry.docsWithFieldLength = meta.readLong();
    entry.jumpTableEntryCount = meta.readShort();
    entry.denseRankPower = meta.readByte();
    entry.numDocsWithField = meta.readInt();
    entry.minLength = meta.readInt();
    entry.maxLength = meta.readInt();
    if (entry.minLength < entry.maxLength) {
      entry.addressesOffset = meta.readLong();

      // Old count of uncompressed addresses
      long numAddresses = entry.numDocsWithField + 1L;

      final int blockShift = meta.readVInt();
      entry.addressesMeta = DirectMonotonicReader.loadMeta(meta, numAddresses, blockShift);
      entry.addressesLength = meta.readLong();
    }
    return entry;
  }

  private SortedEntry readSorted(IndexInput meta) throws IOException {
    SortedEntry entry = new SortedEntry();
    entry.ordsEntry = new NumericEntry();
    readNumeric(meta, entry.ordsEntry);
    entry.termsDictEntry = new TermsDictEntry();
    readTermDict(meta, entry.termsDictEntry);
    return entry;
  }

  private SortedSetEntry readSortedSet(IndexInput meta) throws IOException {
    SortedSetEntry entry = new SortedSetEntry();
    byte multiValued = meta.readByte();
    switch (multiValued) {
      case 0: // singlevalued
        entry.singleValueEntry = readSorted(meta);
        return entry;
      case 1: // multivalued
        break;
      default:
        throw new CorruptIndexException("Invalid multiValued flag: " + multiValued, meta);
    }
    entry.ordsEntry = new SortedNumericEntry();
    readSortedNumeric(meta, entry.ordsEntry);
    entry.termsDictEntry = new TermsDictEntry();
    readTermDict(meta, entry.termsDictEntry);
    return entry;
  }

  private static void readTermDict(IndexInput meta, TermsDictEntry entry) throws IOException {
    entry.termsDictSize = meta.readVLong();
    final int blockShift = meta.readInt();
    final long addressesSize =
        (entry.termsDictSize + (1L << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1)
            >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
    entry.termsAddressesMeta = DirectMonotonicReader.loadMeta(meta, addressesSize, blockShift);
    entry.maxTermLength = meta.readInt();
    entry.maxBlockLength = meta.readInt();
    entry.termsDataOffset = meta.readLong();
    entry.termsDataLength = meta.readLong();
    entry.termsAddressesOffset = meta.readLong();
    entry.termsAddressesLength = meta.readLong();
    entry.termsDictIndexShift = meta.readInt();
    final long indexSize =
        (entry.termsDictSize + (1L << entry.termsDictIndexShift) - 1) >>> entry.termsDictIndexShift;
    entry.termsIndexAddressesMeta = DirectMonotonicReader.loadMeta(meta, 1 + indexSize, blockShift);
    entry.termsIndexOffset = meta.readLong();
    entry.termsIndexLength = meta.readLong();
    entry.termsIndexAddressesOffset = meta.readLong();
    entry.termsIndexAddressesLength = meta.readLong();
  }

  private SortedNumericEntry readSortedNumeric(IndexInput meta) throws IOException {
    SortedNumericEntry entry = new SortedNumericEntry();
    readSortedNumeric(meta, entry);
    return entry;
  }

  private SortedNumericEntry readSortedNumeric(IndexInput meta, SortedNumericEntry entry)
      throws IOException {
    readNumeric(meta, entry);
    entry.numDocsWithField = meta.readInt();
    if (entry.numDocsWithField != entry.numValues) {
      entry.addressesOffset = meta.readLong();
      final int blockShift = meta.readVInt();
      entry.addressesMeta =
          DirectMonotonicReader.loadMeta(meta, entry.numDocsWithField + 1, blockShift);
      entry.addressesLength = meta.readLong();
    }
    return entry;
  }

  @Override
  public void close() throws IOException {
    data.close();
  }

  private record DocValuesSkipperEntry(
      long offset, long length, long minValue, long maxValue, int docCount, int maxDocId) {}

  private static class NumericEntry {
    long[] table;
    int blockShift;
    byte bitsPerValue;
    long docsWithFieldOffset;
    long docsWithFieldLength;
    short jumpTableEntryCount;
    byte denseRankPower;
    long numValues;
    long minValue;
    long gcd;
    long valuesOffset;
    long valuesLength;
    long valueJumpTableOffset; // -1 if no jump-table
  }

  private static class BinaryEntry {
    long dataOffset;
    long dataLength;
    long docsWithFieldOffset;
    long docsWithFieldLength;
    short jumpTableEntryCount;
    byte denseRankPower;
    int numDocsWithField;
    int minLength;
    int maxLength;
    long addressesOffset;
    long addressesLength;
    DirectMonotonicReader.Meta addressesMeta;
  }

  private static class TermsDictEntry {
    long termsDictSize;
    DirectMonotonicReader.Meta termsAddressesMeta;
    int maxTermLength;
    long termsDataOffset;
    long termsDataLength;
    long termsAddressesOffset;
    long termsAddressesLength;
    int termsDictIndexShift;
    DirectMonotonicReader.Meta termsIndexAddressesMeta;
    long termsIndexOffset;
    long termsIndexLength;
    long termsIndexAddressesOffset;
    long termsIndexAddressesLength;

    int maxBlockLength;
  }

  private static class SortedEntry {
    NumericEntry ordsEntry;
    TermsDictEntry termsDictEntry;
  }

  private static class SortedSetEntry {
    SortedEntry singleValueEntry;
    SortedNumericEntry ordsEntry;
    TermsDictEntry termsDictEntry;
  }

  private static class SortedNumericEntry extends NumericEntry {
    int numDocsWithField;
    DirectMonotonicReader.Meta addressesMeta;
    long addressesOffset;
    long addressesLength;
  }

  @Override
  public NumericDocValues getNumeric(FieldInfo field) throws IOException {
    NumericEntry entry = numerics.get(field.number);
    return getNumeric(entry);
  }

  private abstract static class DenseNumericDocValues extends NumericDocValues {

    final int maxDoc;
    int doc = -1;

    DenseNumericDocValues(int maxDoc) {
      this.maxDoc = maxDoc;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) throws IOException {
      if (target >= maxDoc) {
        return doc = NO_MORE_DOCS;
      }
      return doc = target;
    }

    @Override
    public boolean advanceExact(int target) {
      doc = target;
      return true;
    }

    @Override
    public long cost() {
      return maxDoc;
    }

    @Override
    public int docIDRunEnd() throws IOException {
      return maxDoc;
    }
  }

  private abstract static class SparseNumericDocValues extends NumericDocValues {

    final IndexedDISI disi;

    SparseNumericDocValues(IndexedDISI disi) {
      this.disi = disi;
    }

    @Override
    public int advance(int target) throws IOException {
      return disi.advance(target);
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      return disi.advanceExact(target);
    }

    @Override
    public int nextDoc() throws IOException {
      return disi.nextDoc();
    }

    @Override
    public int docID() {
      return disi.docID();
    }

    @Override
    public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
      disi.intoBitSet(upTo, bitSet, offset);
    }

    @Override
    public long cost() {
      return disi.cost();
    }

    @Override
    public int docIDRunEnd() throws IOException {
      return disi.docIDRunEnd();
    }
  }

  private LongValues getDirectReaderInstance(
      RandomAccessInput slice, int bitsPerValue, long offset, long numValues) {
    if (merging) {
      return DirectReader.getMergeInstance(slice, bitsPerValue, offset, numValues);
    } else {
      return DirectReader.getInstance(slice, bitsPerValue, offset);
    }
  }

  private NumericDocValues getNumeric(NumericEntry entry) throws IOException {
    if (entry.docsWithFieldOffset == -2) {
      // empty
      return DocValues.emptyNumeric();
    } else if (entry.docsWithFieldOffset == -1) {
      // dense
      if (entry.bitsPerValue == 0) {
        return new DenseNumericDocValues(maxDoc) {
          @Override
          public long longValue() throws IOException {
            return entry.minValue;
          }
        };
      } else {
        final RandomAccessInput slice =
            data.randomAccessSlice(entry.valuesOffset, entry.valuesLength);
        // Prefetch the first page of data. Following pages are expected to get prefetched through
        // read-ahead.
        if (slice.length() > 0) {
          slice.prefetch(0, 1);
        }
        if (entry.blockShift >= 0) {
          // dense but split into blocks of different bits per value
          return new DenseNumericDocValues(maxDoc) {
            final VaryingBPVReader vBPVReader = new VaryingBPVReader(entry, slice);

            @Override
            public long longValue() throws IOException {
              return vBPVReader.getLongValue(doc);
            }

            @Override
            public void prefetchLongValues(int size, int[] docs) throws IOException {
              if (PrefetchConfig.isEnabled() && size > 0) {
                prefetchVaryingBPV(vBPVReader, size, docs);
              }
            }
          };
        } else {
          final LongValues values =
              getDirectReaderInstance(slice, entry.bitsPerValue, 0L, entry.numValues);
          if (entry.table != null) {
            final long[] table = entry.table;
            return new DenseNumericDocValues(maxDoc) {
              @Override
              public long longValue() throws IOException {
                return table[(int) values.get(doc)];
              }

          @Override
          public void prefetchLongValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && entry.bitsPerValue > 0) {
              prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
            }
          }
            };
          } else if (entry.gcd == 1 && entry.minValue == 0) {
            // Common case for ordinals, which are encoded as numerics
            return new DenseNumericDocValues(maxDoc) {
              @Override
              public long longValue() throws IOException {
                return values.get(doc);
              }

          @Override
          public void prefetchLongValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && entry.bitsPerValue > 0) {
              prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
            }
          }
            };
          } else {
            final long mul = entry.gcd;
            final long delta = entry.minValue;
            return new DenseNumericDocValues(maxDoc) {
              @Override
              public long longValue() throws IOException {
                return mul * values.get(doc) + delta;
              }

          @Override
          public void prefetchLongValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && entry.bitsPerValue > 0) {
              prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
            }
          }
            };
          }
        }
      }
    } else {
      // sparse
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numValues);
      if (entry.bitsPerValue == 0) {
        return new SparseNumericDocValues(disi) {
          @Override
          public long longValue() throws IOException {
            return entry.minValue;
          }

          @Override
          public void prefetchLongValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              prefetchDISI(disi, size, docs);
            }
          }
        };
      } else {
        final RandomAccessInput slice =
            data.randomAccessSlice(entry.valuesOffset, entry.valuesLength);
        // Prefetch the first page of data. Following pages are expected to get prefetched through
        // read-ahead.
        if (slice.length() > 0) {
          slice.prefetch(0, 1);
        }
        if (entry.blockShift >= 0) {
          // sparse and split into blocks of different bits per value
          return new SparseNumericDocValues(disi) {
            final VaryingBPVReader vBPVReader = new VaryingBPVReader(entry, slice);

            @Override
            public long longValue() throws IOException {
              final int index = disi.index();
              return vBPVReader.getLongValue(index);
            }

            @Override
            public void prefetchLongValues(int size, int[] docs) throws IOException {
              if (PrefetchConfig.isEnabled() && size > 0) {
                prefetchDISI(disi, size, docs);
                if (entry.bitsPerValue > 0) {
                  prefetchVaryingBPV(vBPVReader, size, docs);
                }
              }
            }
          };
        } else {
          final LongValues values =
              getDirectReaderInstance(slice, entry.bitsPerValue, 0L, entry.numValues);
          if (entry.table != null) {
            final long[] table = entry.table;
            return new SparseNumericDocValues(disi) {
              @Override
              public long longValue() throws IOException {
                return table[(int) values.get(disi.index())];
              }

              @Override
              public void prefetchLongValues(int size, int[] docs) throws IOException {
                if (PrefetchConfig.isEnabled() && size > 0) {
                  prefetchDISI(disi, size, docs);
                  if (entry.bitsPerValue > 0) {
                    prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
                  }
                }
              }
            };
          } else if (entry.gcd == 1 && entry.minValue == 0) {
            return new SparseNumericDocValues(disi) {
              @Override
              public long longValue() throws IOException {
                return values.get(disi.index());
              }

              @Override
              public void prefetchLongValues(int size, int[] docs) throws IOException {
                if (PrefetchConfig.isEnabled() && size > 0) {
                  prefetchDISI(disi, size, docs);
                  if (entry.bitsPerValue > 0) {
                    prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
                  }
                }
              }
            };
          } else {
            final long mul = entry.gcd;
            final long delta = entry.minValue;
            return new SparseNumericDocValues(disi) {
              @Override
              public long longValue() throws IOException {
                return mul * values.get(disi.index()) + delta;
              }

              @Override
              public void prefetchLongValues(int size, int[] docs) throws IOException {
                if (PrefetchConfig.isEnabled() && size > 0) {
                  prefetchDISI(disi, size, docs);
                  if (entry.bitsPerValue > 0) {
                    prefetchFixedBPV(size, docs, slice, entry.bitsPerValue);
                  }
                }
              }
            };
          }
        }
      }
    }
  }

  private LongValues getNumericValues(NumericEntry entry) throws IOException {
    if (entry.bitsPerValue == 0) {
      return new LongValues() {
        @Override
        public long get(long index) {
          return entry.minValue;
        }
      };
    } else {
      final RandomAccessInput slice =
          data.randomAccessSlice(entry.valuesOffset, entry.valuesLength);
      // Prefetch the first page of data. Following pages are expected to get prefetched through
      // read-ahead.
      if (slice.length() > 0) {
        slice.prefetch(0, 1);
      }
      if (entry.blockShift >= 0) {
        return new LongValues() {
          final VaryingBPVReader vBPVReader = new VaryingBPVReader(entry, slice);

          @Override
          public long get(long index) {
            try {
              return vBPVReader.getLongValue(index);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };
      } else {
        final LongValues values =
            getDirectReaderInstance(slice, entry.bitsPerValue, 0L, entry.numValues);
        if (entry.table != null) {
          final long[] table = entry.table;
          return new LongValues() {
            @Override
            public long get(long index) {
              return table[(int) values.get(index)];
            }
          };
        } else if (entry.gcd != 1) {
          final long gcd = entry.gcd;
          final long minValue = entry.minValue;
          return new LongValues() {
            @Override
            public long get(long index) {
              return values.get(index) * gcd + minValue;
            }
          };
        } else if (entry.minValue != 0) {
          final long minValue = entry.minValue;
          return new LongValues() {
            @Override
            public long get(long index) {
              return values.get(index) + minValue;
            }
          };
        } else {
          return values;
        }
      }
    }
  }

  private abstract static class DenseBinaryDocValues extends BinaryDocValues {

    final int maxDoc;
    int doc = -1;

    DenseBinaryDocValues(int maxDoc) {
      this.maxDoc = maxDoc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public long cost() {
      return maxDoc;
    }

    @Override
    public int advance(int target) throws IOException {
      if (target >= maxDoc) {
        return doc = NO_MORE_DOCS;
      }
      return doc = target;
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      doc = target;
      return true;
    }

    @Override
    public int docIDRunEnd() throws IOException {
      return maxDoc;
    }
  }

  private abstract static class SparseBinaryDocValues extends BinaryDocValues {

    final IndexedDISI disi;

    SparseBinaryDocValues(IndexedDISI disi) {
      this.disi = disi;
    }

    @Override
    public int nextDoc() throws IOException {
      return disi.nextDoc();
    }

    @Override
    public int docID() {
      return disi.docID();
    }

    @Override
    public long cost() {
      return disi.cost();
    }

    @Override
    public int advance(int target) throws IOException {
      return disi.advance(target);
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      return disi.advanceExact(target);
    }

    @Override
    public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
      disi.intoBitSet(upTo, bitSet, offset);
    }

    @Override
    public int docIDRunEnd() throws IOException {
      return disi.docIDRunEnd();
    }
  }

  @Override
  public BinaryDocValues getBinary(FieldInfo field) throws IOException {
    BinaryEntry entry = binaries.get(field.number);

    if (entry.docsWithFieldOffset == -2) {
      return DocValues.emptyBinary();
    }

    final RandomAccessInput bytesSlice = data.randomAccessSlice(entry.dataOffset, entry.dataLength);
    // Prefetch the first page of data. Following pages are expected to get prefetched through
    // read-ahead.
    if (bytesSlice.length() > 0) {
      bytesSlice.prefetch(0, 1);
    }

    if (entry.docsWithFieldOffset == -1) {
      // dense
      if (entry.minLength == entry.maxLength) {
        // fixed length
        final int length = entry.maxLength;
        return new DenseBinaryDocValues(maxDoc) {
          final BytesRef bytes = new BytesRef(new byte[length], 0, length);

          @Override
          public BytesRef binaryValue() throws IOException {
            bytesSlice.readBytes((long) doc * length, bytes.bytes, 0, length);
            return bytes;
          }

          @Override
          public void prefetchBinaryValues(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && length > 0) {
              long firstByte = (long) docs[0] * length;
              long lastByte = (long) docs[size - 1] * length + length;
              bytesSlice.prefetch(firstByte, lastByte - firstByte);
            }
          }
        };
      } else {
        // variable length
        final RandomAccessInput addressesData =
            this.data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
        // Prefetch the first page of data. Following pages are expected to get prefetched through
        // read-ahead.
        if (addressesData.length() > 0) {
          addressesData.prefetch(0, 1);
        }
        final LongValues addresses =
            DirectMonotonicReader.getInstance(entry.addressesMeta, addressesData, merging);
        return new DenseBinaryDocValues(maxDoc) {
          final BytesRef bytes = new BytesRef(new byte[entry.maxLength], 0, entry.maxLength);

          @Override
          public BytesRef binaryValue() throws IOException {
            long startOffset = addresses.get(doc);
            bytes.length = (int) (addresses.get(doc + 1L) - startOffset);
            bytesSlice.readBytes(startOffset, bytes.bytes, 0, bytes.length);
            return bytes;
          }

          @Override
          public void prefetchBinaryValues(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              // Prefetch address index entries for first and last doc
              // (DirectMonotonicReader will read blocks containing these)
              long firstAddr = addresses.get(docs[0]);
              long lastAddr = addresses.get(docs[size - 1] + 1L);
              if (lastAddr > firstAddr) {
                bytesSlice.prefetch(firstAddr, lastAddr - firstAddr);
              }
            }
          }
        };
      }
    } else {
      // sparse
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numDocsWithField);
      if (entry.minLength == entry.maxLength) {
        // fixed length
        final int length = entry.maxLength;
        return new SparseBinaryDocValues(disi) {
          final BytesRef bytes = new BytesRef(new byte[length], 0, length);

          @Override
          public BytesRef binaryValue() throws IOException {
            bytesSlice.readBytes((long) disi.index() * length, bytes.bytes, 0, length);
            return bytes;
          }

          @Override
          public void prefetchBinaryValues(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && length > 0) {
              prefetchDISI(disi, size, docs);
              // Conservative: prefetch the full range of possible positions
              long firstByte = (long) docs[0] * length;
              long lastByte = (long) docs[size - 1] * length + length;
              bytesSlice.prefetch(firstByte, lastByte - firstByte);
            }
          }
        };
      } else {
        // variable length
        final RandomAccessInput addressesData =
            this.data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
        // Prefetch the first page of data. Following pages are expected to get prefetched through
        // read-ahead.
        if (addressesData.length() > 0) {
          addressesData.prefetch(0, 1);
        }
        final LongValues addresses =
            DirectMonotonicReader.getInstance(entry.addressesMeta, addressesData);
        return new SparseBinaryDocValues(disi) {
          final BytesRef bytes = new BytesRef(new byte[entry.maxLength], 0, entry.maxLength);

          @Override
          public BytesRef binaryValue() throws IOException {
            final int index = disi.index();
            long startOffset = addresses.get(index);
            bytes.length = (int) (addresses.get(index + 1L) - startOffset);
            bytesSlice.readBytes(startOffset, bytes.bytes, 0, bytes.length);
            return bytes;
          }

          @Override
          public void prefetchBinaryValues(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              prefetchDISI(disi, size, docs);
            }
          }
        };
      }
    }
  }

  @Override
  public SortedDocValues getSorted(FieldInfo field) throws IOException {
    SortedEntry entry = sorted.get(field.number);
    return getSorted(entry);
  }

  private SortedDocValues getSorted(SortedEntry entry) throws IOException {
    // Specialize the common case for ordinals: single block of packed integers.
    final NumericEntry ordsEntry = entry.ordsEntry;
    if (ordsEntry.blockShift < 0 // single block
        && ordsEntry.bitsPerValue > 0) { // more than 1 value

      if (ordsEntry.gcd != 1 || ordsEntry.minValue != 0 || ordsEntry.table != null) {
        throw new IllegalStateException("Ordinals shouldn't use GCD, offset or table compression");
      }

      final RandomAccessInput slice =
          data.randomAccessSlice(ordsEntry.valuesOffset, ordsEntry.valuesLength);
      // Prefetch the first page of data. Following pages are expected to get prefetched through
      // read-ahead.
      if (slice.length() > 0) {
        slice.prefetch(0, 1);
      }
      final LongValues values =
          getDirectReaderInstance(slice, ordsEntry.bitsPerValue, 0L, ordsEntry.numValues);

      if (ordsEntry.docsWithFieldOffset == -1) { // dense
        return new BaseSortedDocValues(entry) {

          private final int maxDoc = Lucene90DocValuesProducer.this.maxDoc;
          private int doc = -1;

          @Override
          public int ordValue() throws IOException {
            return (int) values.get(doc);
          }

          @Override
          public boolean advanceExact(int target) throws IOException {
            doc = target;
            return true;
          }

          @Override
          public int docID() {
            return doc;
          }

          @Override
          public int nextDoc() throws IOException {
            return advance(doc + 1);
          }

          @Override
          public int advance(int target) throws IOException {
            if (target >= maxDoc) {
              return doc = NO_MORE_DOCS;
            }
            return doc = target;
          }

          @Override
          public long cost() {
            return maxDoc;
          }

          @Override
          public int docIDRunEnd() throws IOException {
            return maxDoc;
          }

          @Override
          public void ordValues(int size, int[] docs, int[] ords, int defaultOrd)
              throws IOException {
            super.ordValues(size, docs, ords, defaultOrd);
          }

          @Override
          public void prefetchOrdValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0 && ordsEntry.bitsPerValue > 0) {
              prefetchFixedBPV(size, docs, slice, ordsEntry.bitsPerValue);
            }
          }
        };
      } else if (ordsEntry.docsWithFieldOffset >= 0) { // sparse but non-empty
        final IndexedDISI disi =
            new IndexedDISI(
                data,
                ordsEntry.docsWithFieldOffset,
                ordsEntry.docsWithFieldLength,
                ordsEntry.jumpTableEntryCount,
                ordsEntry.denseRankPower,
                ordsEntry.numValues);

        return new BaseSortedDocValues(entry) {

          @Override
          public int ordValue() throws IOException {
            return (int) values.get(disi.index());
          }

          @Override
          public boolean advanceExact(int target) throws IOException {
            return disi.advanceExact(target);
          }

          @Override
          public int docID() {
            return disi.docID();
          }

          @Override
          public int nextDoc() throws IOException {
            return disi.nextDoc();
          }

          @Override
          public int advance(int target) throws IOException {
            return disi.advance(target);
          }

          @Override
          public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
            disi.intoBitSet(upTo, bitSet, offset);
          }

          @Override
          public long cost() {
            return disi.cost();
          }

          @Override
          public int docIDRunEnd() throws IOException {
            return disi.docIDRunEnd();
          }

          @Override
          public void prefetchOrdValues(int size, int[] docs) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              prefetchDISI(disi, size, docs);
              if (ordsEntry.bitsPerValue > 0) {
                prefetchFixedBPV(size, docs, slice, ordsEntry.bitsPerValue);
              }
            }
          }
        };
      }
    }

    final NumericDocValues ords = getNumeric(entry.ordsEntry);
    return new BaseSortedDocValues(entry) {

      @Override
      public int ordValue() throws IOException {
        return (int) ords.longValue();
      }

      @Override
      public boolean advanceExact(int target) throws IOException {
        return ords.advanceExact(target);
      }

      @Override
      public int docID() {
        return ords.docID();
      }

      @Override
      public int nextDoc() throws IOException {
        return ords.nextDoc();
      }

      @Override
      public int advance(int target) throws IOException {
        return ords.advance(target);
      }

      @Override
      public long cost() {
        return ords.cost();
      }

      @Override
      public int docIDRunEnd() throws IOException {
        return ords.docIDRunEnd();
      }

      @Override
      public void ordValues(int size, int[] docs, int[] ordsBuf, int defaultOrd)
          throws IOException {
        // Delegate to NumericDocValues.longValues() which already has prefetch
        long[] longBuf = new long[size];
        ords.longValues(size, docs, longBuf, defaultOrd);
        for (int i = 0; i < size; i++) {
          ordsBuf[i] = (int) longBuf[i];
        }
      }
    };
  }

  private abstract class BaseSortedDocValues extends SortedDocValues {

    final SortedEntry entry;
    final TermsEnum termsEnum;

    BaseSortedDocValues(SortedEntry entry) throws IOException {
      this.entry = entry;
      this.termsEnum = termsEnum();
    }

    @Override
    public int getValueCount() {
      return Math.toIntExact(entry.termsDictEntry.termsDictSize);
    }

    @Override
    public BytesRef lookupOrd(int ord) throws IOException {
      termsEnum.seekExact(ord);
      return termsEnum.term();
    }

    @Override
    public int lookupTerm(BytesRef key) throws IOException {
      SeekStatus status = termsEnum.seekCeil(key);
      switch (status) {
        case FOUND:
          return Math.toIntExact(termsEnum.ord());
        case NOT_FOUND:
        case END:
        default:
          return Math.toIntExact(-1L - termsEnum.ord());
      }
    }

    @Override
    public TermsEnum termsEnum() throws IOException {
      return new TermsDict(entry.termsDictEntry, data);
    }

    /**
     * Prefetch the LZ4 term dictionary blocks for the given ordinals.
     * Call this before a batch of lookupOrd() calls to warm the cache
     * asynchronously. The ordinals must be sorted ascending.
     */
    void prefetchOrdinals(int[] ords, int count) throws IOException {
      if (termsEnum instanceof TermsDict td) {
        td.prefetchBlocks(ords, count);
      }
    }

    @Override
    public void prepareSeekExact(int ord) throws IOException {
      if (termsEnum instanceof TermsDict td) {
        td.prepareSeekExact(ord);
      }
    }
  }

  private abstract class BaseSortedSetDocValues extends SortedSetDocValues {

    final SortedSetEntry entry;
    final IndexInput data;
    final TermsEnum termsEnum;

    BaseSortedSetDocValues(SortedSetEntry entry, IndexInput data) throws IOException {
      this.entry = entry;
      this.data = data;
      this.termsEnum = termsEnum();
    }

    @Override
    public long getValueCount() {
      return entry.termsDictEntry.termsDictSize;
    }

    @Override
    public BytesRef lookupOrd(long ord) throws IOException {
      termsEnum.seekExact(ord);
      return termsEnum.term();
    }

    @Override
    public long lookupTerm(BytesRef key) throws IOException {
      SeekStatus status = termsEnum.seekCeil(key);
      switch (status) {
        case FOUND:
          return termsEnum.ord();
        case NOT_FOUND:
        case END:
        default:
          return -1L - termsEnum.ord();
      }
    }

    @Override
    public TermsEnum termsEnum() throws IOException {
      return new TermsDict(entry.termsDictEntry, data);
    }
  }

  private class TermsDict extends BaseTermsEnum {
    static final int LZ4_DECOMPRESSOR_PADDING = 7;

    final TermsDictEntry entry;
    final LongValues blockAddresses;
    final IndexInput bytes;
    final long blockMask;
    final LongValues indexAddresses;
    final RandomAccessInput indexBytes;
    final BytesRef term;
    final BytesRef blockBuffer;
    final ByteArrayDataInput blockInput;
    long ord = -1;
    long currentCompressedBlockStart = -1;
    long currentCompressedBlockEnd = -1;

    TermsDict(TermsDictEntry entry, IndexInput data) throws IOException {
      this.entry = entry;
      RandomAccessInput addressesSlice =
          data.randomAccessSlice(entry.termsAddressesOffset, entry.termsAddressesLength);
      blockAddresses =
          DirectMonotonicReader.getInstance(entry.termsAddressesMeta, addressesSlice, merging);
      bytes = data.slice("terms", entry.termsDataOffset, entry.termsDataLength);
      blockMask = (1L << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1;
      RandomAccessInput indexAddressesSlice =
          data.randomAccessSlice(entry.termsIndexAddressesOffset, entry.termsIndexAddressesLength);
      indexAddresses =
          DirectMonotonicReader.getInstance(
              entry.termsIndexAddressesMeta, indexAddressesSlice, merging);
      indexBytes = data.randomAccessSlice(entry.termsIndexOffset, entry.termsIndexLength);
      term = new BytesRef(entry.maxTermLength);

      // add the max term length for the dictionary
      // add 7 padding bytes can help decompression run faster.
      int bufferSize = entry.maxBlockLength + entry.maxTermLength + LZ4_DECOMPRESSOR_PADDING;
      blockBuffer = new BytesRef(new byte[bufferSize], 0, bufferSize);
      blockInput = new ByteArrayDataInput();
    }

    @Override
    public BytesRef next() throws IOException {
      if (++ord >= entry.termsDictSize) {
        return null;
      }

      if ((ord & blockMask) == 0L) {
        decompressBlock();
      } else {
        DataInput input = blockInput;
        final int token = Byte.toUnsignedInt(input.readByte());
        int prefixLength = token & 0x0F;
        int suffixLength = 1 + (token >>> 4);
        if (prefixLength == 15) {
          prefixLength += input.readVInt();
        }
        if (suffixLength == 16) {
          suffixLength += input.readVInt();
        }
        term.length = prefixLength + suffixLength;
        input.readBytes(term.bytes, prefixLength, suffixLength);
      }
      return term;
    }

    @Override
    public void seekExact(long ord) throws IOException {
      if (ord < 0 || ord >= entry.termsDictSize) {
        throw new IndexOutOfBoundsException();
      }
      // Signed shift since ord is -1 when the terms enum is not positioned
      final long currentBlockIndex = this.ord >> TERMS_DICT_BLOCK_LZ4_SHIFT;
      final long blockIndex = ord >> TERMS_DICT_BLOCK_LZ4_SHIFT;
      if (ord < this.ord || blockIndex != currentBlockIndex) {
        // The looked up ord is before the current ord or belongs to a different block, seek again
        final long blockAddress = blockAddresses.get(blockIndex);
        bytes.seek(blockAddress);
        this.ord = (blockIndex << TERMS_DICT_BLOCK_LZ4_SHIFT) - 1;
      }
      // Scan to the looked up ord
      while (this.ord < ord) {
        next();
      }
    }

    private BytesRef getTermFromIndex(long index) throws IOException {
      assert index >= 0 && index <= (entry.termsDictSize - 1) >>> entry.termsDictIndexShift;
      final long start = indexAddresses.get(index);
      term.length = (int) (indexAddresses.get(index + 1) - start);
      indexBytes.readBytes(start, term.bytes, 0, term.length);
      return term;
    }

    private long seekTermsIndex(BytesRef text) throws IOException {
      long lo = 0L;
      long hi = (entry.termsDictSize - 1) >> entry.termsDictIndexShift;
      while (lo <= hi) {
        final long mid = (lo + hi) >>> 1;
        getTermFromIndex(mid);
        final int cmp = term.compareTo(text);
        if (cmp <= 0) {
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }

      assert hi < 0 || getTermFromIndex(hi).compareTo(text) <= 0;
      assert hi == ((entry.termsDictSize - 1) >> entry.termsDictIndexShift)
          || getTermFromIndex(hi + 1).compareTo(text) > 0;
      assert hi < 0 ^ entry.termsDictSize > 0; // return -1 iff empty term dict

      return hi;
    }

    private BytesRef getFirstTermFromBlock(long block) throws IOException {
      assert block >= 0 && block <= (entry.termsDictSize - 1) >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      final long blockAddress = blockAddresses.get(block);
      bytes.seek(blockAddress);
      term.length = bytes.readVInt();
      bytes.readBytes(term.bytes, 0, term.length);
      return term;
    }

    private long seekBlock(BytesRef text) throws IOException {
      long index = seekTermsIndex(text);
      if (index == -1L) {
        // empty terms dict
        this.ord = 0;
        return -2L;
      }

      long ordLo = index << entry.termsDictIndexShift;
      long ordHi = Math.min(entry.termsDictSize, ordLo + (1L << entry.termsDictIndexShift)) - 1L;

      long blockLo = ordLo >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      long blockHi = ordHi >>> TERMS_DICT_BLOCK_LZ4_SHIFT;

      while (blockLo <= blockHi) {
        final long blockMid = (blockLo + blockHi) >>> 1;
        getFirstTermFromBlock(blockMid);
        final int cmp = term.compareTo(text);
        if (cmp <= 0) {
          blockLo = blockMid + 1;
        } else {
          blockHi = blockMid - 1;
        }
      }

      assert blockHi < 0 || getFirstTermFromBlock(blockHi).compareTo(text) <= 0;
      assert blockHi == ((entry.termsDictSize - 1) >>> TERMS_DICT_BLOCK_LZ4_SHIFT)
          || getFirstTermFromBlock(blockHi + 1).compareTo(text) > 0;

      // read the block only if term dict is not empty
      assert entry.termsDictSize > 0;
      // reset ord and bytes to the ceiling block even if
      // text is before the first term (blockHi == -1)
      final long block = Math.max(blockHi, 0);
      final long blockAddress = blockAddresses.get(block);
      this.ord = block << TERMS_DICT_BLOCK_LZ4_SHIFT;
      bytes.seek(blockAddress);
      decompressBlock();

      return blockHi;
    }

    @Override
    public SeekStatus seekCeil(BytesRef text) throws IOException {
      final long block = seekBlock(text);
      if (block == -2) {
        // empty terms dict
        assert entry.termsDictSize == 0;
        return SeekStatus.END;
      } else if (block == -1) {
        // before the first term
        return SeekStatus.NOT_FOUND;
      }

      while (true) {
        int cmp = term.compareTo(text);
        if (cmp == 0) {
          return SeekStatus.FOUND;
        } else if (cmp > 0) {
          return SeekStatus.NOT_FOUND;
        }
        if (next() == null) {
          return SeekStatus.END;
        }
      }
    }

    private void decompressBlock() throws IOException {
      // The first term is kept uncompressed, so no need to decompress block if only
      // look up the first term when doing seek block.
      term.length = bytes.readVInt();
      bytes.readBytes(term.bytes, 0, term.length);
      long offset = bytes.getFilePointer();
      if (offset < entry.termsDataLength - 1) {
        // Avoid decompress again if we are reading a same block.
        if (currentCompressedBlockStart != offset) {
          blockBuffer.offset = term.length;
          blockBuffer.length = bytes.readVInt();
          // Decompress the remaining of current block, using the first term as a dictionary
          System.arraycopy(term.bytes, 0, blockBuffer.bytes, 0, blockBuffer.offset);
          LZ4.decompress(bytes, blockBuffer.length, blockBuffer.bytes, blockBuffer.offset);
          currentCompressedBlockStart = offset;
          currentCompressedBlockEnd = bytes.getFilePointer();
        } else {
          // Skip decompression but need to re-seek to block end.
          bytes.seek(currentCompressedBlockEnd);
        }

        // Reset the buffer.
        blockInput.reset(blockBuffer.bytes, blockBuffer.offset, blockBuffer.length);
      }
    }

    /**
     * Prepare for a subsequent seekExact(ord) by prefetching the LZ4 block
     * containing the given ordinal. This is the ordinal equivalent of
     * TermsEnum.prepareSeekExact(BytesRef) — same two-phase pattern where
     * prepare issues async IO and the subsequent seekExact finds data warm.
     */
    void prepareSeekExact(long ord) throws IOException {
      if (ord < 0 || ord >= entry.termsDictSize) return;
      final long blockIndex = ord >> TERMS_DICT_BLOCK_LZ4_SHIFT;
      // Skip if we're already positioned in this block
      final long currentBlockIndex = this.ord >> TERMS_DICT_BLOCK_LZ4_SHIFT;
      if (this.ord >= 0 && blockIndex == currentBlockIndex && ord >= this.ord) return;
      // Prefetch the LZ4 block
      long blockStart = blockAddresses.get(blockIndex);
      long totalBlocks = (entry.termsDictSize + TERMS_DICT_BLOCK_LZ4_SIZE - 1)
          >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
      long blockEnd = (blockIndex + 1 < totalBlocks)
          ? blockAddresses.get(blockIndex + 1)
          : entry.termsDataLength;
      if (blockEnd > blockStart) {
        bytes.prefetch(blockStart, blockEnd - blockStart);
      }
    }

    /**
     * Prefetch the LZ4 term dictionary blocks that will be read by subsequent
     * seekExact(ord) calls. Given a sorted array of ordinals, computes the unique
     * block indices, looks up their byte addresses, and issues prefetch() calls
     * on the underlying IndexInput.
     *
     * <p>This is 100% non-speculative: every prefetched block WILL be decompressed
     * by the subsequent seekExact calls because the ordinals are known in advance.
     */
    void prefetchBlocks(int[] ords, int count) throws IOException {
      if (count == 0) return;
      // Compute unique block indices from ordinals (ords are sorted ascending)
      long prevBlock = -1;
      for (int i = 0; i < count; i++) {
        long blockIndex = ((long) ords[i]) >> TERMS_DICT_BLOCK_LZ4_SHIFT;
        if (blockIndex != prevBlock) {
          long blockStart = blockAddresses.get(blockIndex);
          // End address: start of next block, or end of terms data
          long blockEnd;
          long nextBlockIndex = blockIndex + 1;
          long totalBlocks = (entry.termsDictSize + TERMS_DICT_BLOCK_LZ4_SIZE - 1)
              >>> TERMS_DICT_BLOCK_LZ4_SHIFT;
          if (nextBlockIndex < totalBlocks) {
            blockEnd = blockAddresses.get(nextBlockIndex);
          } else {
            blockEnd = entry.termsDataLength;
          }
          if (blockEnd > blockStart) {
            bytes.prefetch(blockStart, blockEnd - blockStart);
          }
          prevBlock = blockIndex;
        }
      }
    }

    @Override
    public BytesRef term() throws IOException {
      return term;
    }

    @Override
    public long ord() throws IOException {
      return ord;
    }

    @Override
    public long totalTermFreq() throws IOException {
      return -1L;
    }

    @Override
    public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImpactsEnum impacts(int flags) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int docFreq() throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
    SortedNumericEntry entry = sortedNumerics.get(field.number);
    return getSortedNumeric(entry);
  }

  private SortedNumericDocValues getSortedNumeric(SortedNumericEntry entry) throws IOException {
    if (entry.numValues == entry.numDocsWithField) {
      return DocValues.singleton(getNumeric(entry));
    }

    final RandomAccessInput addressesInput =
        data.randomAccessSlice(entry.addressesOffset, entry.addressesLength);
    // Capture values slice for prefetchRange — needed to prefetch value data
    final RandomAccessInput valuesSlice = (entry.bitsPerValue > 0)
        ? data.randomAccessSlice(entry.valuesOffset, entry.valuesLength) : null;
    // Prefetch the first page of data. Following pages are expected to get prefetched through
    // read-ahead.
    if (addressesInput.length() > 0) {
      addressesInput.prefetch(0, 1);
    }
    final LongValues addresses =
        DirectMonotonicReader.getInstance(entry.addressesMeta, addressesInput, merging);

    final LongValues values = getNumericValues(entry);

    if (entry.docsWithFieldOffset == -1) {
      // dense
      return new SortedNumericDocValues() {

        int doc = -1;
        long start, end;
        int count;

        @Override
        public int nextDoc() throws IOException {
          return advance(doc + 1);
        }

        @Override
        public int docID() {
          return doc;
        }

        @Override
        public long cost() {
          return maxDoc;
        }

        @Override
        public int advance(int target) throws IOException {
          if (target >= maxDoc) {
            return doc = NO_MORE_DOCS;
          }
          start = addresses.get(target);
          end = addresses.get(target + 1L);
          count = (int) (end - start);
          return doc = target;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
          start = addresses.get(target);
          end = addresses.get(target + 1L);
          count = (int) (end - start);
          doc = target;
          return true;
        }

        @Override
        public long nextValue() throws IOException {
          return values.get(start++);
        }

        @Override
        public int docValueCount() {
          return count;
        }

        @Override
        public int docIDRunEnd() throws IOException {
          return maxDoc;
        }

        @Override
        public void prefetchRange(int[] docs, int size) throws IOException {
          if (!PrefetchConfig.isEnabled() || size == 0) return;
          // Prefetch address index (typically small, a few KB)
          prefetchDirectMonotonicAll(addressesInput);
          // Read addresses (now warm) to get value position range
          long firstStart = addresses.get(docs[0]);
          long lastEnd = addresses.get((long) docs[size - 1] + 1);
          // Prefetch values data for positions [firstStart, lastEnd)
          if (lastEnd > firstStart && valuesSlice != null && entry.bitsPerValue > 0) {
            long firstByte = (firstStart * entry.bitsPerValue) / 8;
            long lastByte = ((lastEnd - 1) * entry.bitsPerValue) / 8 + 8;
            valuesSlice.prefetch(firstByte, Math.min(lastByte - firstByte, valuesSlice.length() - firstByte));
          }
        }
      };
    } else {
      // sparse
      final IndexedDISI disi =
          new IndexedDISI(
              data,
              entry.docsWithFieldOffset,
              entry.docsWithFieldLength,
              entry.jumpTableEntryCount,
              entry.denseRankPower,
              entry.numDocsWithField);
      return new SortedNumericDocValues() {

        boolean set;
        long start, end;
        int count;

        @Override
        public int nextDoc() throws IOException {
          set = false;
          return disi.nextDoc();
        }

        @Override
        public int docID() {
          return disi.docID();
        }

        @Override
        public long cost() {
          return disi.cost();
        }

        @Override
        public int advance(int target) throws IOException {
          set = false;
          return disi.advance(target);
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
          set = false;
          return disi.advanceExact(target);
        }

        @Override
        public long nextValue() throws IOException {
          set();
          return values.get(start++);
        }

        @Override
        public int docValueCount() {
          set();
          return count;
        }

        @Override
        public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
          set = false;
          disi.intoBitSet(upTo, bitSet, offset);
        }

        private void set() {
          if (set == false) {
            final int index = disi.index();
            start = addresses.get(index);
            end = addresses.get(index + 1L);
            count = (int) (end - start);
            set = true;
          }
        }

        @Override
        public int docIDRunEnd() throws IOException {
          return disi.docIDRunEnd();
        }

        @Override
        public void prefetchRange(int[] docs, int size) throws IOException {
          if (!PrefetchConfig.isEnabled() || size == 0) return;
          // Prefetch DISI blocks (does not advance the iterator)
          prefetchDISI(disi, size, docs);
          // Prefetch address index (small, prefetch all)
          prefetchDirectMonotonicAll(addressesInput);
          // For values, we can't know exact positions without advancing DISI
          // (which would corrupt iterator state). Prefetch the entire values slice
          // as a conservative bound — values slice is the packed integer data.
          if (valuesSlice != null && valuesSlice.length() > 0 && entry.bitsPerValue > 0) {
            valuesSlice.prefetch(0, valuesSlice.length());
          }
        }
      };
    }
  }

  @Override
  public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
    SortedSetEntry entry = sortedSets.get(field.number);
    if (entry.singleValueEntry != null) {
      return DocValues.singleton(getSorted(entry.singleValueEntry));
    }

    // Specialize the common case for ordinals: single block of packed integers.
    SortedNumericEntry ordsEntry = entry.ordsEntry;
    if (ordsEntry.blockShift < 0 && ordsEntry.bitsPerValue > 0) {
      if (ordsEntry.gcd != 1 || ordsEntry.minValue != 0 || ordsEntry.table != null) {
        throw new IllegalStateException("Ordinals shouldn't use GCD, offset or table compression");
      }

      final RandomAccessInput addressesInput =
          data.randomAccessSlice(ordsEntry.addressesOffset, ordsEntry.addressesLength);
      // Prefetch the first page of data. Following pages are expected to get prefetched through
      // read-ahead.
      if (addressesInput.length() > 0) {
        addressesInput.prefetch(0, 1);
      }
      final LongValues addresses =
          DirectMonotonicReader.getInstance(ordsEntry.addressesMeta, addressesInput);

      final RandomAccessInput slice =
          data.randomAccessSlice(ordsEntry.valuesOffset, ordsEntry.valuesLength);
      // Prefetch the first page of data. Following pages are expected to get prefetched through
      // read-ahead.
      if (slice.length() > 0) {
        slice.prefetch(0, 1);
      }
      final LongValues values = DirectReader.getInstance(slice, ordsEntry.bitsPerValue);

      if (ordsEntry.docsWithFieldOffset == -1) { // dense
        return new BaseSortedSetDocValues(entry, data) {

          private final int maxDoc = Lucene90DocValuesProducer.this.maxDoc;
          private int doc = -1;
          private long curr;
          private int count;

          @Override
          public long nextOrd() throws IOException {
            return values.get(curr++);
          }

          @Override
          public boolean advanceExact(int target) throws IOException {
            curr = addresses.get(target);
            long end = addresses.get(target + 1L);
            count = (int) (end - curr);
            doc = target;
            return true;
          }

          @Override
          public int docValueCount() {
            return count;
          }

          @Override
          public int docID() {
            return doc;
          }

          @Override
          public int nextDoc() throws IOException {
            return advance(doc + 1);
          }

          @Override
          public int advance(int target) throws IOException {
            if (target >= maxDoc) {
              return doc = NO_MORE_DOCS;
            }
            curr = addresses.get(target);
            long end = addresses.get(target + 1L);
            count = (int) (end - curr);
            return doc = target;
          }

          @Override
          public long cost() {
            return maxDoc;
          }

          @Override
          public int docIDRunEnd() throws IOException {
            return maxDoc;
          }

          @Override
          public void prefetchOrds(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              // Prefetch address index range
              long firstAddr = addresses.get(docs[0]);
              long lastAddr = addresses.get(docs[size - 1] + 1L);
              // Prefetch ordinal data range
              if (lastAddr > firstAddr && ordsEntry.bitsPerValue > 0) {
                int readSize = ordsEntry.bitsPerValue <= 8 ? 1 : ordsEntry.bitsPerValue <= 16 ? 2 : ordsEntry.bitsPerValue <= 32 ? 4 : 8;
                long firstByte = (firstAddr * ordsEntry.bitsPerValue) / 8;
                long lastByte = ((lastAddr - 1) * ordsEntry.bitsPerValue) / 8 + readSize;
                slice.prefetch(firstByte, lastByte - firstByte);
              }
            }
          }
        };
      } else if (ordsEntry.docsWithFieldOffset >= 0) { // sparse but non-empty
        final IndexedDISI disi =
            new IndexedDISI(
                data,
                ordsEntry.docsWithFieldOffset,
                ordsEntry.docsWithFieldLength,
                ordsEntry.jumpTableEntryCount,
                ordsEntry.denseRankPower,
                ordsEntry.numValues);

        return new BaseSortedSetDocValues(entry, data) {

          boolean set;
          long curr;
          int count;

          @Override
          public long nextOrd() throws IOException {
            set();
            return values.get(curr++);
          }

          @Override
          public boolean advanceExact(int target) throws IOException {
            set = false;
            return disi.advanceExact(target);
          }

          @Override
          public int docValueCount() {
            set();
            return count;
          }

          @Override
          public int docID() {
            return disi.docID();
          }

          @Override
          public int nextDoc() throws IOException {
            set = false;
            return disi.nextDoc();
          }

          @Override
          public int advance(int target) throws IOException {
            set = false;
            return disi.advance(target);
          }

          @Override
          public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
            set = false;
            disi.intoBitSet(upTo, bitSet, offset);
          }

          @Override
          public long cost() {
            return disi.cost();
          }

          private void set() {
            if (set == false) {
              final int index = disi.index();
              curr = addresses.get(index);
              long end = addresses.get(index + 1L);
              count = (int) (end - curr);
              set = true;
            }
          }

          @Override
          public int docIDRunEnd() throws IOException {
            return disi.docIDRunEnd();
          }

          @Override
          public void prefetchOrds(int[] docs, int size) throws IOException {
            if (PrefetchConfig.isEnabled() && size > 0) {
              prefetchDISI(disi, size, docs);
              if (ordsEntry.bitsPerValue > 0) {
                prefetchFixedBPV(size, docs, slice, ordsEntry.bitsPerValue);
              }
            }
          }
        };
      }
    }

    final SortedNumericDocValues ords = getSortedNumeric(ordsEntry);
    return new BaseSortedSetDocValues(entry, data) {

      @Override
      public long nextOrd() throws IOException {
        return ords.nextValue();
      }

      @Override
      public int docValueCount() {
        return ords.docValueCount();
      }

      @Override
      public boolean advanceExact(int target) throws IOException {
        return ords.advanceExact(target);
      }

      @Override
      public int docID() {
        return ords.docID();
      }

      @Override
      public int nextDoc() throws IOException {
        return ords.nextDoc();
      }

      @Override
      public int advance(int target) throws IOException {
        return ords.advance(target);
      }

      @Override
      public long cost() {
        return ords.cost();
      }

      @Override
      public int docIDRunEnd() throws IOException {
        return ords.docIDRunEnd();
      }
    };
  }

  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(data);
  }

  /**
   * Reader for longs split into blocks of different bits per values. The longs are requested by
   * index and must be accessed in monotonically increasing order.
   */
  // Note: The order requirement could be removed as the jump-tables allow for backwards iteration
  // Note 2: The rankSlice is only used if an advance of > 1 block is called. Its construction could
  // be lazy
  private class VaryingBPVReader {
    final RandomAccessInput slice; // 2 slices to avoid cache thrashing when using rank
    final RandomAccessInput rankSlice;
    final NumericEntry entry;
    final int shift;
    final long mul;
    final int mask;

    long block = -1;
    long delta;
    long offset;
    long blockEndOffset;
    LongValues values;

    VaryingBPVReader(NumericEntry entry, RandomAccessInput slice) throws IOException {
      this.entry = entry;
      this.slice = slice;
      this.rankSlice =
          entry.valueJumpTableOffset == -1
              ? null
              : data.randomAccessSlice(
                  entry.valueJumpTableOffset, data.length() - entry.valueJumpTableOffset);
      if (rankSlice != null && rankSlice.length() > 0) {
        // Prefetch the first page of data. Following pages are expected to get prefetched through
        // read-ahead.
        rankSlice.prefetch(0, 1);
      }
      shift = entry.blockShift;
      mul = entry.gcd;
      mask = (1 << shift) - 1;
    }

    long getLongValue(long index) throws IOException {
      final long block = index >>> shift;
      if (this.block != block) {
        int bitsPerValue;
        do {
          // If the needed block is the one directly following the current block, it is cheaper to
          // avoid the cache
          if (rankSlice != null && block != this.block + 1) {
            blockEndOffset = rankSlice.readLong(block * Long.BYTES) - entry.valuesOffset;
            this.block = block - 1;
          }
          offset = blockEndOffset;
          bitsPerValue = slice.readByte(offset++);
          delta = slice.readLong(offset);
          offset += Long.BYTES;
          if (bitsPerValue == 0) {
            blockEndOffset = offset;
          } else {
            final int length = slice.readInt(offset);
            offset += Integer.BYTES;
            blockEndOffset = offset + length;
          }
          this.block++;
        } while (this.block != block);
        final int numValues =
            Math.toIntExact(Math.min(1 << shift, entry.numValues - (block << shift)));
        values =
            bitsPerValue == 0
                ? LongValues.ZEROES
                : getDirectReaderInstance(slice, bitsPerValue, offset, numValues);
      }
      return mul * values.get(index & mask) + delta;
    }
  }

  @Override
  public DocValuesSkipper getSkipper(FieldInfo field) throws IOException {
    final DocValuesSkipperEntry entry = skippers.get(field.number);

    final IndexInput input = data.slice("doc value skipper", entry.offset, entry.length);
    // Prefetch the first page of data. Following pages are expected to get prefetched through
    // read-ahead.
    if (input.length() > 0) {
      input.prefetch(0, 1);
    }
    // TODO: should we write to disk the actual max level for this segment?
    return new DocValuesSkipper() {
      final int[] minDocID = new int[SKIP_INDEX_MAX_LEVEL];
      final int[] maxDocID = new int[SKIP_INDEX_MAX_LEVEL];

      {
        for (int i = 0; i < SKIP_INDEX_MAX_LEVEL; i++) {
          minDocID[i] = maxDocID[i] = -1;
        }
      }

      final long[] minValue = new long[SKIP_INDEX_MAX_LEVEL];
      final long[] maxValue = new long[SKIP_INDEX_MAX_LEVEL];
      final int[] docCount = new int[SKIP_INDEX_MAX_LEVEL];
      int levels = 1;

      @Override
      public void advance(int target) throws IOException {
        if (target > entry.maxDocId) {
          // skipper is exhausted
          for (int i = 0; i < SKIP_INDEX_MAX_LEVEL; i++) {
            minDocID[i] = maxDocID[i] = DocIdSetIterator.NO_MORE_DOCS;
          }
        } else {
          // find next interval
          assert target > maxDocID[0] : "target must be bigger that current interval";
          while (true) {
            levels = input.readByte();
            assert levels <= SKIP_INDEX_MAX_LEVEL && levels > 0
                : "level out of range [" + levels + "]";
            boolean valid = true;
            // check if current interval is competitive or we can jump to the next position
            for (int level = levels - 1; level >= 0; level--) {
              if ((maxDocID[level] = input.readInt()) < target) {
                input.skipBytes(SKIP_INDEX_JUMP_LENGTH_PER_LEVEL[level]); // the jump for the level
                valid = false;
                break;
              }
              minDocID[level] = input.readInt();
              maxValue[level] = input.readLong();
              minValue[level] = input.readLong();
              docCount[level] = input.readInt();
            }
            if (valid) {
              // adjust levels
              while (levels < SKIP_INDEX_MAX_LEVEL && maxDocID[levels] >= target) {
                levels++;
              }
              break;
            }
          }
        }
      }

      @Override
      public int numLevels() {
        return levels;
      }

      @Override
      public int minDocID(int level) {
        return minDocID[level];
      }

      @Override
      public int maxDocID(int level) {
        return maxDocID[level];
      }

      @Override
      public long minValue(int level) {
        return minValue[level];
      }

      @Override
      public long maxValue(int level) {
        return maxValue[level];
      }

      @Override
      public int docCount(int level) {
        return docCount[level];
      }

      @Override
      public long minValue() {
        return entry.minValue;
      }

      @Override
      public long maxValue() {
        return entry.maxValue;
      }

      @Override
      public int docCount() {
        return entry.docCount;
      }
    };
  }


  /**
   * Prefetches DISI jump table and data blocks for a batch of doc IDs.
   * This warms the cache for subsequent disi.advanceExact() calls.
   */
  static void prefetchDISI(IndexedDISI disi, int size, int[] docs) throws IOException {
    if (disi.jumpTable == null || size == 0) return;
    int firstBlock = docs[0] >>> 16;
    int lastBlock = docs[size - 1] >>> 16;
    int clampedFirst = Math.min(firstBlock, disi.jumpTableEntryCount - 1);
    int clampedLast = Math.min(lastBlock, disi.jumpTableEntryCount - 1);
    if (clampedFirst >= 0 && clampedLast >= clampedFirst) {
      long jtFirstByte = (long) clampedFirst * Integer.BYTES * 2;
      long jtLastByte = ((long) clampedLast + 1) * Integer.BYTES * 2;
      disi.jumpTable.prefetch(jtFirstByte, jtLastByte - jtFirstByte);
    }
  }

  /**
   * Prefetches varying BPV rankSlice entries for a batch of positions.
   * Positions are doc IDs for dense fields, DISI indices for sparse fields.
   */
  static void prefetchVaryingBPV(VaryingBPVReader vBPVReader, int size, int[] positions)
      throws IOException {
    if (vBPVReader.rankSlice == null || size == 0) return;
    long firstBlock = Integer.toUnsignedLong(positions[0]) >>> vBPVReader.shift;
    long lastBlock = Integer.toUnsignedLong(positions[size - 1]) >>> vBPVReader.shift;
    // Round 1: prefetch rankSlice entries for the block range
    long rankFirstByte = firstBlock * Long.BYTES;
    long rankLastByte = (lastBlock + 1) * Long.BYTES;
    vBPVReader.rankSlice.prefetch(rankFirstByte, rankLastByte - rankFirstByte);
    // Round 2: read rankSlice to get data offsets, prefetch value data range
    long dataStartOffset = vBPVReader.rankSlice.readLong(firstBlock * Long.BYTES)
        - vBPVReader.entry.valuesOffset;
    long dataEndOffset = vBPVReader.rankSlice.readLong(lastBlock * Long.BYTES)
        - vBPVReader.entry.valuesOffset;
    if (dataEndOffset >= dataStartOffset) {
      // Prefetch from first block's data to last block's data + estimated block size
      vBPVReader.slice.prefetch(dataStartOffset, dataEndOffset - dataStartOffset + (1 << 13));
    }
  }

  // ---- Prefetch helpers (additive, no modifications to existing code) ----

  /**
   * Determines whether to use per-doc block-based prefetch or contiguous range prefetch.
   * When docs are spread across more cache blocks than the batch size, per-doc is better.
   */
  static boolean usePerDocPrefetch(long densityRatio, int bitsPerValue, int densityMultiplier) {
    if (bitsPerValue == 0) return false;
    long blockCapacity = (32768L * 8) / bitsPerValue;
    return densityRatio > blockCapacity * densityMultiplier;
  }

  /**
   * Prefetches byte ranges for fixed bits-per-value encoded values. Computes byte offsets from
   * positions and bitsPerValue, then calls slice.prefetch() using either a contiguous range or
   * per-position strategy based on density.
   */
  /**
   * Prefetch the DirectMonotonicReader's backing RandomAccessInput. The address index
   * is typically small (a few KB) so we prefetch the entire slice rather than computing
   * exact block boundaries. This ensures addresses.get(index) calls don't block on IO.
   */
  static void prefetchDirectMonotonicAll(RandomAccessInput input) throws IOException {
    if (input.length() > 0) {
      input.prefetch(0, input.length());
    }
  }

  static void prefetchFixedBPV(int size, int[] positions, RandomAccessInput slice, int bitsPerValue)
      throws IOException {
    assert size > 0;
    assert bitsPerValue > 0;
    int readSize = bitsPerValue <= 8 ? 1 : bitsPerValue <= 16 ? 2 : bitsPerValue <= 32 ? 4 : 8;
    if (size == 1) {
      long byteOffset = ((long) positions[0] * bitsPerValue) / 8;
      slice.prefetch(byteOffset, readSize);
    } else {
      long densityRatio = ((long) positions[size - 1] - positions[0]) / size;
      if (usePerDocPrefetch(densityRatio, bitsPerValue, 1)) {
        for (int i = 0; i < size; i++) {
          long byteOffset = ((long) positions[i] * bitsPerValue) / 8;
          slice.prefetch(byteOffset, readSize);
        }
      } else {
        long firstByte = ((long) positions[0] * bitsPerValue) / 8;
        long lastByte = ((long) positions[size - 1] * bitsPerValue) / 8 + readSize;
        slice.prefetch(firstByte, lastByte - firstByte);
      }
    }
  }

}
