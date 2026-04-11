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

/**
 * Global configuration for bulk prefetch behavior. When disabled, all bulk prefetch
 * methods (longValues, ordValues, prefetchOrdinals, collect(DocIdStream) bulk paths)
 * fall back to their default per-doc implementations without issuing prefetch calls.
 *
 * <p>This is intended to be set by the hosting application (e.g., OpenSearch) to
 * dynamically enable/disable prefetch at runtime for benchmarking and A/B testing.
 *
 * @lucene.experimental
 */
public final class PrefetchConfig {

  /** Whether bulk prefetch is enabled. Reads from system property at startup, default true. */
  private static volatile boolean enabled = !"false".equalsIgnoreCase(
      System.getProperty("lucene.prefetch.enabled", "true"));

  /** Batch size for bulk doc values prefetch in aggregators. Default 4096. */
  private static volatile int batchSize = Integer.parseInt(
      System.getProperty("lucene.prefetch.batchSize", "4096"));

  /** Cache block size in bytes for debug logging. Default 32768 (32KB). */
  private static volatile int cacheBlockSize = Integer.parseInt(
      System.getProperty("lucene.prefetch.cacheBlockSize", "32768"));

  private PrefetchConfig() {}


  /** Whether debug logging for prefetch is enabled. */
  private static volatile boolean debugEnabled = "true".equalsIgnoreCase(
      System.getProperty("lucene.prefetch.debug", "false"));

  /** Returns true if bulk prefetch is enabled. */
  public static boolean isEnabled() {
    return enabled;
  }

  /** Returns true if prefetch debug logging is enabled. */
  public static boolean isDebugEnabled() {
    return debugEnabled;
  }

  /** Set whether prefetch debug logging is enabled. Thread-safe. */
  public static void setDebugEnabled(boolean value) {
    debugEnabled = value;
  }

  /** Set whether bulk prefetch is enabled. Thread-safe. */
  public static void setEnabled(boolean value) {
    enabled = value;
  }

  /** Returns the batch size for bulk doc values prefetch. */
  public static int getBatchSize() {
    return batchSize;
  }

  /** Set the batch size for bulk doc values prefetch. Thread-safe. */
  public static void setBatchSize(int value) {
    batchSize = value;
  }

  /** Returns the cache block size in bytes (for debug logging). Default 32KB. */
  public static int getCacheBlockSize() {
    return cacheBlockSize;
  }

  /** Set the cache block size in bytes. Thread-safe. */
  public static void setCacheBlockSize(int value) {
    cacheBlockSize = value;
  }
}
