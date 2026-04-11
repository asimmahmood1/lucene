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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.FieldValueHitQueue.Entry;
import org.apache.lucene.search.TotalHits.Relation;
import org.apache.lucene.search.comparators.NumericComparator;

/**
 * A {@link Collector} that sorts by {@link SortField} using {@link FieldComparator}s.
 *
 * <p>See the constructor of {@link TopFieldCollectorManager} for instantiating a
 * TopFieldCollectorManager with support for concurrency in IndexSearcher.
 *
 * @lucene.experimental
 */
public abstract class TopFieldCollector extends TopDocsCollector<Entry> {

  // TODO: one optimization we could do is to pre-fill
  // the queue with sentinel value that guaranteed to
  // always compare lower than a real hit; this would
  // save having to check queueFull on each insert

  private abstract class TopFieldLeafCollector implements LeafCollector {

    final LeafFieldComparator comparator;
    final int reverseMul;
    Scorable scorer;
    boolean collectedAllCompetitiveHits = false;

    // Bulk collection state — null when bulk path is not available
    final BulkValueComparator bulkValueComparator;
    final NumericDocValues bulkDocValues;
    final SortedDocValues bulkSortedDocValues;  // non-null for keyword sort
    final long bulkMissingValue;
    final int bulkMissingOrd;  // missing ordinal for keyword sort (-1 or Integer.MAX_VALUE)
    int[] docBuffer;    // lazily allocated int[PrefetchConfig.getBatchSize()]
    long[] valueBuffer; // lazily allocated long[PrefetchConfig.getBatchSize()]
    int[] ordBuffer;    // lazily allocated int[PrefetchConfig.getBatchSize()] for keyword sort

    // Multi-field sort state — null for single-field sorts
    final boolean isMultiSort;
    final LeafFieldComparator[] allComparators;  // null for single sort
    final int[] allReverseMul;                   // null for single sort
    final int firstReverseMul;                   // reverseMul for primary comparator

    TopFieldLeafCollector(FieldValueHitQueue<Entry> queue, Sort sort, LeafReaderContext context)
        throws IOException {
      // as all segments are sorted in the same way, enough to check only the 1st segment for
      // indexSort
      if (searchSortPartOfIndexSort == null) {
        final Sort indexSort = context.reader().getMetaData().sort();
        searchSortPartOfIndexSort = canEarlyTerminate(sort, indexSort);
        if (searchSortPartOfIndexSort) {
          firstComparator.disableSkipping();
        }
      }
      LeafFieldComparator[] comparators = queue.getComparators(context);
      int[] reverseMuls = queue.getReverseMul();
      if (comparators.length == 1) {
        this.reverseMul = reverseMuls[0];
        this.comparator = comparators[0];
      } else {
        this.reverseMul = 1;
        this.comparator = new MultiLeafFieldComparator(comparators, reverseMuls);
      }

      // Detect BulkValueComparator support for bulk collect(DocIdStream) path.
      // Defaults to null (disabled). Only enabled for numeric sorts without scoring.
      int detectedFirstRevMul = this.reverseMul;
      BulkValueComparator detectedBvc = null;
      NumericDocValues detectedDv = null;
      long detectedMv = 0;
      boolean detectedMulti = false;
      LeafFieldComparator[] detectedComps = null;
      int[] detectedRevMul = null;

      SortedDocValues detectedSdv = null;
      int detectedMissingOrd = -1;

      if (!needsScores && !canSetMinScore && PrefetchConfig.isEnabled()) {
        // Try single-field numeric sort
        if (this.comparator instanceof BulkValueComparator bvc
                && this.comparator instanceof NumericComparator<?>.NumericLeafComparator nlc) {
          detectedBvc = bvc;
          detectedDv = nlc.getDocValues();
          Object mv = sort.getSort()[0].getMissingValue();
          detectedMv = mv instanceof Number n ? n.longValue() : 0L;
        }
        // Try multi-field sort (primary field must support bulk)
        else if (this.comparator instanceof MultiLeafFieldComparator mlfc) {
          LeafFieldComparator first = mlfc.getFirstComparator();
          if (first instanceof BulkValueComparator bvc
                  && first instanceof NumericComparator<?>.NumericLeafComparator nlc) {
            detectedBvc = bvc;
            detectedDv = nlc.getDocValues();
            Object mv = sort.getSort()[0].getMissingValue();
            detectedMv = mv instanceof Number n ? n.longValue() : 0L;
            detectedMulti = true;
            detectedComps = mlfc.getComparators();
            detectedRevMul = mlfc.getReverseMul();
            detectedFirstRevMul = mlfc.getFirstReverseMul();
          }
        }
      }
      this.bulkValueComparator = detectedBvc;
      this.bulkDocValues = detectedDv;
      this.bulkSortedDocValues = detectedSdv;
      this.bulkMissingValue = detectedMv;
      this.bulkMissingOrd = detectedMissingOrd;
      this.isMultiSort = detectedMulti;
      this.allComparators = detectedComps;
      this.allReverseMul = detectedRevMul;
      this.firstReverseMul = detectedFirstRevMul;
    }

