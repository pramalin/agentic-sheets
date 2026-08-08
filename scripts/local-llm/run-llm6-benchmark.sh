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

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

WORKSHEET="${WORKSHEET:-Holdings}"
MODEL_ID="${MODEL_ID:-Holdings}"
CLIENT_ID="${CLIENT_ID:-jpmc}"
RESULTS_DIR="${RESULTS_DIR:-build/local-llm-results}"

mkdir -p "$RESULTS_DIR"
TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
HOST_NAME="$(hostname | tr -cs '[:alnum:]_.-' '-')"

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
    echo

    return "$status"
}

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
echo "failure to record as one."
