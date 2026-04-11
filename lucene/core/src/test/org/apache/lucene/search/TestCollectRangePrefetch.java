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
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Tests that TopFieldCollector.collectRange uses the collector-side one-batch-ahead
 * prefetch pipeline with range-based APIs (prefetchRange / longValuesRange).
 */
public class TestCollectRangePrefetch extends LuceneTestCase {

  /**
   * Test correctness: MatchAll + numeric sort via collectRange path must produce
   * identical results to the per-doc collect(int) path.
   */
  public void testCollectRangeMatchesPerDoc() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 50_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("value", numDocs - i)); // descending values
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, false)); // asc
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    assertEquals(10, results.scoreDocs.length);

    // The smallest values are numDocs-i for i=numDocs-1..numDocs-10, i.e., 1..10
    // Sorted ascending, first result should have value=1 (doc numDocs-1)
    for (int i = 0; i < 10; i++) {
      FieldDoc fd = (FieldDoc) results.scoreDocs[i];
      assertEquals("Position " + i, (long) (i + 1), ((Number) fd.fields[0]).longValue());
    }

    reader.close();
    dir.close();
  }

  /** Test with descending sort — most common case (e.g., sort by @timestamp desc). */
  public void testCollectRangeDescSort() throws Exception {
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

    Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true)); // desc
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    // Top 10 should be the highest timestamps: 99999, 99998, ..., 99990
    for (int i = 0; i < 10; i++) {
      FieldDoc fd = (FieldDoc) results.scoreDocs[i];
      assertEquals("Position " + i, (long) (numDocs - 1 - i), ((Number) fd.fields[0]).longValue());
    }

    reader.close();
    dir.close();
  }

  /** Test with deleted docs — collectRange should NOT be called (acceptDocs != null). */
  public void testWithDeletedDocs() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 20_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("value", i));
      doc.add(new org.apache.lucene.document.StringField(
          "id", Integer.toString(i), org.apache.lucene.document.Field.Store.NO));
      w.addDocument(doc);
    }
    for (int i = 0; i < numDocs; i += 5) {
      w.deleteDocuments(new org.apache.lucene.index.Term("id", Integer.toString(i)));
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    int expectedLive = numDocs - (numDocs / 5);
    assertEquals(expectedLive, results.totalHits.value());

    reader.close();
    dir.close();
  }

  /** Test with searchAfter pagination — exercises PagingFieldCollector.collectRange. */
  public void testSearchAfterPagination() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 50_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("value", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));

    // First page
    TopFieldDocs page1 = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(10, page1.scoreDocs.length);
    FieldDoc lastOnPage1 = (FieldDoc) page1.scoreDocs[9];

    // Second page via searchAfter
    TopDocs page2 = searcher.searchAfter(lastOnPage1, new MatchAllDocsQuery(), 10, sort);
    assertEquals(10, page2.scoreDocs.length);

    // Page 2 values should be strictly less than page 1's last value (desc sort)
    long lastPage1Value = ((Number) lastOnPage1.fields[0]).longValue();
    for (ScoreDoc sd : page2.scoreDocs) {
      FieldDoc fd = (FieldDoc) sd;
      long val = ((Number) fd.fields[0]).longValue();
      assertTrue("Page 2 value " + val + " should be < " + lastPage1Value, val < lastPage1Value);
    }

    reader.close();
    dir.close();
  }

  /** Test with small doc count (less than batch size) — single batch, no pipeline. */
  public void testSmallDocCount() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 100;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("value", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 10, sort);

    assertEquals(numDocs, results.totalHits.value());
    for (int i = 0; i < 10; i++) {
      FieldDoc fd = (FieldDoc) results.scoreDocs[i];
      assertEquals((long) (numDocs - 1 - i), ((Number) fd.fields[0]).longValue());
    }

    reader.close();
    dir.close();
  }

  /** Test with large topN — queue fills slowly, exercises collectAnyHit path. */
  public void testLargeTopN() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter w = new IndexWriter(dir, iwc);

    int numDocs = 20_000;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("value", i));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    w.close();

    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);

    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));
    TopFieldDocs results = searcher.search(new MatchAllDocsQuery(), 5000, sort);

    assertEquals(numDocs, results.totalHits.value());
    assertEquals(5000, results.scoreDocs.length);
    // First result should be the max value
    FieldDoc first = (FieldDoc) results.scoreDocs[0];
    assertEquals((long) (numDocs - 1), ((Number) first.fields[0]).longValue());

    reader.close();
    dir.close();
  }
}