    /** Fetch values for the current batch of docs. Uses NumericDocValues or SortedDocValues. */
    void fetchBulkValues(int count) throws IOException {
      if (bulkSortedDocValues != null) {
        bulkSortedDocValues.ordValues(count, docBuffer, ordBuffer, -1);
        for (int i = 0; i < count; i++) {
          valueBuffer[i] = ordBuffer[i];
        }
      } else {
        bulkDocValues.longValues(count, docBuffer, valueBuffer, bulkMissingValue);
      }
    }

    void countHit() throws IOException {
      int hitCountSoFar = ++totalHits;

      if (minScoreAcc != null && (hitCountSoFar & minScoreAcc.modInterval) == 0) {
        updateGlobalMinCompetitiveScore(scorer);
      }
      if (scoreMode.isExhaustive() == false
          && totalHitsRelation == TotalHits.Relation.EQUAL_TO
          && totalHits > totalHitsThreshold) {
        // for the first time hitsThreshold is reached, notify comparator about this
        comparator.setHitsThresholdReached();
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
    }

    boolean thresholdCheck(int doc) throws IOException {
      if (collectedAllCompetitiveHits || reverseMul * comparator.compareBottom(doc) <= 0) {
        // since docs are visited in doc Id order, if compare is 0, it means
        // this document is larger than anything else in the queue, and
        // therefore not competitive.
        if (searchSortPartOfIndexSort) {
          if (totalHits > totalHitsThreshold) {
            totalHitsRelation = Relation.GREATER_THAN_OR_EQUAL_TO;
            throw new CollectionTerminatedException();
          } else {
            collectedAllCompetitiveHits = true;
          }
        } else if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
          // we can start setting the min competitive score if the
          // threshold is reached for the first time here.
          updateMinCompetitiveScore(scorer);
        }
        return true;
      }
      return false;
    }

    void collectCompetitiveHit(int doc) throws IOException {
      // This hit is competitive - replace bottom element in queue & adjustTop
      comparator.copy(bottom.slot, doc);
      updateBottom(doc);
      comparator.setBottom(bottom.slot);
      updateMinCompetitiveScore(scorer);
    }

    void collectAnyHit(int doc, int hitsCollected) throws IOException {
      // Startup transient: queue hasn't gathered numHits yet
      int slot = hitsCollected - 1;
      // Copy hit into queue
      comparator.copy(slot, doc);
      add(slot, doc);
      if (queueFull) {
        comparator.setBottom(bottom.slot);
        updateMinCompetitiveScore(scorer);
      }
    }

    /** Threshold check using pre-fetched values. Returns true if doc is not competitive. */
    boolean bulkThresholdCheck(int idx) throws IOException {
      int cmp;
      if (isMultiSort) {
        cmp = firstReverseMul * bulkValueComparator.compareBottomAt(idx);
        if (cmp == 0) {
          // Primary tied — fall back to secondary comparators per-doc
          for (int j = 1; j < allComparators.length; j++) {
            cmp = allReverseMul[j] * allComparators[j].compareBottom(docBuffer[idx]);
            if (cmp != 0) break;
          }
        }
      } else {
        cmp = reverseMul * bulkValueComparator.compareBottomAt(idx);
      }

      if (collectedAllCompetitiveHits || cmp <= 0) {
        // Same early termination / min score logic as thresholdCheck(int doc)
        if (searchSortPartOfIndexSort) {
          if (totalHits > totalHitsThreshold) {
            totalHitsRelation = Relation.GREATER_THAN_OR_EQUAL_TO;
            throw new CollectionTerminatedException();
          } else {
            collectedAllCompetitiveHits = true;
          }
        } else if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
          updateMinCompetitiveScore(scorer);
        }
        return true;
      }
      return false;
    }

