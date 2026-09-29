#!/usr/bin/env bash
# Runs the CI checks that fail most often, locally, before a push:
#   1. spotless:check (JDK 17; the formatter does not run on JDK 25)
#   2. full test suite on JDK 25 and JDK 21 (includes NativeImageJniRegistrationTest, which
#      catches JNI renames the GraalVM native-image jobs would otherwise fail on)
#   3. optional GraalVM native-image test when GRAALVM_HOME is set (--native)
#
# JDK locations: JAVA17_HOME, JAVA21_HOME, JAVA25_HOME, defaulting to SDKMAN candidates.
set -euo pipefail

cd "$(dirname "$0")/.."

sdk_java() {
    local version=$1
    local dir
    dir=$(ls -d "$HOME/.sdkman/candidates/java/$version".* 2>/dev/null | sort -V | tail -1 || true)
    echo "$dir"
}

JAVA17_HOME=${JAVA17_HOME:-$(sdk_java 17)}
JAVA21_HOME=${JAVA21_HOME:-$(sdk_java 21)}
JAVA25_HOME=${JAVA25_HOME:-$(sdk_java 25)}
RUN_NATIVE=false
[[ "${1:-}" == "--native" ]] && RUN_NATIVE=true

for name in JAVA17_HOME JAVA21_HOME JAVA25_HOME; do
    if [[ -z "${!name}" || ! -x "${!name}/bin/java" ]]; then
        echo "missing $name" >&2
        exit 2
    fi
done

step() { printf '\n=== %s\n' "$*"; }

step "spotless:check (JDK 17)"
JAVA_HOME=$JAVA17_HOME mvn -q -o spotless:check

step "test (JDK 25)"
JAVA_HOME=$JAVA25_HOME mvn -q -o test

step "test (JDK 21)"
JAVA_HOME=$JAVA21_HOME mvn -q -o test

if $RUN_NATIVE; then
    if [[ -z "${GRAALVM_HOME:-}" ]]; then
        echo "--native needs GRAALVM_HOME" >&2
        exit 2
    fi
    step "GraalVM native-image test"
    JAVA_HOME=$GRAALVM_HOME mvn -q -P native integration-test
fi

step "PASS"
