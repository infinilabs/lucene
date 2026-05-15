# Search Test Suite

A portable, engine-agnostic test specification format for validating search engine query implementations.

## Overview

This test suite captures the **semantic contract** of core search query operations — datasets, schemas, queries, and expected results — in a JSON format that can be consumed by any search engine implementation (Lucene, Elasticsearch, a Rust-based engine, etc.).

Tests are extracted from Apache Lucene's core query test suite but are deliberately **implementation-independent**: no Java types, no scorer internals, no codec details.

## Directory Structure

```
search-test-suite/
├── README.md              # This file
├── schema.json            # JSON Schema for validating test files
├── datasets/              # Reusable document datasets
├── schemas/               # Reusable field/index schemas
└── tests/                 # Test suites, one folder per query type
    ├── term_query/
    ├── boolean_query/
    ├── phrase_query/
    ├── fuzzy_query/
    ├── prefix_query/
    ├── wildcard_query/
    ├── range_query/
    ├── regexp_query/
    ├── match_all_query/
    └── match_none_query/
```

## Format Specification

### Dataset Files

Each dataset file (`datasets/*.json`) defines a reusable set of documents:

```json
{
  "id": "dataset-name",
  "description": "Human-readable description",
  "documents": [
    { "_id": "1", "field_name": "field_value", ... }
  ]
}
```

### Schema Files

Each schema file (`schemas/*.json`) defines the index field mappings:

```json
{
  "id": "schema-name",
  "description": "Human-readable description",
  "fields": [
    {
      "name": "field_name",
      "type": "text|keyword|integer|long|float|double|boolean|date",
      "stored": true,
      "indexed": true,
      "analyzer": {                    // optional, for "text" fields
        "tokenizer": "whitespace|standard",
        "lowercase": false,
        "position_increment_gap": 100
      }
    }
  ]
}
```

**Field Types:**
- `keyword` — Not analyzed, exact match (equivalent to Lucene `StringField`)
- `text` — Analyzed/tokenized (equivalent to Lucene `TextField`)
- `integer`, `long`, `float`, `double` — Numeric types
- `boolean`, `date` — Other common types

### Test Files

Each test file (`tests/<query_type>/*.json`) defines one or more test cases:

```json
{
  "description": "Test suite description",
  "source": "Original Lucene test class and method",
  "dataset": "dataset-id",
  "schema": "schema-id",
  "tests": [
    {
      "id": "unique-test-id",
      "description": "What this test verifies",
      "query": { ... },
      "expected": { ... }
    }
  ]
}
```

### Query Types

| Type | Key Fields |
|------|-----------|
| `term` | `field`, `value` |
| `boolean` | `clauses[]` with `occur` (must/should/must_not/filter), `min_should_match` |
| `phrase` | `field`, `terms[]`, `slop` |
| `fuzzy` | `field`, `value`, `max_edits`, `prefix_length`, `max_expansions` |
| `prefix` | `field`, `value` |
| `wildcard` | `field`, `pattern` |
| `regexp` | `field`, `pattern`, `flags` |
| `range` | `field`, `lower`, `upper`, `include_lower`, `include_upper` |
| `match_all` | _(none)_ |
| `match_none` | `reason` (optional) |

### Expected Results

```json
{
  "count": 3,                              // exact total hit count
  "count_min": 1,                          // minimum hits (for approximate checks)
  "ordered": ["val1", "val2"],             // strict order by relevance (field values)
  "hits": {
    "must_contain": ["val1", "val2"],      // these field values MUST appear in results
    "must_not_contain": ["val3"]           // these field values MUST NOT appear
  },
  "match_field": "field"                   // which stored field to check (default: query field)
}
```

## Writing a Test Runner

Any conforming test runner must:

1. **Read** a test JSON file
2. **Resolve** the referenced dataset and schema files
3. **Create** an in-memory index from the dataset using the schema's field definitions
4. **Build** the query from the `query` block
5. **Execute** the query against the index
6. **Assert** against the `expected` block:
   - `count` → exact match on total hits
   - `count_min` → total hits >= value
   - `ordered` → strict sequence match on stored field values in result order
   - `hits.must_contain` → all listed values are found in results
   - `hits.must_not_contain` → none of the listed values are found in results

## Validation

Use the provided `schema.json` to validate test files:

```bash
# Using ajv-cli (Node.js)
npx ajv validate -s schema.json -d "tests/**/*.json"

# Using check-jsonschema (Python)
check-jsonschema --schemafile schema.json tests/**/*.json
```

## License

This test suite is derived from Apache Lucene test cases, licensed under the Apache License, Version 2.0.
