# Search Test Suite - Rust Runner

This directory is a placeholder for the Rust-based test runner implementation.

## How to Implement

A conforming Rust test runner should:

1. **Parse** test JSON files from the `tests/` directory
2. **Resolve** referenced dataset and schema files from `datasets/` and `schemas/`
3. **Create** an in-memory index from the dataset using the schema's field definitions
4. **Build** the query from the `query` block using your Rust search engine's API
5. **Execute** the query against the index
6. **Assert** against the `expected` block

## Suggested Crate Structure

```
src/
├── main.rs          # CLI entry point
├── loader.rs        # JSON file loading and parsing
├── indexer.rs       # Document indexing from dataset+schema
├── query.rs         # Query building from JSON definition
├── validator.rs     # Result validation against expected block
└── types.rs         # Shared types (Dataset, Schema, TestCase, Expected, etc.)
```

## Key Types

```rust
struct Dataset {
    id: String,
    description: String,
    documents: Vec<Document>,
}

struct Schema {
    id: String,
    description: String,
    fields: Vec<FieldDef>,
}

struct FieldDef {
    name: String,
    field_type: FieldType, // keyword, text, integer, etc.
    stored: bool,
    indexed: bool,
    analyzer: Option<AnalyzerConfig>,
}

struct TestSuite {
    description: String,
    dataset: String,
    schema: String,
    tests: Vec<TestCase>,
}

struct TestCase {
    id: String,
    description: String,
    query: QueryDef,
    expected: Expected,
}

struct Expected {
    count: Option<usize>,
    count_min: Option<usize>,
    ordered: Option<Vec<String>>,
    hits: Option<Hits>,
    match_field: Option<String>,
}
```

## Running

```bash
cargo run -- /path/to/search-test-suite
```
