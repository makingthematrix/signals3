# AGENTS.md

## Project Overview
This is a Scala project implementing a lightweight event streaming library.

## Repository Structure
- `src/main/scala/io/github/makingthematrix/signals3/`: Core implementation
- `src/main/scala/io/github/makingthematrix/signals3/actors`: The implementation of the Actor model
- `src/test/scala/io/github/makingthematrix/signals3/`: Unit tests

## Development Workflow
1. Clone the repository: `git clone https://github.com/makingthematrix/signals3.git`.
2. Run tests always with `sbt testOnly` followed by a list of test classes that are directly related to the feature you work on.
3. Run the project: `sbt run`.

## Development Scripts

The `scripts/` folder contains small bash helpers for the everyday build loop around sbt.
They normalize sbt's output (ANSI colors, CR), manage their own timeouts, and always report
errors and warnings together.

- `scripts/check` - compile the main and the test source trees and print a compact summary
  (pass/fail, error and warning counts, the per-file error table). `--clean` cleans first -
  incremental compiles re-report nothing, so before declaring a phase done use `--clean` to
  get the truthful warning count. `--dump FILE` saves the full output for diffing.
- `scripts/spec [--seq] <Suites...>` - run `sbt testOnly` on the given suites and print the per-suite
  result lines, the failing tests with assertion locations, and the final verdict. Do not
  pass munit output-mode flags on top of it - they make every suite report 0 tests.
  `--seq` runs the suites sequentially instead of in parallel - see "Testing Notes" below for
  when that matters.
- `scripts/mk-smoke <Name> [main|actors|priv]` - scaffold a temporary smoke-test main object
  into the main tree (the flavors differ in the package and the imports).
- `scripts/smoke [-k] <Name>` - compile and run a smoke created with `mk-smoke`, print the
  `PASS`/`FAIL`/`SMOKE` lines, and then delete the smoke file - even if the run failed
  (add `-k` to keep it). This is the way to verify changes when the test suite can't run,
  e.g. in the middle of a refactoring.
- `scripts/probe [-p <package>] [-f <file>]` - a one-shot compile probe: takes a Scala snippet
  from stdin (or a file), drops it into the main compile scope, reports the diagnostics, and
  cleans up. Use it to check how a snippet compiles without touching the sources.
- `scripts/git-guard [--i-know] <git args...>` - a safety gate around the git commands which
  can discard uncommitted work or act on someone else's stash (`stash pop/apply/clear/drop`,
  `reset --hard`, `clean -f`, `checkout/restore --`). For those, it first prints the stash
  list and the dirty-file count and refuses to run without `--i-know`. Everything else passes
  straight to git.
- `scripts/self-test [case...]` - the regression harness for the other scripts. After changing
  anything in `scripts/`, run it to re-verify the toolbox: the exit codes, the output markers,
  and the leftover state in the working tree (it creates and deletes files under `src/`, so it
  refuses to start over a dirty `src/` unless `--force` is given). Costs a few sbt invocations.
  Individual cases can be run: `scripts/self-test check-ok probe-neg`.

## Testing Notes

These are behaviors of the test infrastructure which have cost real debugging time before:

- sbt runs test suites in parallel threads of one JVM, and the `Behaviors` registry is global
  to that JVM. Test suites therefore share one registry: ids are globally unique, a `register*`
  for a taken id returns `false` without overwriting the original, and nothing unregisters
  automatically. In tests:
  - Assert on the `register*` result (`assert(Behaviors.registerF(...))`, or the `expectSuccessCF` /
    `expectFailureCF` helpers). Helpers that swallow results (`awaitCF`, `tryResult` discarded)
    hide registration failures.
  - Prefer ids unique to a test (e.g. `s"spec-<topic>"` with the test's subject in them) over
    reusing fixed ids like `"default"` across unrelated tests - a re-registration silently
    keeps the FIRST function.
  - Don't call `Behaviors.reset()` (or any registry-wide reset) in `afterEach` of one suite:
    while that suite runs in parallel with another, the reset wipes the registry of the whole
  JVM mid-flight for the other suite. The signature symptom is a mid-test `InvalidIdException`
  for an id the test just registered. To tell a real failure from a cross-suite interaction,
  run the suite alone (`scripts/spec <Suite>`), or several suites with `scripts/spec --seq`.
- `waitFor` and `awaitCF` in the test `Utils` return their result instead of asserting:
  wrap `waitFor` in `assert(...)`, or the test passes even when the expectation never arrives.
- Some tests use the persistent global registry deliberately; their order within a suite can
  matter. If removing or moving a test changes which tests fail in a suite, suspect registry
  pollution first before suspecting the production code.

## Coding Standards
- Use Scala 3 with braces syntax.
- Use standard Scala library and collections if possible.
- Try to re-use existing code and avoid duplicating functionality.

## AI-Specific Guidelines
- Read Scaladoc comments attached to every class and method.
- Always run `sbt test` before committing.
- Do not modify files in `target/`.
- Prefer functional programming patterns.
- Avoid modifying the `build.sbt` file unless necessary.
- Use `sbt` for all build tasks.
- Some unit tests deal with concurrency and can be flaky. If a unit test that is not directly related to the feature you work on fails, rerun it (but only once).

## Contacts
- Maintainer: [@makingthematrix](https://github.com/makingthematrix)