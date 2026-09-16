#!/bin/sh
# Scan staged additions; exact public fixtures are owned by secret-scan.mjs.
set -eu
exec node "$(dirname "$0")/secret-scan.mjs"
