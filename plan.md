# Plan: Add Sourcepath Support to Scala 3 Compiler

## Context

The Metals project is working on a feature called "sourcepath mode" that allows the presentation compiler to compile sources from dependencies by loading them from the sourcepath instead of using pre-compiled classfiles. This feature is currently available for Scala 2 and needs to be added to Scala 3.

### Why This Feature Exists

When working in a large codebase with many dependencies, developers often want to:

- Navigate to source code of dependencies
- See accurate diagnostics for dependency sources
- Get completions and hover information from uncompiled sources
- Work with sources that aren't yet compiled or are in a different directory structure

The sourcepath mode solves this by:

1. Loading source files from dependencies into the compiler's sourcepath
2. Pruning method/field bodies from these sources (keeping only signatures)
3. Allowing the compiler to type-check against these pruned sources instead of compiled classfiles

This approach is much faster than fully compiling dependencies and provides better diagnostics than using only classfiles.

### Current State

**Scala 2 Implementation** (in Metals repository at `/Users/tgodzik/Documents/metals`):

1. **LogicalSourcePath** - A custom ClassPath implementation that finds sources based on logical package structure (not physical directory layout)
2. **PruneLateSources** - A compiler phase that removes method/field bodies from sources loaded from sourcepath
3. **LogicalPackagesProvider** - Parses the entire sourcepath to build a package hierarchy
4. **MetalsBrowsingLoaders** - Custom symbol loaders that handle late compilation from sourcepath
5. **Test infrastructure** - PCDiagnosticsSuite, PCDiagnosticsWithSourcePath, FallbackClasspathSuite

**Scala 3 Current State:**

