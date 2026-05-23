#!/usr/bin/env sh
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: parse-document.sh <path> [--front-pages N] [--random-pages N] [--tail-pages N]" >&2
  exit 2
fi

INPUT=$1
shift || true

if [ ! -f "$INPUT" ]; then
  echo "office-parser: file not found: $INPUT" >&2
  exit 1
fi

TIKA_JAR=${JOBCLAW_TIKA_APP_JAR:-"$HOME/.jobclaw/skills/office-parser/lib/tika-app.jar"}
if [ -f "$TIKA_JAR" ]; then
  exec java -jar "$TIKA_JAR" --text "$INPUT"
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$SCRIPT_DIR/parse_document.py" "$INPUT" "$@"
