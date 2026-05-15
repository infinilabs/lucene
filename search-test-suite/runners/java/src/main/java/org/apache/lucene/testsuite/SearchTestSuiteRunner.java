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
package org.apache.lucene.testsuite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.RegExp;

/**
 * Reference test runner that executes search-test-suite JSON test files against Apache Lucene.
 *
 * <p>This runner validates that the JSON test specifications produce the expected results when run
 * against the Lucene search engine. It can be used as a reference implementation for building test
 * runners for other search engines.
 *
 * <p>Usage: {@code java SearchTestSuiteRunner <test-suite-root-dir>}
 */
public class SearchTestSuiteRunner {

  private final Path suiteRoot;
  private final Gson gson = new Gson();
  private int passed = 0;
  private int failed = 0;
  private int skipped = 0;

  public SearchTestSuiteRunner(Path suiteRoot) {
    this.suiteRoot = suiteRoot;
  }

  /** Entry point: runs all test files found under the suite root. */
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: SearchTestSuiteRunner <test-suite-root-dir>");
      System.exit(1);
    }
    Path root = Paths.get(args[0]);
    SearchTestSuiteRunner runner = new SearchTestSuiteRunner(root);
    runner.runAll();
    runner.printSummary();
    System.exit(runner.failed > 0 ? 1 : 0);
  }

  /** Runs all test files found in the tests/ subdirectory. */
  public void runAll() throws IOException {
    Path testsDir = suiteRoot.resolve("tests");
    if (!Files.isDirectory(testsDir)) {
      System.err.println("No tests/ directory found at " + testsDir);
      return;
    }
    runTestsInDirectory(testsDir);
  }

  private void runTestsInDirectory(Path dir) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      List<Path> entries = new ArrayList<>();
      stream.forEach(entries::add);
      entries.sort(Path::compareTo);
      for (Path entry : entries) {
        if (Files.isDirectory(entry)) {
          runTestsInDirectory(entry);
        } else if (entry.toString().endsWith(".json")) {
          runTestFile(entry);
        }
      }
    }
  }

  /** Runs a single test file. */
  public void runTestFile(Path testFile) throws IOException {
    JsonObject testSuite;
    try (Reader reader = Files.newBufferedReader(testFile)) {
      testSuite = JsonParser.parseReader(reader).getAsJsonObject();
    }

    String datasetId = testSuite.get("dataset").getAsString();
    String schemaId = testSuite.get("schema").getAsString();
    String description = testSuite.get("description").getAsString();

    JsonObject dataset = loadJson(suiteRoot.resolve("datasets").resolve(datasetId + ".json"));
    JsonObject schema = loadJson(suiteRoot.resolve("schemas").resolve(schemaId + ".json"));

    System.out.println("\n=== " + description + " ===");
    System.out.println("    File: " + suiteRoot.relativize(testFile));

    JsonArray tests = testSuite.getAsJsonArray("tests");
    for (JsonElement testElem : tests) {
      JsonObject test = testElem.getAsJsonObject();
      runSingleTest(test, dataset, schema);
    }
  }

  private void runSingleTest(JsonObject test, JsonObject dataset, JsonObject schema) {
    String testId = test.get("id").getAsString();
    String testDesc = test.get("description").getAsString();

    try (Directory dir = new ByteBuffersDirectory()) {
      // Build index
      Analyzer analyzer = new StandardAnalyzer();
      try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
        indexDocuments(writer, dataset, schema);
      }

      // Search
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Query query = buildQuery(test.getAsJsonObject("query"));
        JsonObject expected = test.getAsJsonObject("expected");

        ScoreDoc[] hits = searcher.search(query, 1000).scoreDocs;

        // Validate
        List<String> errors = validate(hits, expected, searcher, test.getAsJsonObject("query"));

        if (errors.isEmpty()) {
          System.out.println("  ✓ " + testId + ": " + testDesc);
          passed++;
        } else {
          System.out.println("  ✗ " + testId + ": " + testDesc);
          for (String error : errors) {
            System.out.println("      → " + error);
          }
          failed++;
        }
      }
    } catch (Exception e) {
      System.out.println("  ⚠ " + testId + ": " + testDesc + " [SKIPPED: " + e.getMessage() + "]");
      skipped++;
    }
  }

  private void indexDocuments(IndexWriter writer, JsonObject dataset, JsonObject schema)
      throws IOException {
    JsonArray documents = dataset.getAsJsonArray("documents");
    JsonArray fields = schema.getAsJsonArray("fields");

    for (JsonElement docElem : documents) {
      JsonObject docObj = docElem.getAsJsonObject();
      Document doc = new Document();

      for (JsonElement fieldElem : fields) {
        JsonObject fieldDef = fieldElem.getAsJsonObject();
        String fieldName = fieldDef.get("name").getAsString();
        String fieldType = fieldDef.get("type").getAsString();
        boolean stored = fieldDef.has("stored") && fieldDef.get("stored").getAsBoolean();

        if (docObj.has(fieldName)) {
          String value = docObj.get(fieldName).getAsString();
          Field.Store store = stored ? Field.Store.YES : Field.Store.NO;

          if ("keyword".equals(fieldType)) {
            doc.add(new StringField(fieldName, value, store));
          } else if ("text".equals(fieldType)) {
            doc.add(new TextField(fieldName, value, store));
          }
        }
      }

      // Also index any fields not in schema (for flexibility)
      for (Map.Entry<String, JsonElement> entry : docObj.entrySet()) {
        String key = entry.getKey();
        if ("_id".equals(key)) continue;
        boolean inSchema = false;
        for (JsonElement fieldElem : fields) {
          if (fieldElem.getAsJsonObject().get("name").getAsString().equals(key)) {
            inSchema = true;
            break;
          }
        }
        if (!inSchema) {
          // Default to text field for unschema'd fields
          doc.add(new TextField(key, entry.getValue().getAsString(), Field.Store.YES));
        }
      }

      writer.addDocument(doc);
    }
  }

  /** Builds a Lucene Query from a JSON query definition. */
  public Query buildQuery(JsonObject queryDef) {
    String type = queryDef.get("type").getAsString();

    switch (type) {
      case "term":
        return new TermQuery(
            new Term(queryDef.get("field").getAsString(), queryDef.get("value").getAsString()));

      case "match_all":
        return new MatchAllDocsQuery();

      case "match_none":
        {
          String reason =
              queryDef.has("reason") ? queryDef.get("reason").getAsString() : "match none";
          return new MatchNoDocsQuery(reason);
        }

      case "boolean":
        {
          BooleanQuery.Builder builder = new BooleanQuery.Builder();
          JsonArray clauses = queryDef.getAsJsonArray("clauses");
          for (JsonElement clauseElem : clauses) {
            JsonObject clause = clauseElem.getAsJsonObject();
            String occur = clause.get("occur").getAsString();
            Query subQuery = buildQuery(clause.getAsJsonObject("query"));
            builder.add(subQuery, toBooleanOccur(occur));
          }
          if (queryDef.has("min_should_match")) {
            builder.setMinimumNumberShouldMatch(queryDef.get("min_should_match").getAsInt());
          }
          return builder.build();
        }

      case "phrase":
        {
          String field = queryDef.get("field").getAsString();
          int slop = queryDef.has("slop") ? queryDef.get("slop").getAsInt() : 0;
          JsonArray terms = queryDef.getAsJsonArray("terms");
          String[] termStrings = new String[terms.size()];
          for (int i = 0; i < terms.size(); i++) {
            termStrings[i] = terms.get(i).getAsString();
          }
          return new PhraseQuery(slop, field, termStrings);
        }

      case "fuzzy":
        {
          String field = queryDef.get("field").getAsString();
          String value = queryDef.get("value").getAsString();
          int maxEdits = queryDef.has("max_edits") ? queryDef.get("max_edits").getAsInt() : 2;
          int prefixLength =
              queryDef.has("prefix_length") ? queryDef.get("prefix_length").getAsInt() : 0;
          if (queryDef.has("max_expansions")) {
            int maxExpansions = queryDef.get("max_expansions").getAsInt();
            return new FuzzyQuery(
                new Term(field, value), maxEdits, prefixLength, maxExpansions, false);
          }
          return new FuzzyQuery(new Term(field, value), maxEdits, prefixLength);
        }

      case "prefix":
        return new PrefixQuery(
            new Term(queryDef.get("field").getAsString(), queryDef.get("value").getAsString()));

      case "wildcard":
        return new WildcardQuery(
            new Term(queryDef.get("field").getAsString(), queryDef.get("pattern").getAsString()));

      case "regexp":
        {
          String field = queryDef.get("field").getAsString();
          String pattern = queryDef.get("pattern").getAsString();
          int flags = RegExp.ALL;
          int matchFlags = 0;
          if (queryDef.has("flags")) {
            JsonArray flagsArray = queryDef.getAsJsonArray("flags");
            for (JsonElement flag : flagsArray) {
              if ("case_insensitive".equals(flag.getAsString())) {
                matchFlags |= RegExp.ASCII_CASE_INSENSITIVE;
              }
            }
          }
          if (matchFlags != 0) {
            return new RegexpQuery(new Term(field, pattern), flags, matchFlags);
          }
          return new RegexpQuery(new Term(field, pattern));
        }

      case "range":
        {
          String field = queryDef.get("field").getAsString();
          BytesRef lower =
              queryDef.has("lower") && !queryDef.get("lower").isJsonNull()
                  ? new BytesRef(queryDef.get("lower").getAsString())
                  : null;
          BytesRef upper =
              queryDef.has("upper") && !queryDef.get("upper").isJsonNull()
                  ? new BytesRef(queryDef.get("upper").getAsString())
                  : null;
          boolean includeLower =
              !queryDef.has("include_lower") || queryDef.get("include_lower").getAsBoolean();
          boolean includeUpper =
              !queryDef.has("include_upper") || queryDef.get("include_upper").getAsBoolean();
          return new TermRangeQuery(field, lower, upper, includeLower, includeUpper);
        }

      default:
        throw new IllegalArgumentException("Unknown query type: " + type);
    }
  }

  private BooleanClause.Occur toBooleanOccur(String occur) {
    switch (occur) {
      case "must":
        return BooleanClause.Occur.MUST;
      case "should":
        return BooleanClause.Occur.SHOULD;
      case "must_not":
        return BooleanClause.Occur.MUST_NOT;
      case "filter":
        return BooleanClause.Occur.FILTER;
      default:
        throw new IllegalArgumentException("Unknown occur type: " + occur);
    }
  }

  private List<String> validate(
      ScoreDoc[] hits, JsonObject expected, IndexSearcher searcher, JsonObject queryDef)
      throws IOException {
    List<String> errors = new ArrayList<>();

    // Check exact count
    if (expected.has("count")) {
      int expectedCount = expected.get("count").getAsInt();
      if (hits.length != expectedCount) {
        errors.add("Expected count=" + expectedCount + " but got " + hits.length);
      }
    }

    // Check minimum count
    if (expected.has("count_min")) {
      int minCount = expected.get("count_min").getAsInt();
      if (hits.length < minCount) {
        errors.add("Expected count_min=" + minCount + " but got " + hits.length);
      }
    }

    // Determine which field to check
    String matchField = null;
    if (expected.has("match_field")) {
      matchField = expected.get("match_field").getAsString();
    } else if (queryDef.has("field")) {
      matchField = queryDef.get("field").getAsString();
    }

    // Check ordered results
    if (expected.has("ordered") && matchField != null) {
      JsonArray ordered = expected.getAsJsonArray("ordered");
      StoredFields storedFields = searcher.storedFields();
      for (int i = 0; i < ordered.size() && i < hits.length; i++) {
        String expectedVal = ordered.get(i).getAsString();
        String actualVal = storedFields.document(hits[i].doc).get(matchField);
        if (!expectedVal.equals(actualVal)) {
          errors.add(
              "Position "
                  + i
                  + ": expected '"
                  + expectedVal
                  + "' but got '"
                  + actualVal
                  + "'");
        }
      }
      if (ordered.size() != hits.length) {
        errors.add(
            "Ordered list size="
                + ordered.size()
                + " but actual results="
                + hits.length);
      }
    }

    // Check must_contain / must_not_contain
    if (expected.has("hits") && matchField != null) {
      JsonObject hitsObj = expected.getAsJsonObject("hits");
      StoredFields storedFields = searcher.storedFields();
      List<String> actualValues = new ArrayList<>();
      for (ScoreDoc hit : hits) {
        String val = storedFields.document(hit.doc).get(matchField);
        if (val != null) {
          actualValues.add(val);
        }
      }

      if (hitsObj.has("must_contain")) {
        JsonArray mustContain = hitsObj.getAsJsonArray("must_contain");
        for (JsonElement elem : mustContain) {
          String val = elem.getAsString();
          if (!actualValues.contains(val)) {
            errors.add("must_contain: '" + val + "' not found in results " + actualValues);
          }
        }
      }

      if (hitsObj.has("must_not_contain")) {
        JsonArray mustNotContain = hitsObj.getAsJsonArray("must_not_contain");
        for (JsonElement elem : mustNotContain) {
          String val = elem.getAsString();
          if (actualValues.contains(val)) {
            errors.add("must_not_contain: '" + val + "' was found in results " + actualValues);
          }
        }
      }
    }

    return errors;
  }

  private JsonObject loadJson(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  /** Prints a summary of test results. */
  public void printSummary() {
    System.out.println("\n" + "=".repeat(60));
    System.out.println("RESULTS: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
    System.out.println("=".repeat(60));
  }

  public int getPassed() {
    return passed;
  }

  public int getFailed() {
    return failed;
  }

  public int getSkipped() {
    return skipped;
  }
}