    /** Copy competitive hit using pre-fetched values. */
    void bulkCollectCompetitiveHit(int idx) throws IOException {
      bulkValueComparator.copyAt(bottom.slot, idx);
      if (isMultiSort) {
        for (int j = 1; j < allComparators.length; j++) {
          allComparators[j].copy(bottom.slot, docBuffer[idx]);
        }
      }
      updateBottom(docBuffer[idx]);
      comparator.setBottom(bottom.slot);
      updateMinCompetitiveScore(scorer);
    }

    /** Copy any hit (queue not full) using pre-fetched values. */
    void bulkCollectAnyHit(int idx, int hitsCollected) throws IOException {
      int slot = hitsCollected - 1;
      bulkValueComparator.copyAt(slot, idx);
      if (isMultiSort) {
        for (int j = 1; j < allComparators.length; j++) {
          allComparators[j].copy(slot, docBuffer[idx]);
        }
      }
      add(slot, docBuffer[idx]);
      if (queueFull) {
        comparator.setBottom(bottom.slot);
        updateMinCompetitiveScore(scorer);
      }
    }

    @Override
    public void setScorer(Scorable scorer) throws IOException {
      this.scorer = scorer;
      comparator.setScorer(scorer);
      if (minScoreAcc == null) {
        updateMinCompetitiveScore(scorer);
      } else {
        updateGlobalMinCompetitiveScore(scorer);
      }
    }

