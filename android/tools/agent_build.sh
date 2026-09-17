#!/usr/bin/env bash
#
# Build the Branded Launcher APK from an agent shell, where a plain `./gradlew` cannot run.
#
#     tools/agent_build.sh                    # :app:assembleDebug
#     tools/agent_build.sh :app:assembleRelease
#
# Not needed in an ordinary terminal. From an agent shell Gradle dies with "Unable to establish
# loopback connection": Pipe.open() makes an AF_UNIX socket under %LOCALAPPDATA%\Temp, where
# connect() returns EINVAL. Moving TEMP off the drive root fixes it for every JVM in the tree.
set -euo pipefail

BUILD_TMP="${NOLEE_BUILD_TMP:-C:\\tmp-gradle}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$(cygpath -u "$BUILD_TMP" 2>/dev/null || echo /c/tmp-gradle)"
export TEMP="$BUILD_TMP"
export TMP="$BUILD_TMP"

cd "$HERE"
exec ./gradlew "${@:-:app:assembleDebug}" --console=plain
