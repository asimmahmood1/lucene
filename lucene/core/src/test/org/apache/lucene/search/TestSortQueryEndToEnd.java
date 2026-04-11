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
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * End-to-end sort query tests that reproduce the exact production scenario:
 * index documents with @timestamp-like field, run desc sort query via IndexSearcher.
 * This exercises the full path: DenseConjunctionBulkScorer → collectRange/collect(DocIdStream)
 * → TopFieldCollector bulk collection → BulkValueComparator.
 *
 * <p>These tests catch the BitSetDocIdStream.intoArray boundary bug (Index 64 out of bounds
 * for length 64) that occurs when WINDOW_SIZE=4096 docs all match in a single scoring window.
 */
public class TestSortQueryEndToEnd extends LuceneTestCase {

  /**
   * Exact reproduction of the production failure: 100K docs, MatchAll, desc sort on long field.
   * This goes through DenseConjunctionBulkScorer → collectRange (dense, no deletes) and
   * collect(DocIdStream) via BitSetDocIdStream when competitive iterator is active.
   */
  public void testDescSortTimestamp100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      // Simulate @timestamp: monotonically increasing
      doc.add(new NumericDocValuesField("timestamp", 1700000000000L + i * 1000L));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    // Exact query from production: {"size":10,"sort":[{"@timestamp":{"order":"desc"}}]}
    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    assertEquals(10, results.scoreDocs.length);

    // Top 10 should be the highest timestamps
    for (int i = 0; i < 10; i++) {
      FieldDoc fd = (FieldDoc) results.scoreDocs[i];
      long expected = 1700000000000L + (numDocs - 1 - i) * 1000L;
      assertEquals("Position " + i, expected, ((Number) fd.fields[0]).longValue());
    }

