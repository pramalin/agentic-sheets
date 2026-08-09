#!/usr/bin/env bash
# Local LLM phase, Step LLM-6 (see docs/local-llm-enhancements.md) --
# the re-scoped benchmark. Steps LLM-1 through LLM-4 moved currency and
# asset_class resolution out of the LLM's hands entirely and into
# deterministic code; this asks the question that motivated all of it:
# once the model is only resolving genuine ambiguity, not re-deriving
# facts the application already knows, is a smaller model sufficient?
#
# Runs the SAME model (default: qwen2.5:3B-Q4_K_M, matching
# compose.local-llm.yaml's own default -- see that file if you want a
# different model or size) against two fixtures in one pass:
#
#   1. The original holdings_jpmc_20260115.xlsx -- re-establishes the
#      current baseline under today's code, since the older recorded
#      result (docs/local-llm-evaluation.md, and this scripts
#      directory's own README, which still says a Qwen 2.5 3B proposal
#      is expected to be structurally rejected) predates Steps LLM-1
#      through LLM-4 and is very likely stale now.
#
#   2. holdings_jpmc_llm6_unfamiliar_column.xlsx -- byte-identical to
#      the original except for exactly one header cell: "Price" renamed
#      to "Valuation Px", a column name JPMC's real fixture, jpmc.yaml's
#      fieldAliases, and (as far as this benchmark is concerned) the
#      model have never seen. Every other column -- including Currency
#      and Asset Class, the two fields this whole phase was built
#      around -- is untouched. This isolates the question Step LLM-6
#      exists to ask: with known facts handled deterministically, can a
#      3B model correctly resolve ONE genuinely unfamiliar column on its
#      own.
#
# Requires the same stack as run-holdings-proposal.sh -- see this
# directory's README for setup. Does not itself start or verify the
# stack; run verify-local-llm.sh first if you haven't already.
#
# Following an external review after this step's first real run: this
# script now WARNS (loudly, but never acts) if a run completes
# suspiciously fast for real CPU inference -- exactly what happened the
# first time this was run, when MappingController's own fast path
# reused an already-persisted batch/proposal for a file whose content
# hash it had already seen, completing in 0.10s instead of several
# minutes. Deliberately does NOT auto-clear the Postgres volume or take
# any destructive action on your behalf -- that's a real, separate
# decision for you to make deliberately, not something a benchmark
# script should do silently. See this file's own CACHE_HIT_THRESHOLD_SECONDS
# below, and docs/local-llm-enhancements.md's "Clearing state between
# benchmark runs" section for the actual clear-and-rerun commands.

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

WORKSHEET="${WORKSHEET:-Holdings}"
MODEL_ID="${MODEL_ID:-Holdings}"
CLIENT_ID="${CLIENT_ID:-jpmc}"
RESULTS_DIR="${RESULTS_DIR:-build/local-llm-results}"
# Real CPU inference for this fixture has always taken well over a
# minute (observed: 1:38-3:14 across every genuine model call so far);
# a cache hit completed in 0:00.10. 10 seconds is generously far below
# any real inference time and generously far above any cache hit --
# override via env var if your hardware is meaningfully different.
CACHE_HIT_THRESHOLD_SECONDS="${CACHE_HIT_THRESHOLD_SECONDS:-10}"

mkdir -p "$RESULTS_DIR"
TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
HOST_NAME="$(hostname | tr -cs '[:alnum:]_.-' '-')"

# Parses GNU time's %E elapsed format (M:SS.ss, or H:MM:SS.ss for
# anything over an hour) into whole seconds, truncated -- good enough
# for a threshold check, avoids depending on `bc` (not guaranteed
# installed) for a float comparison bash can't do natively.
parse_elapsed_seconds() {
    awk -F: -v t="$1" 'BEGIN {
        n = split(t, a, ":");
        if (n == 3) { s = a[1]*3600 + a[2]*60 + a[3]; }
        else if (n == 2) { s = a[1]*60 + a[2]; }
        else { s = a[1]; }
        printf "%d\n", s;
    }'
}

