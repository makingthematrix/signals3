#!/usr/bin/env bash
# Shared helpers for the development scripts in this folder.
# Source this file from the other scripts - do not run it directly.
#
# The helpers exist because sbt's raw output is hard to process reliably:
# it paints with ANSI escape sequences, emits CR, and mixes progress lines
# with real diagnostics. The utilities below normalize all of that.

# The root of the repository - every script resolves paths against it.
# BASH_SOURCE[1] is the sourcing script, which lives in scripts/.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[1]}")/.." && pwd)"

# Runs sbt (batch mode) with a safe timeout, strips the ANSI colors and the CR
# characters, and never fails the pipeline on sbt's exit status alone -
# the callers judge the result by the diagnostics in the output, not by the
# exit code. Use grep -a on the results: sbt output can look binary to grep.
sbt_out() {
  timeout 280 sbt -batch "$@" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tr '\r' '\n'
  return 0
}

# Extracts the compile diagnostics ("-- [Exxx] ... Error/Warning: <file>:<line> <col>")
# from normalized sbt output and counts them per file, most errors first.
# Usage: diagnostics_table <kind> <<< "$output"   where kind is "Error" or "Warning"
diagnostics_table() {
  local kind="$1"
  grep -aoE "\-\- \[E[0-9]+\] [A-Za-z ]+ ${kind}: [^ ]+" \
    | awk '{print $NF}' | awk -F: '{print $1}' | sort | uniq -c | sort -rn
}