- No sourcepath support in presentation compiler
- Uses `InteractiveDriver` which is simpler but less extensible than Scala 2's `Global`
- No custom compiler phase mechanism exposed
- Different AST structure (`tpd`/`untpd` instead of Scala 2's trees)

## Objective

Implement sourcepath support in the Scala 3 compiler (scala/scala3 repository) to enable the same functionality that exists for Scala 2. This implementation should be done in the compiler itself, not in Metals.

---

## Implementation Plan

### Phase 1: Add Compiler Phase Infrastructure

#### 1.1 Create PruneSourcePath Phase

**Location:** `compiler/src/dotty/tools/dotc/transform/metals/PruneSourcePath.scala`

**Purpose:** A compiler phase that removes method and field bodies for sources loaded from sourcepath, replacing them with `???` (similar to Scala 2's PruneLateSources).

**Implementation approach:**

- Extend `MiniPhase` (simpler) or `Phase` (more control)
- Phase should run early, ideally after parsing but before typer (similar to Scala 2's placement after "parser" before "namer")
- Override `phaseName` to return `"metals-prune-sourcepath"`
- Use `TreeMap` or similar traversal mechanism to transform trees

**Key transformations:**

```scala
// For DefDef (method definitions):
// - Keep constructors unchanged
// - Keep abstract/deferred methods unchanged
// - Keep methods without explicit return type (need inference)
// - Replace bodies with ??? for methods with explicit types

// For ValDef (field definitions):
// - Keep abstract/deferred fields unchanged
// - Keep fields without explicit type (need inference)
// - Keep parameters unchanged
// - Replace RHS with ??? for fields with explicit types
```

**Tree pattern matching:**
Use Scala 3's `dotty.tools.dotc.ast.tpd` for typed trees or `dotty.tools.dotc.ast.untpd` for untyped trees:

- Match on `DefDef`, `ValDef` case classes
- Check for flags: `Deferred`, `Abstract`, `Macro`, `CaseAccessor`, `Param`
- Replace RHS with `ref(defn.Predef_???)`

#### 1.2 Register Phase Conditionally

**Location:** `compiler/src/dotty/tools/dotc/Compiler.scala`

**Approach:** Add the PruneSourcePath phase to the compiler pipeline conditionally based on a compiler setting.

**Changes:**

- Add phase to `phases` list in appropriate position (after parser, before typer)
- Make it conditional on a setting (e.g., `-Yprune-sourcepath` or similar)
- Ensure phase runs only for files marked as "from sourcepath"

---

### Phase 2: Sourcepath File Tracking

#### 2.1 Track Sourcepath Files

**Location:** `compiler/src/dotty/tools/dotc/CompilationUnit.scala`

**Purpose:** Add metadata to `CompilationUnit` to track whether a source was loaded from sourcepath vs. classpath.

**Implementation:**

- Add field: `val isFromSourcePath: Boolean` (or use existing extension mechanism)
- Set this flag when loading sources from sourcepath directories
- Use this flag in PruneSourcePath phase to decide whether to prune

#### 2.2 Sourcepath Configuration

**Location:** `compiler/src/dotty/tools/dotc/config/Settings.scala` and `PathResolver.scala`

**Purpose:** Ensure `-sourcepath` setting is properly parsed and accessible.

**Changes:**

- Verify `-sourcepath` setting exists and is properly handled
- Ensure `PathResolver` correctly separates sourcepath from classpath
- Make sourcepath directories accessible to compilation unit creation logic

---

### Phase 3: Logical Package Structure (Advanced Feature)

#### 3.1 Create LogicalSourcePath

**Location:** `compiler/src/dotty/tools/dotc/classpath/LogicalSourcePath.scala`

**Purpose:** A ClassPath implementation that can find sources based on their package declaration, not physical directory structure (mirrors Scala 2 implementation).

**Why needed:** This allows finding sources that don't follow the standard `src/main/scala/package/File.scala` structure, which is common in large projects.

**Implementation:**

- Extend or implement `ClassPath` interface
- Parse all sources in sourcepath to build a package hierarchy
- Provide lookup methods: `findSourceFile(className: String): Option[SourceFile]`
- Cache the package structure for performance

#### 3.2 Parse Sourcepath for Package Structure

**Location:** `compiler/src/dotty/tools/dotc/classpath/SourcePathParser.scala`

**Purpose:** Parse all source files in the sourcepath to extract package declarations and build a logical package tree.

**Implementation:**

- Use outline parsing (parse only package declarations and top-level definitions)
- Build a tree structure: `Package -> Subpackages + SourceFiles`
- Handle both `.scala` and `.java` files
- Skip excluded patterns (e.g., `/experimental/`, test directories)

---

### Phase 4: InteractiveDriver Integration

#### 4.1 Update InteractiveDriver

**Location:** `compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala`

**Purpose:** Enable InteractiveDriver to use sourcepath and trigger pruning.

**Changes:**

- Accept sourcepath parameter in initialization
- Pass sourcepath to compiler settings
- Mark compilation units from sourcepath with `isFromSourcePath = true`
- Enable the PruneSourcePath phase when sourcepath is configured

#### 4.2 Source Loading Logic

**Location:** `compiler/src/dotty/tools/dotc/interactive/Interactive.scala` or similar

**Purpose:** When a symbol is referenced but not found on classpath, look for it in sourcepath.

**Implementation:**

- Hook into symbol resolution
- When a symbol is not found in classfiles, search sourcepath
- Load and compile the source file (which will be pruned automatically)
- Cache loaded sources to avoid reloading

---

### Phase 5: Testing Infrastructure

#### 5.1 Unit Tests for PruneSourcePath Phase

**Location:** `compiler/test/dotty/tools/dotc/transform/PruneSourcePathTests.scala`

**Test cases:**

- Method with explicit return type → body replaced with ???
- Method without return type → kept unchanged (needs inference)
- Abstract method → kept unchanged
- Field with explicit type → body replaced with ???
- Field without type → kept unchanged
- Constructor → kept unchanged
- Macro definition → kept unchanged

#### 5.2 Integration Tests

**Location:** `compiler/test/dotty/tools/dotc/interactive/SourcePathTests.scala`

**Test scenarios:**

- Cross-file reference: File A references class from File B in sourcepath
- Diagnostics: Errors in main file show up correctly, no spurious errors from pruned sources
- Completion: Completions work with symbols from sourcepath
- Hover: Hover shows correct signatures from pruned sources
- Non-matching path: Sources whose package doesn't match directory structure

#### 5.3 Fallback Tests

**Location:** `compiler/test/dotty/tools/dotc/interactive/FallbackSourcePathTests.scala`

**Test scenarios:**

- Dependency resolution: Libraries on sourcepath are found and used
- Scala version matching: Correct Scala version used for sourcepath sources
- Mixed sources: Some files from classpath, some from sourcepath

---

## Critical Files to Modify/Create

### Files to Create

1. `compiler/src/dotty/tools/dotc/transform/metals/PruneSourcePath.scala` - Main pruning phase
2. `compiler/src/dotty/tools/dotc/classpath/LogicalSourcePath.scala` - Package-based source lookup
3. `compiler/src/dotty/tools/dotc/classpath/SourcePathParser.scala` - Parse sourcepath for packages
4. `compiler/test/dotty/tools/dotc/transform/PruneSourcePathTests.scala` - Phase unit tests
5. `compiler/test/dotty/tools/dotc/interactive/SourcePathTests.scala` - Integration tests

### Files to Modify

1. `compiler/src/dotty/tools/dotc/Compiler.scala` - Register the new phase
2. `compiler/src/dotty/tools/dotc/CompilationUnit.scala` - Add `isFromSourcePath` flag
3. `compiler/src/dotty/tools/dotc/config/Settings.scala` - Verify/add sourcepath setting
4. `compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala` - Enable sourcepath support
5. `compiler/src/dotty/tools/dotc/config/PathResolver.scala` - Ensure sourcepath is parsed

---

## Implementation Stages

### Stage 1: Basic Phase Implementation (Minimal Viable Feature)

1. Create PruneSourcePath phase with basic tree transformation
2. Add phase registration in Compiler.scala (behind a flag)
3. Add isFromSourcePath tracking to CompilationUnit
4. Write unit tests for the phase

**Deliverable:** A working phase that can prune method bodies when enabled

### Stage 2: InteractiveDriver Integration

1. Update InteractiveDriver to accept sourcepath parameter
2. Implement source loading from sourcepath
3. Enable phase when sourcepath is configured
4. Write integration tests

**Deliverable:** InteractiveDriver can compile with sourcepath and pruning works

### Stage 3: Logical Package Support (Optional Enhancement)

1. Implement LogicalSourcePath for package-based lookup
2. Implement SourcePathParser for building package tree
3. Integrate with InteractiveDriver
4. Add tests for non-standard directory structures

**Deliverable:** Sources can be found by package name, not just directory path

---

## Verification Plan

### Manual Verification

1. Create a test project with two modules (main + dependency)
2. Add dependency sources to sourcepath
3. Run InteractiveDriver with `-sourcepath` flag and pruning enabled
4. Verify:
   - Main file can reference classes from dependency
   - No type errors for references to pruned sources
   - Completion works for symbols from sourcepath
   - Hover shows correct signatures (without method bodies)
   - Diagnostics are accurate

### Automated Testing

1. Run all unit tests: `sbt "testCompilation dotty/tools/dotc/transform/PruneSourcePathTests"`
2. Run integration tests: `sbt "testCompilation dotty/tools/dotc/interactive/SourcePathTests"`
3. Run with coverage to ensure all code paths are tested
4. Performance testing: Measure compilation time with and without pruning

### Compatibility Testing

1. Verify backward compatibility: Existing code compiles without changes
2. Verify the flag is opt-in: Default behavior unchanged
3. Test with various Scala versions (if applicable)

---

## References

**Scala 3 Compiler Documentation:**

- [Compiler Phases](https://dotty.epfl.ch/docs/contributing/architecture/phases.html)
- [Compiler Overview](https://dotty.epfl.ch/docs/contributing/architecture/lifecycle.html)
- [Compiler Plugins](https://docs.scala-lang.org/scala3/reference/changed-features/compiler-plugins.html)
- [Classpaths](https://dotty.epfl.ch/docs/internals/classpaths.html)

**Scala 3 Compiler Source:**

- [MegaPhase.scala](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/transform/MegaPhase.scala)
- [Compiler.scala](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/Compiler.scala)
- [Trees.scala](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/ast/Trees.scala)
- [InteractiveDriver](https://github.com/scala/scala3/blob/main/language-server/src/dotty/tools/languageserver/DottyLanguageServer.scala)

**Scala 2 Reference Implementation (in Metals):**

- `/Users/tgodzik/Documents/metals/mtags/src/main/scala-2/scala/meta/internal/pc/PruneLateSources.scala`
- `/Users/tgodzik/Documents/metals/mtags/src/main/scala-2/scala/tools/nsc/LogicalSourcePath.scala`
- `/Users/tgodzik/Documents/metals/mtags/src/main/scala-2/scala/meta/internal/pc/classpath/LogicalPackagesProvider.scala`

---

## Key Decisions

### 1. Phase vs. Plugin

**Decision:** Implement as a built-in compiler phase (not a plugin)

**Rationale:**

- Plugins are external and would require users to add plugin dependency
- Built-in phase can be enabled with a simple compiler flag
- Better integration with InteractiveDriver
- Easier to maintain and test within compiler codebase

### 2. MiniPhase vs. Full Phase

**Decision:** Start with `MiniPhase`, upgrade to full `Phase` if needed

**Rationale:**

- MiniPhase is simpler and sufficient for tree transformation
- Can be upgraded later if more control is needed
- Follows Scala 3 best practices for transformations

### 3. When to Prune

**Decision:** Prune during compilation, not at load time

**Rationale:**

- Cleaner separation of concerns
- Easier to test and debug
- Consistent with Scala 2 approach
- Allows compiler optimizations to still work on unpruned trees

### 4. Feature Flag

**Decision:** Use compiler flag `-Yprune-sourcepath` to enable feature

**Rationale:**

- Opt-in ensures backward compatibility
- Y-flag indicates internal/experimental feature
- Can be promoted to standard flag later if successful

---

## Risks and Mitigations

### Risk 1: InteractiveDriver may not support custom phases easily

**Mitigation:**

- Research InteractiveDriver extensibility first (Stage 1)
- If blocked, consider modifying InteractiveDriver itself
- Worst case: implement at a different level (tree modification after loading)

### Risk 2: Performance degradation from parsing entire sourcepath

**Mitigation:**

- Use outline parsing (not full parsing) for package structure
- Cache package tree across compilations
- Make logical package support optional (Stage 3)
- Benchmark and optimize hot paths

### Risk 3: AST transformation complexity in Scala 3

**Mitigation:**

- Study existing transformation phases in Scala 3 as examples
- Start with simple cases (methods with explicit types)
- Incrementally add support for edge cases
- Comprehensive test coverage

### Risk 4: Metals integration may need updates

**Mitigation:**

- Design API to be compatible with how Metals calls InteractiveDriver
- Coordinate with Metals maintainers on integration points
- Ensure flag names and behavior match Metals expectations

---

## Success Criteria

1. ✅ PruneSourcePath phase compiles and runs without errors
2. ✅ Phase correctly identifies methods/fields to prune vs. keep
3. ✅ InteractiveDriver can load and compile sources from sourcepath
4. ✅ Cross-file references work (file A → file B in sourcepath)
5. ✅ No spurious type errors from pruned sources
6. ✅ All unit and integration tests pass
7. ✅ Performance impact < 10% on standard compilation
8. ✅ Backward compatible (no breaking changes to existing code)
9. ✅ Documentation written for the feature
10. ✅ Successfully integrates with Metals (tested in real IDE scenario)