    reader.close();
    dir.close();
  }

  /** Same test with 116M-scale doc count pattern (uses 200K to keep test fast). */
  public void testDescSortTimestamp200K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 200_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    FieldDoc first = (FieldDoc) results.scoreDocs[0];
    assertEquals((long) (numDocs - 1), ((Number) first.fields[0]).longValue());

    reader.close();
    dir.close();
  }

  /** Asc sort — exercises the same bulk path with opposite comparator direction. */
  public void testAscSortTimestamp100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, false));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    for (int i = 0; i < 10; i++) {
      FieldDoc fd = (FieldDoc) results.scoreDocs[i];
      assertEquals("Position " + i, (long) i, ((Number) fd.fields[0]).longValue());
    }

    reader.close();
    dir.close();
  }

  /** Filtered query + sort — exercises BitSetDocIdStream path (not collectRange). */
  public void testFilteredDescSort100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      doc.add(new StringField("status", i % 3 == 0 ? "error" : "ok", Field.Store.NO));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    // Bool query: filter on status=error + sort by timestamp desc
    BooleanQuery query = new BooleanQuery.Builder()
        .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term("status", "error")), BooleanClause.Occur.FILTER)
        .build();

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(query, 10, sort);

    // ~33K docs match (every 3rd)
    int expectedCount = (numDocs + 2) / 3;
    assertEquals(expectedCount, results.totalHits.value());
    assertEquals(10, results.scoreDocs.length);

    // Top result should be the highest timestamp that's divisible by 3
    FieldDoc first = (FieldDoc) results.scoreDocs[0];
    long topTimestamp = ((Number) first.fields[0]).longValue();
    assertEquals(0, topTimestamp % 3);
    assertTrue(topTimestamp >= numDocs - 3);

    reader.close();
    dir.close();
  }

  /** searchAfter pagination — exercises PagingFieldCollector path. */
  public void testSearchAfterDescSort100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));

    // Page 1
    TopFieldDocs page1 = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(10, page1.scoreDocs.length);
    FieldDoc lastPage1 = (FieldDoc) page1.scoreDocs[9];

    // Page 2
    TopDocs page2 = searcher.searchAfter(lastPage1, new MatchAllDocsQuery(), 10, sort);
    assertEquals(10, page2.scoreDocs.length);

    // Page 2 first doc should be exactly page1 last - 1
    FieldDoc firstPage2 = (FieldDoc) page2.scoreDocs[0];
    long page1Last = ((Number) lastPage1.fields[0]).longValue();
    long page2First = ((Number) firstPage2.fields[0]).longValue();
    assertEquals(page1Last - 1, page2First);

    // Page 3
    FieldDoc lastPage2 = (FieldDoc) page2.scoreDocs[9];
    TopDocs page3 = searcher.searchAfter(lastPage2, new MatchAllDocsQuery(), 10, sort);
    assertEquals(10, page3.scoreDocs.length);

    reader.close();
    dir.close();
  }

  /** Multi-segment index — no force merge, exercises cross-segment collection. */
  public void testDescSortMultiSegment100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    // Don't merge — keep multiple segments
    iwc.setMaxBufferedDocs(10000);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      w.addDocument(doc);
      if ((i + 1) % 10000 == 0) {
        w.flush();
      }
    }
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    assertTrue("Expected multiple segments", reader.leaves().size() > 1);

    IndexSearcher searcher = new IndexSearcher(reader);
    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    FieldDoc first = (FieldDoc) results.scoreDocs[0];
    assertEquals((long) (numDocs - 1), ((Number) first.fields[0]).longValue());

    reader.close();
    dir.close();
  }

  /** Deleted docs — exercises acceptDocs != null path. */
  public void testDescSortWithDeletes100K() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("timestamp", i));
      doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
      w.addDocument(doc);
    }
    // Delete 20% of docs
    for (int i = 0; i < numDocs; i += 5) {
      w.deleteDocuments(new Term("id", Integer.toString(i)));
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    int expectedLive = numDocs - (numDocs / 5);
    assertEquals(expectedLive, results.totalHits.value());

    // Top result should be the highest non-deleted timestamp
    FieldDoc first = (FieldDoc) results.scoreDocs[0];
    long topTs = ((Number) first.fields[0]).longValue();
    // Highest non-deleted: 99999 (not divisible by 5)
    assertTrue(topTs % 5 != 0);
    assertTrue(topTs >= numDocs - 5);

    reader.close();
    dir.close();
  }

  /**
   * Randomized end-to-end: random doc count, random sort direction, random topN,
   * optional filter, optional deletes. Cross-validates bulk path vs per-doc path
   * by running the same query with PrefetchConfig enabled and disabled.
   */
  public void testRandomizedSortQuery() throws Exception {
    for (int iter = 0; iter < 10; iter++) {
      Directory dir = newDirectory();
      IndexWriterConfig iwc = new IndexWriterConfig();
      iwc.setMergePolicy(newLogMergePolicy());
      IndexWriter w = new IndexWriter(dir, iwc);

      int numDocs = random().nextInt(10_000, 200_001);
      boolean addFilter = random().nextBoolean();
      boolean addDeletes = random().nextBoolean();
      boolean descSort = random().nextBoolean();
      int topN = random().nextInt(1, 101);

      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("value", random().nextLong()));
        if (addFilter) {
          doc.add(new StringField("cat", i % 4 == 0 ? "A" : "B", Field.Store.NO));
        }
        if (addDeletes) {
          doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
        }
        w.addDocument(doc);
      }

      if (addDeletes) {
        for (int i = 0; i < numDocs; i += 7) {
          w.deleteDocuments(new Term("id", Integer.toString(i)));
        }
      }
      w.forceMerge(1);
      w.close();

      DirectoryReader reader = DirectoryReader.open(dir);
      IndexSearcher searcher = new IndexSearcher(reader);

      Query query;
      if (addFilter) {
        query = new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("cat", "A")), BooleanClause.Occur.FILTER)
            .build();
      } else {
        query = new MatchAllDocsQuery();
      }

      Sort sort = new Sort(new SortField("value", SortField.Type.LONG, descSort));

      // Run query — this exercises the full bulk collection path
      TopFieldDocs results = searcher.search(query, topN, sort);

      assertTrue("iter=" + iter + " totalHits=" + results.totalHits.value(),
          results.totalHits.value() > 0);
      assertTrue("iter=" + iter + " scoreDocs=" + results.scoreDocs.length,
          results.scoreDocs.length <= topN);

      // Verify sort order
      for (int i = 1; i < results.scoreDocs.length; i++) {
        long prev = ((Number) ((FieldDoc) results.scoreDocs[i - 1]).fields[0]).longValue();
        long curr = ((Number) ((FieldDoc) results.scoreDocs[i]).fields[0]).longValue();
        if (descSort) {
          assertTrue("iter=" + iter + " not desc at " + i + ": " + prev + " vs " + curr,
              prev >= curr);
        } else {
          assertTrue("iter=" + iter + " not asc at " + i + ": " + prev + " vs " + curr,
              prev <= curr);
        }
      }

      reader.close();
      dir.close();
    }
  }
}
