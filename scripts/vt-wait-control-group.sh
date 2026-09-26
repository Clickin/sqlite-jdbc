#!/bin/bash
# Control group for vt-java-wait carrier-progress claims (plan Phase 2, V01/V02).
#
# Runs the SAME single-carrier child-JVM scenario (VtWaitScenarioMain) against the
# PARENT commit's native library (3850773, in-native sqlite3_sleep copy loop) and
# against the current build, and records both outputs.
#
# Expected on JDK 24+ (JEP 491):
#   - current build: child exits 0, B-DONE targetStillWaiting=true
#   - parent commit: child exits 1, "independent virtual thread made no progress"
#
# The parent run uses --marker=call-start (test-side, non-blocking): the parent's
# backup is one blocking native call, so the statement right before the call marks
# the busy-wait window. No production code is patched for the control group.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PARENT_SHA="3850773aed93aa657b8025d44be208a98ea761f3"
WORKTREE="${REPO_DIR}/../sqlite-jdbc-parent-wt"
EVIDENCE="${REPO_DIR}/target/vt-wait-evidence/control-group"
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

mkdir -p "${EVIDENCE}"
if [ ! -d "${WORKTREE}" ]; then
  git -C "${REPO_DIR}" worktree add "${WORKTREE}" "${PARENT_SHA}"
fi

run_scenario() { # $1 label, $2 classpath, $3 workdir, extra args...
  local label="$1" classpath="$2" workdir="$3"; shift 3
  mkdir -p "${workdir}"
  echo "== ${label} =="
  set +e
  "${JAVA_BIN}" \
    -Djdk.virtualThreadScheduler.parallelism=1 \
    -Djdk.virtualThreadScheduler.maxPoolSize=1 \
    -cp "${classpath}" org.sqlite.vt.VtWaitScenarioMain "$@" > "${EVIDENCE}/${label}-stdout.txt" 2> "${EVIDENCE}/${label}-stderr.txt"
  local rc=$?
  set -e
  echo "${rc}" > "${EVIDENCE}/${label}-exit.txt"
  cat "${EVIDENCE}/${label}-stdout.txt"
  echo "exit=${rc}"
  echo
}

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

# --- current build ----------------------------------------------------------
( cd "${REPO_DIR}" && mvn -q -DskipTests compile test-compile > /dev/null 2>&1 )
run_scenario "vt-java-wait" "${REPO_DIR}/target/test-classes:${REPO_DIR}/target/classes" \
  "${WORKDIR}/current" backup-progress "${WORKDIR}/current"

# --- parent commit native ---------------------------------------------------
( cd "${WORKTREE}" && mvn -q -DskipTests compile > /dev/null 2>&1 )
( cd "${WORKTREE}" && make native > /dev/null 2>&1 )
CTRL_CLASSES="${WORKTREE}/control-classes"
mkdir -p "${CTRL_CLASSES}" "${WORKTREE}/org/sqlite/core"
# The probe reads a counter that only exists on the vt-java-wait branch; the
# control run uses the call-start marker and never calls into it.
cat > "${WORKTREE}/org/sqlite/core/VtWaitProbe.java" <<'EOF'
package org.sqlite.core;
public class VtWaitProbe {
    public static long javaWaitObservations() { return 0; }
}
EOF
"${JAVA_BIN}" -version 2> "${EVIDENCE}/java-version.txt" || true
javac -encoding UTF-8 -cp "${WORKTREE}/target/classes" \
  -d "${CTRL_CLASSES}" \
  "${REPO_DIR}/src/test/java/org/sqlite/vt/VtWaitScenarioMain.java" \
  "${WORKTREE}/org/sqlite/core/VtWaitProbe.java" \
  > "${EVIDENCE}/control-compile.log" 2>&1

run_scenario "parent-native" "${CTRL_CLASSES}:${WORKTREE}/target/classes" \
  "${WORKDIR}/parent" backup-progress "${WORKDIR}/parent" --marker=call-start

# --- verdict ----------------------------------------------------------------
if grep -q "B-DONE targetStillWaiting=true" "${EVIDENCE}/vt-java-wait-stdout.txt" \
    && [ "$(cat "${EVIDENCE}/vt-java-wait-exit.txt")" = "0" ]; then
  echo "PASS: current build yields the carrier during the Java wait"
else
  echo "FAIL: current build did not prove carrier progress" >&2
  exit 1
fi
# Parent native loop pins the carrier: the independent VT can only run after the
# target exhausted its busy budget (targetStillWaiting=false), never during it.
if [ "$(cat "${EVIDENCE}/parent-native-exit.txt")" = "0" ] \
    && grep -q "B-DONE targetStillWaiting=false" "${EVIDENCE}/parent-native-stdout.txt"; then
  echo "PASS: parent native loop only allows progress after the busy budget (expected contrast)"
else
  echo "FAIL: parent control did not reproduce the pinned-carrier behavior" >&2
  exit 1
fi
