#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

README_FRAGMENT="$SCRIPT_DIR/fragments/readme-section.md"
GITIGNORE_FRAGMENT="$SCRIPT_DIR/fragments/gitignore-entries.txt"
README_MARKER='## Local LLM evaluation'

if [[ ! -f README.md ]]; then
  echo "ERROR: README.md not found at repository root" >&2
  exit 1
fi

if ! grep -Fq "$README_MARKER" README.md; then
  python3 - "$README_FRAGMENT" <<'PY'
from pathlib import Path
import sys

readme = Path("README.md")
fragment = Path(sys.argv[1]).read_text().rstrip() + "\n\n"
text = readme.read_text()
marker = "## Roadmap"

if marker in text:
    text = text.replace(marker, fragment + marker, 1)
else:
    text = text.rstrip() + "\n\n" + fragment

readme.write_text(text)
PY
  echo "Added the Local LLM section to README.md"
else
  echo "README.md already contains the Local LLM section"
fi

touch .gitignore
while IFS= read -r entry || [[ -n "$entry" ]]; do
  [[ -z "$entry" ]] && continue
  if [[ "$entry" == \#* ]]; then
    continue
  fi
  if ! grep -Fxq "$entry" .gitignore; then
    printf '%s\n' "$entry" >>.gitignore
  fi
done <"$GITIGNORE_FRAGMENT"

echo "Ensured local-LLM output paths are ignored"
echo
echo "Review changes with:"
echo "  git diff -- README.md .gitignore"