    @Override
    public DocIdSetIterator competitiveIterator() throws IOException {
      return comparator.competitiveIterator();
    }
  }

  static boolean canEarlyTerminate(Sort searchSort, Sort indexSort) {
    return canEarlyTerminateOnDocId(searchSort) || canEarlyTerminateOnPrefix(searchSort, indexSort);
  }

  private static boolean canEarlyTerminateOnDocId(Sort searchSort) {
    final SortField[] fields1 = searchSort.getSort();
    return SortField.FIELD_DOC.equals(fields1[0]);
  }

  private static boolean canEarlyTerminateOnPrefix(Sort searchSort, Sort indexSort) {
    if (indexSort != null) {
      final SortField[] fields1 = searchSort.getSort();
      final SortField[] fields2 = indexSort.getSort();
      // early termination is possible if fields1 is a prefix of fields2
      if (fields1.length > fields2.length) {
        return false;
      }
      return Arrays.asList(fields1).equals(Arrays.asList(fields2).subList(0, fields1.length));
    } else {
      return false;
    }
  }

  /*
   * Implements a TopFieldCollector over one SortField criteria, with tracking
   * document scores and maxScore.
   */
  static class SimpleFieldCollector extends TopFieldCollector {
    final Sort sort;
    final FieldValueHitQueue<Entry> queue;

    public SimpleFieldCollector(
        Sort sort,
        FieldValueHitQueue<Entry> queue,
        int numHits,
        int totalHitsThreshold,
        MaxScoreAccumulator minScoreAcc) {
      super(queue, numHits, totalHitsThreshold, sort.needsScores(), minScoreAcc);
      this.sort = sort;
      this.queue = queue;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      // reset the minimum competitive score
      minCompetitiveScore = 0f;
      docBase = context.docBase;

      LeafCollector collector =
          new TopFieldLeafCollector(queue, sort, context) {

            @Override
            public void collect(int doc) throws IOException {
              countHit();
              if (queueFull) {
                if (thresholdCheck(doc)) {
                  return;
                }
                collectCompetitiveHit(doc);
              } else {
                collectAnyHit(doc, totalHits);
              }
            }

            // TODO: This path has no one-batch-ahead prefetch lead time — prefetch and
            // read happen in the same batch (fetchBulkValues does inline prefetch+read).
            // The fix: the scorer (DefaultBulkScorer, BooleanScorer) should orchestrate a
            // window-rotation pipeline: prefetch(W1) before collect(W0), giving W0's async
            // IO a full window of lead time. The collector would expose a separate
            // prefetch(DocIdStream) method for async IO, and collect(DocIdStream) would
            // read from warm cache.
            // See prefetch-backlog.md "Scorer-side prefetch pipeline" for the full design.
            @Override
            public void collect(DocIdStream stream) throws IOException {
              if (bulkValueComparator == null) {
                super.collect(stream); // default per-doc fallback
                return;
              }
              if (docBuffer == null) {
                int batchSize = PrefetchConfig.getBatchSize();
                docBuffer = new int[batchSize];
                valueBuffer = new long[batchSize];
                if (bulkSortedDocValues != null) {
                  ordBuffer = new int[batchSize];
                }
              }
              for (int count = stream.intoArray(docBuffer);
                   count != 0;
                   count = stream.intoArray(docBuffer)) {
                fetchBulkValues(count);
                bulkValueComparator.setBatch(valueBuffer, docBuffer, count);
                for (int i = 0; i < count; i++) {
                  countHit();
                  if (queueFull) {
                    if (bulkThresholdCheck(i)) {
                      continue;
                    }
                    bulkCollectCompetitiveHit(i);
                  } else {
                    bulkCollectAnyHit(i, totalHits);
                  }
                }
              }
            }

            @Override
            public void collectRange(int min, int max) throws IOException {
              if (bulkValueComparator == null || bulkDocValues == null
                  ) {
                super.collectRange(min, max);
                return;
              }
              int batchSize = PrefetchConfig.getBatchSize();
              if (valueBuffer == null) {
                docBuffer = new int[batchSize];
                valueBuffer = new long[batchSize];
              }

              int pos = min;
              // Prefetch first batch before entering loop
              int firstSize = Math.min(batchSize, max - pos);
              bulkDocValues.prefetchRange(pos, firstSize);

              while (pos < max) {
                int batchEnd = Math.min(pos + batchSize, max);
                int size = batchEnd - pos;

                // Prefetch NEXT batch (one-ahead pipeline)
                int nextPos = batchEnd;
                if (nextPos < max) {
                  int nextSize = Math.min(batchSize, max - nextPos);
                  bulkDocValues.prefetchRange(nextPos, nextSize);
                }

                // Collect CURRENT batch — warm from prior prefetch
                bulkDocValues.longValuesRange(pos, size, valueBuffer, bulkMissingValue);
                // Fill docBuffer for setBatch (needed by copyAt for doc ID tracking)
                for (int i = 0; i < size; i++) {
                  docBuffer[i] = pos + i;
                }
                bulkValueComparator.setBatch(valueBuffer, docBuffer, size);
                for (int i = 0; i < size; i++) {
                  countHit();
                  if (queueFull) {
                    if (bulkThresholdCheck(i)) {
                      continue;
                    }
                    bulkCollectCompetitiveHit(i);
                  } else {
                    bulkCollectAnyHit(i, totalHits);
                  }
                }

                pos = batchEnd;
              }
            }
          };

      if (needsScores) {
        // score-based comparators may need to call score() multiple times, e.g. once for the
        // comparison, and once to copy the score into the priority queue
        collector = ScoreCachingWrappingScorer.wrap(collector);
      }

      return collector;
    }
  }

  /*
   * Implements a TopFieldCollector when after != null.
   */
  static final class PagingFieldCollector extends TopFieldCollector {

    final Sort sort;
    int collectedHits;
    final FieldValueHitQueue<Entry> queue;
    final FieldDoc after;

    public PagingFieldCollector(
        Sort sort,
        FieldValueHitQueue<Entry> queue,
        FieldDoc after,
        int numHits,
        int totalHitsThreshold,
        MaxScoreAccumulator minScoreAcc) {
      super(queue, numHits, totalHitsThreshold, sort.needsScores(), minScoreAcc);
      this.sort = sort;
      this.queue = queue;
      this.after = after;

      FieldComparator<?>[] comparators = queue.getComparators();
      // Tell all comparators their top value:
      for (int i = 0; i < comparators.length; i++) {
        @SuppressWarnings("unchecked")
        FieldComparator<Object> comparator = (FieldComparator<Object>) comparators[i];
        comparator.setTopValue(after.fields[i]);
      }
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      // reset the minimum competitive score
      minCompetitiveScore = 0f;
      docBase = context.docBase;
      final int afterDoc = after.doc - docBase;

      LeafCollector collector =
          new TopFieldLeafCollector(queue, sort, context) {

            @Override
            public void collect(int doc) throws IOException {
              countHit();
              if (queueFull) {
                if (thresholdCheck(doc)) {
                  return;
                }
              }
              final int topCmp = reverseMul * comparator.compareTop(doc);
              if (topCmp > 0 || (topCmp == 0 && doc <= afterDoc)) {
                // Already collected on a previous page
                if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
                  // check if totalHitsThreshold is reached and we can update competitive score
                  // necessary to account for possible update to global min competitive score
                  updateMinCompetitiveScore(scorer);
                }
                return;
              }
              if (queueFull) {
                collectCompetitiveHit(doc);
              } else {
                collectedHits++;
                collectAnyHit(doc, collectedHits);
              }
            }

            // TODO: Same as SimpleFieldCollector — no one-batch-ahead prefetch lead time.
            // Scorer-side pipeline needed for the collect(DocIdStream) path.
            @Override
            public void collect(DocIdStream stream) throws IOException {
              if (bulkValueComparator == null) {
                super.collect(stream); // default per-doc fallback
                return;
              }
              if (docBuffer == null) {
                int batchSize = PrefetchConfig.getBatchSize();
                docBuffer = new int[batchSize];
                valueBuffer = new long[batchSize];
                if (bulkSortedDocValues != null) {
                  ordBuffer = new int[batchSize];
                }
              }
              for (int count = stream.intoArray(docBuffer);
                   count != 0;
                   count = stream.intoArray(docBuffer)) {
                fetchBulkValues(count);
                bulkValueComparator.setBatch(valueBuffer, docBuffer, count);
                for (int i = 0; i < count; i++) {
                  countHit();
                  if (queueFull) {
                    if (bulkThresholdCheck(i)) {
                      continue;
                    }
                  }
                  // compareTop check for searchAfter pagination
                  int topCmp;
                  if (isMultiSort) {
                    topCmp = firstReverseMul * bulkValueComparator.compareTopAt(i);
                    if (topCmp == 0) {
                      for (int j = 1; j < allComparators.length; j++) {
                        topCmp = allReverseMul[j] * allComparators[j].compareTop(docBuffer[i]);
                        if (topCmp != 0) break;
                      }
                    }
                  } else {
                    topCmp = reverseMul * bulkValueComparator.compareTopAt(i);
                  }
                  if (topCmp > 0 || (topCmp == 0 && docBuffer[i] <= afterDoc)) {
                    // Already collected on a previous page
                    if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
                      updateMinCompetitiveScore(scorer);
                    }
                    continue;
                  }
                  if (queueFull) {
                    bulkCollectCompetitiveHit(i);
                  } else {
                    collectedHits++;
                    bulkCollectAnyHit(i, collectedHits);
                  }
                }
              }
            }

            @Override
            public void collectRange(int min, int max) throws IOException {
              if (bulkValueComparator == null || bulkDocValues == null
                  ) {
                super.collectRange(min, max);
                return;
              }
              int batchSize = PrefetchConfig.getBatchSize();
              if (valueBuffer == null) {
                docBuffer = new int[batchSize];
                valueBuffer = new long[batchSize];
              }

              int pos = min;
              int firstSize = Math.min(batchSize, max - pos);
              bulkDocValues.prefetchRange(pos, firstSize);

              while (pos < max) {
                int batchEnd = Math.min(pos + batchSize, max);
                int size = batchEnd - pos;

                int nextPos = batchEnd;
                if (nextPos < max) {
                  int nextSize = Math.min(batchSize, max - nextPos);
                  bulkDocValues.prefetchRange(nextPos, nextSize);
                }

                bulkDocValues.longValuesRange(pos, size, valueBuffer, bulkMissingValue);
                for (int i = 0; i < size; i++) {
                  docBuffer[i] = pos + i;
                }
                bulkValueComparator.setBatch(valueBuffer, docBuffer, size);
                for (int i = 0; i < size; i++) {
                  countHit();
                  if (queueFull) {
                    if (bulkThresholdCheck(i)) {
                      continue;
                    }
                  }
                  int topCmp;
                  if (isMultiSort) {
                    topCmp = firstReverseMul * bulkValueComparator.compareTopAt(i);
                    if (topCmp == 0) {
                      for (int j = 1; j < allComparators.length; j++) {
                        topCmp = allReverseMul[j] * allComparators[j].compareTop(docBuffer[i]);
                        if (topCmp != 0) break;
                      }
                    }
                  } else {
                    topCmp = reverseMul * bulkValueComparator.compareTopAt(i);
                  }
                  if (topCmp > 0 || (topCmp == 0 && docBuffer[i] <= afterDoc)) {
                    if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
                      updateMinCompetitiveScore(scorer);
                    }
                    continue;
                  }
                  if (queueFull) {
                    bulkCollectCompetitiveHit(i);
                  } else {
                    collectedHits++;
                    bulkCollectAnyHit(i, collectedHits);
                  }
                }

                pos = batchEnd;
              }
            }
          };

      if (needsScores) {
        // score-based comparators may need to call score() multiple times, e.g. once for the
        // comparison, and once to copy the score into the priority queue
        collector = ScoreCachingWrappingScorer.wrap(collector);
      }

      return collector;
    }
  }

  private static final ScoreDoc[] EMPTY_SCOREDOCS = new ScoreDoc[0];

  final int numHits;
  final int totalHitsThreshold;
  final FieldComparator<?> firstComparator;
  final boolean canSetMinScore;

  Boolean searchSortPartOfIndexSort = null; // shows if Search Sort if a part of the Index Sort

  // an accumulator that maintains the maximum of the segment's minimum competitive scores
  final MaxScoreAccumulator minScoreAcc;
  // the current local minimum competitive score already propagated to the underlying scorer
  float minCompetitiveScore;

  final int numComparators;
  FieldValueHitQueue.Entry bottom = null;
  boolean queueFull;
  int docBase;
  final boolean needsScores;
  final ScoreMode scoreMode;

  // Declaring the constructor private prevents extending this class by anyone
  // else. Note that the class cannot be final since it's extended by the
  // internal versions. If someone will define a constructor with any other
  // visibility, then anyone will be able to extend the class, which is not what
  // we want.
  private TopFieldCollector(
      FieldValueHitQueue<Entry> pq,
      int numHits,
      int totalHitsThreshold,
      boolean needsScores,
      MaxScoreAccumulator minScoreAcc) {
    super(pq);
    this.needsScores = needsScores;
    this.numHits = numHits;
    this.totalHitsThreshold = Math.max(totalHitsThreshold, numHits);
    this.numComparators = pq.getComparators().length;
    this.firstComparator = pq.getComparators()[0];
    int reverseMul = pq.getReverseMul()[0];

    if (firstComparator.getClass().equals(FieldComparator.RelevanceComparator.class)
        && reverseMul == 1 // if the natural sort is preserved (sort by descending relevance)
        && totalHitsThreshold != Integer.MAX_VALUE) {
      scoreMode = ScoreMode.TOP_SCORES;
      canSetMinScore = true;
    } else {
      canSetMinScore = false;
      if (totalHitsThreshold != Integer.MAX_VALUE) {
        scoreMode = needsScores ? ScoreMode.TOP_DOCS_WITH_SCORES : ScoreMode.TOP_DOCS;
      } else {
        scoreMode = needsScores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
      }
    }
    this.minScoreAcc = minScoreAcc;
  }

  @Override
  public ScoreMode scoreMode() {
    return scoreMode;
  }

  protected void updateGlobalMinCompetitiveScore(Scorable scorer) throws IOException {
    assert minScoreAcc != null;
    if (canSetMinScore) {
      // we can start checking the global maximum score even if the local queue is not full or if
      // the threshold is not reached on the local competitor: the fact that there is a shared min
      // competitive score implies that one of the collectors hit its totalHitsThreshold already
      long maxMinScore = minScoreAcc.getRaw();
      float score;
      if (maxMinScore != Long.MIN_VALUE
          && (score = DocScoreEncoder.toScore(maxMinScore)) > minCompetitiveScore) {
        scorer.setMinCompetitiveScore(score);
        minCompetitiveScore = score;
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
    }
  }

  protected void updateMinCompetitiveScore(Scorable scorer) throws IOException {
    if (canSetMinScore && queueFull && totalHits > totalHitsThreshold) {
      assert bottom != null;
      float minScore = (float) firstComparator.value(bottom.slot);
      if (minScore > minCompetitiveScore) {
        scorer.setMinCompetitiveScore(minScore);
        minCompetitiveScore = minScore;
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
        if (minScoreAcc != null) {
          minScoreAcc.accumulate(DocScoreEncoder.encode(docBase, minScore));
        }
      }
    }
  }

  /**
   * Populate {@link ScoreDoc#score scores} of the given {@code topDocs}.
   *
   * @param topDocs the top docs to populate
   * @param searcher the index searcher that has been used to compute {@code topDocs}
   * @param query the query that has been used to compute {@code topDocs}
   * @throws IllegalArgumentException if there is evidence that {@code topDocs} have been computed
   *     against a different searcher or a different query.
   * @lucene.experimental
   */
  public static void populateScores(ScoreDoc[] topDocs, IndexSearcher searcher, Query query)
      throws IOException {
    // Get the score docs sorted in doc id order
    topDocs = topDocs.clone();
    Arrays.sort(topDocs, Comparator.comparingInt(scoreDoc -> scoreDoc.doc));

    final Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE, 1);
    List<LeafReaderContext> contexts = searcher.getIndexReader().leaves();
    LeafReaderContext currentContext = null;
    Scorer currentScorer = null;
    for (ScoreDoc scoreDoc : topDocs) {
      if (currentContext == null
          || scoreDoc.doc >= currentContext.docBase + currentContext.reader().maxDoc()) {
        Objects.checkIndex(scoreDoc.doc, searcher.getIndexReader().maxDoc());
        int newContextIndex = ReaderUtil.subIndex(scoreDoc.doc, contexts);
        currentContext = contexts.get(newContextIndex);
        final ScorerSupplier scorerSupplier = weight.scorerSupplier(currentContext);
        if (scorerSupplier == null) {
          throw new IllegalArgumentException("Doc id " + scoreDoc.doc + " doesn't match the query");
        }
        currentScorer = scorerSupplier.get(1); // random-access
      }
      final int leafDoc = scoreDoc.doc - currentContext.docBase;
      assert leafDoc >= 0;
      final int advanced = currentScorer.iterator().advance(leafDoc);
      if (leafDoc != advanced) {
        throw new IllegalArgumentException("Doc id " + scoreDoc.doc + " doesn't match the query");
      }
      scoreDoc.score = currentScorer.score();
    }
  }

  final void add(int slot, int doc) {
    bottom = pq.add(new Entry(slot, docBase + doc));
    // The queue is full either when totalHits == numHits (in SimpleFieldCollector), in which case
    // slot = totalHits - 1, or when hitsCollected == numHits (in PagingFieldCollector this is hits
    // on the current page) and slot = hitsCollected - 1.
    assert slot < numHits;
    queueFull = slot == numHits - 1;
  }

  final void updateBottom(int doc) {
    // bottom.score is already set to Float.NaN in add().
    bottom.doc = docBase + doc;
    bottom = pq.updateTop();
  }

  /*
   * Only the following callback methods need to be overridden since
   * topDocs(int, int) calls them to return the results.
   */

  @Override
  protected void populateResults(ScoreDoc[] results, int howMany) {
    // avoid casting if unnecessary.
    FieldValueHitQueue<Entry> queue = (FieldValueHitQueue<Entry>) pq;
    for (int i = howMany - 1; i >= 0; i--) {
      results[i] = queue.fillFields(queue.pop());
    }
  }

  @Override
  protected TopDocs newTopDocs(ScoreDoc[] results, int start) {
    if (results == null) {
      results = EMPTY_SCOREDOCS;
    }

    // If this is a maxScoring tracking collector and there were no results,
    return new TopFieldDocs(
        new TotalHits(totalHits, totalHitsRelation),
        results,
        ((FieldValueHitQueue<Entry>) pq).getFields());
  }

  @Override
  public TopFieldDocs topDocs() {
    return (TopFieldDocs) super.topDocs();
  }

  /** Return whether collection terminated early. */
  public boolean isEarlyTerminated() {
    return totalHitsRelation == Relation.GREATER_THAN_OR_EQUAL_TO;
  }
}