run_one() {
    local label="$1"
    local file="$2"
    local out_prefix="$RESULTS_DIR/${TIMESTAMP}-${HOST_NAME}-llm6-${label}"

    echo "=============================================================="
    echo "Step LLM-6 -- ${label}"
    echo "File: ${file}"
    echo "=============================================================="

    set +e
    FILE="$file" WORKSHEET="$WORKSHEET" MODEL_ID="$MODEL_ID" CLIENT_ID="$CLIENT_ID" \
        RESULTS_DIR="$RESULTS_DIR" \
        ./scripts/local-llm/run-holdings-proposal.sh \
        > "${out_prefix}-console.txt" 2>&1
    local status=$?
    set -e

    cat "${out_prefix}-console.txt"
    echo
    echo "Full console output also saved to: ${out_prefix}-console.txt"

    local elapsed
    elapsed="$(grep -m1 '^Elapsed: ' "${out_prefix}-console.txt" 2>/dev/null | sed 's/^Elapsed: //')"
    if [[ -n "$elapsed" ]]; then
        local elapsed_seconds
        elapsed_seconds="$(parse_elapsed_seconds "$elapsed")"
        if (( elapsed_seconds < CACHE_HIT_THRESHOLD_SECONDS )); then
            echo
            echo "!! WARNING: ${label} completed in ${elapsed} (~${elapsed_seconds}s) -- suspiciously"
            echo "!! fast for real CPU inference. This almost certainly means MappingController's"
            echo "!! own fast path reused an already-persisted batch/proposal for this exact file"
            echo "!! content, NOT a fresh model call. This result is NOT a valid benchmark data"
            echo "!! point -- see docs/local-llm-enhancements.md's 'Clearing state between"
            echo "!! benchmark runs' section. This script does not clear anything automatically;"
            echo "!! if you need a genuinely fresh model call, clear it yourself:"
            echo "!!   docker compose -f compose.yaml -f compose.local-llm.yaml down -v"
            echo "!!   docker compose -f compose.yaml -f compose.local-llm.yaml up -d --build --wait"
            # Following an external review: a warning is clear to a
            # human reading the console, but leaves the script's own
            # exit status unchanged, meaning benchmark automation could
            # still record a cache-hit run as an ordinary pass/fail.
            # This global flag, checked after both runs, forces the
            # script's own exit code to a distinct, impossible-to-miss
            # value in that case -- see the bottom of this script.
            CACHE_HIT_DETECTED=1
        fi
    fi
    echo

    return "$status"
}

CACHE_HIT_DETECTED=0

baseline_status=0
run_one "baseline" "holdings_jpmc_20260115.xlsx" || baseline_status=$?

unfamiliar_status=0
run_one "unfamiliar-column" "holdings_jpmc_llm6_unfamiliar_column.xlsx" || unfamiliar_status=$?

echo "=============================================================="
echo "Step LLM-6 summary"
echo "=============================================================="
echo "Baseline (holdings_jpmc_20260115.xlsx):            curl exit $baseline_status"
echo "Unfamiliar column (Price -> Valuation Px):          curl exit $unfamiliar_status"
echo
echo "curl exit 0 means the /propose call succeeded AND the proposal"
echo "passed structural validation + the Local LLM phase's deterministic"
echo "resolver -- not that the mapping is necessarily correct. Read both"
echo "response JSON files under $RESULTS_DIR and check, in particular,"
echo "whether 'Valuation Px' -> market_price was actually proposed at"
echo "all, or left in unmappedSourceColumns -- a model correctly"
echo "declining to guess is a legitimate, useful outcome here, not a"
echo "failure to record as one. If either run above printed a cache-hit"
echo "WARNING, treat that result as invalid regardless of its curl exit"
echo "status -- it didn't test the model at all."

if [[ "$CACHE_HIT_DETECTED" -eq 1 ]]; then
    echo
    echo "!! At least one run above was a cache hit, not a real model call --"
    echo "!! exiting with status 3 (distinct from any curl exit code) so"
    echo "!! automation can't mistake this run for a valid result."
    exit 3
fi
