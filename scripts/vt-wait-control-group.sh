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
   "${WORKDIR}/current-backup" backup-progress "${WORKDIR}/current-backup"
 run_scenario "vt-begin" "${REPO_DIR}/target/test-classes:${REPO_DIR}/target/classes" \
   "${WORKDIR}/current-begin" begin-progress "${WORKDIR}/current-begin"
 run_scenario "vt-commit" "${REPO_DIR}/target/test-classes:${REPO_DIR}/target/classes" \
   "${WORKDIR}/current-commit" commit-progress "${WORKDIR}/current-commit"

 # --- parent commit native ---------------------------------------------------
 ( cd "${WORKTREE}" && mvn -q -DskipTests compile > /dev/null 2>&1 )
 ( cd "${WORKTREE}" && make native > /dev/null 2>&1 )
 CTRL_CLASSES="${WORKTREE}/control-classes"
 mkdir -p "${CTRL_CLASSES}" "${WORKTREE}/org/sqlite/core"
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
   "${WORKDIR}/parent-backup" backup-progress "${WORKDIR}/parent-backup" --marker=call-start
 run_scenario "parent-begin" "${CTRL_CLASSES}:${WORKTREE}/target/classes" \
   "${WORKDIR}/parent-begin" begin-progress "${WORKDIR}/parent-begin" --marker=call-start
 run_scenario "parent-commit" "${CTRL_CLASSES}:${WORKTREE}/target/classes" \
   "${WORKDIR}/parent-commit" commit-progress "${WORKDIR}/parent-commit" --marker=call-start

 assert_yielded() {
   local label="$1"
   if [ "$(cat "${EVIDENCE}/${label}-exit.txt")" = "0" ] \
       && grep -q "B-DONE targetStillWaiting=true" "${EVIDENCE}/${label}-stdout.txt"; then
     echo "PASS: ${label} yielded its carrier during Java wait"
   else
     echo "FAIL: ${label} did not prove carrier progress" >&2
     exit 1
   fi
 }

 assert_native_blocked() {
   local label="$1"
   if [ "$(cat "${EVIDENCE}/${label}-exit.txt")" = "0" ] \
       && grep -q "B-DONE targetStillWaiting=false" "${EVIDENCE}/${label}-stdout.txt"; then
     echo "PASS: ${label} only allowed progress after the native wait (expected contrast)"
   else
     echo "FAIL: ${label} did not reproduce native carrier blocking" >&2
     exit 1
   fi
 }

 assert_yielded "vt-java-wait"
 assert_yielded "vt-begin"
 assert_yielded "vt-commit"
 assert_native_blocked "parent-native"
 assert_native_blocked "parent-begin"
 assert_native_blocked "parent-commit"
