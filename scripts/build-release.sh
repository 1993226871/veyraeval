#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash ./mvnw --batch-mode --no-transfer-progress verify
(
  cd web
  npm ci
  npm test -- --run
  npm run build
)
python -m pip wheel --no-deps ./sdks/python --wheel-dir .tools/release
echo 'Source-built Java jars, Python wheel and web assets are ready. No public package was published.'
