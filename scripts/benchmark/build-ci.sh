#!/usr/bin/env bash
# Build only; run-gateway.py owns the sequential measurements.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
ci="$root/target/ci"
out="$root/target/vt-wait-evidence/ci-benchmark"
upstream="$ci/upstream"
before="$ci/before"
gateway="$ci/gateway"
fixtures="$root/scripts/benchmark/fixtures"
upstream_revision=cab7981c19ce04d691f0675f0b2586afc2bbf803

# Never delete a prior build/run to make a rerun appear fresh.
for path in "$out" "$before" "$gateway" "$root/target/classes" "$upstream/target"; do
    if [[ -e "$path" ]]; then
        printf 'Refusing to reuse existing build/output: %s\n' "$path" >&2
        exit 1
    fi
done
mkdir -p "$out" "$before" "$gateway"
exec > >(tee "$out/build.log") 2>&1
set -x
export CC=gcc-13 CXX=g++-13 LC_ALL=C.UTF-8 TZ=UTC
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]]
[[ "$(git -C "$upstream" rev-parse HEAD)" == "$upstream_revision" ]]
[[ -f "$fixtures/gateway-source.zip" && -f "$fixtures/pre-optimization.patch" ]]

# Archive tracked HEAD, not a recursive copy containing target/ or checkout credentials.
git archive HEAD | tar -x -C "$before"
(cd "$before" && git init --quiet && git apply -p1 "$fixtures/pre-optimization.patch")
unzip -q "$fixtures/gateway-source.zip" -d "$gateway"
chmod +x "$gateway/gradlew"
python3 - "$root" "$before" "$gateway" "$fixtures" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

root, before, gateway, fixtures = map(Path, sys.argv[1:])
manifest = json.loads((fixtures / 'manifest.json').read_text())
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert digest(fixtures / 'gateway-source.zip') == manifest['gateway_source_zip_sha256']
assert digest(fixtures / 'pre-optimization.patch') == manifest['pre_optimization_patch_sha256']
assert manifest['upstream_release_sha'] == 'cab7981c19ce04d691f0675f0b2586afc2bbf803'
for tree, key in [(root, 'fork_current_sources'), (before, 'fork_before_sources'), (gateway, 'gateway_source_files')]:
    for name, expected in manifest[key].items():
        assert digest(tree / name) == expected, f'Source mismatch: {tree / name}'
PY

{
    uname -a
    cat /etc/os-release
    lscpu
    cat /proc/meminfo
    df -hT "$root"
    findmnt -T "$root"
    gcc-13 --version
    gcc-13 -v
    "$JAVA_HOME/bin/java" -version
    "$JAVA_HOME/bin/javac" -version
    mvn --version
    make --version
    dpkg-query -W gcc-13 g++-13 binutils tcl-dev maven
} > "$out/host-toolchain.log" 2>&1

run_logged() {
    local log=$1
    shift
    { printf '%q ' "$@"; printf '\n'; "$@"; } 2>&1 | tee "$out/$log.log"
}

# Invoke the same plugin directly: upstream's lifecycle pins an older plugin.
# Only main Java 8 classes are needed on the classpath (no Java 9 module descriptor).
java_build=(mvn --batch-mode --no-transfer-progress -DskipTests
    -Dmaven.compiler.release=8 -Dmaven.compiler.fork=true
    "-Dmaven.compiler.executable=$JAVA_HOME/bin/javac" -Dmaven.compiler.verbose=true
    org.apache.maven.plugins:maven-resources-plugin:3.3.1:resources
    org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile)
native_path=src/main/resources/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so
source "$upstream/VERSION"
[[ "$version" == 3.53.4 ]]
shared_out="$upstream/target/sqlite-$version-Linux-x86_64"
shared_source="$upstream/target/sqlite-amalgamation-$(sh "$upstream/amalgamation_version.sh" "$version")"
shared_obj="$shared_out/sqlite3.o"
make_build=(make -j1 SHELL=/bin/bash '.SHELLFLAGS=-eu -o pipefail -c'
    OS_NAME=Linux OS_ARCH=x86_64 CC=gcc-13
    "JAVAC=$JAVA_HOME/bin/javac --release 8"
    "JAVA_CLASSPATH=$HOME/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
    OSINFO_PROG=target/classes/org/sqlite/util/OSInfo.class)

for label in xerial before fork; do
    case "$label" in
        xerial) tree="$upstream" ;;
        before) tree="$before" ;;
        fork) tree="$root" ;;
    esac
    # Committed native libraries and old objects must never satisfy make by timestamp.
    for path in "$tree/target/common-lib" "$tree/target/sqlite-$version-Linux-x86_64"; do
        [[ ! -e "$path" ]] || { printf 'Stale native output: %s\n' "$path"; exit 1; }
    done
    rm -f "$tree/$native_path"
    (
        cd "$tree"
        run_logged "$label-java-build" "${java_build[@]}"
        if [[ "$label" == xerial ]]; then
            run_logged "$label-native-build" "${make_build[@]}" native
            sha256sum "$shared_obj" > "$out/shared-sqlite-object.sha256"
        else
            run_logged "$label-native-build" "${make_build[@]}" native \
                -o "$shared_obj" -o "$shared_source/sqlite3.h" \
                "SQLITE_OBJ=$shared_obj" "SQLITE_SOURCE=$shared_source" \
                "SQLITE_HEADER=$shared_source/sqlite3.h"
            sha256sum --check "$out/shared-sqlite-object.sha256"
            [[ ! -e "target/sqlite-$version-Linux-x86_64/sqlite3.o" ]]
        fi
        cmp "$native_path" target/classes/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so
    )
done

# Build the unchanged production application and its existing test fixtures, not tests.
cat > "$out/classpath.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    def backend = rootProject.project(':backend')
    backend.tasks.register('benchmarkClasspath') {
        dependsOn backend.tasks.named('testClasses')
        doLast { new File(System.getProperty('benchmark.classpath.output')).text = backend.sourceSets.test.runtimeClasspath.asPath }
    }
}
GRADLE
(
    cd "$gateway"
    run_logged gateway-build ./gradlew --no-daemon --max-workers=2 --console=plain \
        -I "$out/classpath.gradle" "-Dbenchmark.classpath.output=$out/gateway-classpath.txt" \
        :backend:benchmarkClasspath
    run_logged gateway-stop ./gradlew --stop
)

# Capture hashes of source inputs and outputs, plus actual toolchain/runner identity.
python3 - "$root" "$out" "$shared_obj" "$shared_source" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root, out, sqlite_obj, sqlite_source = map(Path, sys.argv[1:])
ci = root / 'target/ci'

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def capture(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()

identity = {
    'fork_revision': capture('git', '-C', str(root), 'rev-parse', 'HEAD'),
    'upstream_revision': capture('git', '-C', str(ci / 'upstream'), 'rev-parse', 'HEAD'),
    'jdk': capture(str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-version'),
    'javac': capture(str(Path(os.environ['JAVA_HOME']) / 'bin/javac'), '-version'),
    'gcc': capture('gcc-13', '-v'),
    'maven': capture('mvn', '--version'),
    'compiler_plugin': 'org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile',
    'java_release': 8,
    'native_flags': 'Unmodified Linux-x86_64 Makefile flags; full commands in *-native-build.log',
    'sqlite_object': {'path': str(sqlite_obj), 'sha256': digest(sqlite_obj)},
    'sqlite_source_sha256': digest(sqlite_source / 'sqlite3.c'),
    'sqlite_header_sha256': digest(sqlite_source / 'sqlite3.h'),
    'runner': {key: os.environ.get(key) for key in (
        'GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT',
        'RUNNER_NAME', 'RUNNER_OS', 'RUNNER_ARCH', 'ImageOS', 'ImageVersion')},
    'fixtures': {p.name: digest(p) for p in sorted((root / 'scripts/benchmark/fixtures').iterdir()) if p.is_file()},
    'benchmark_sources': {p.name: digest(p) for p in (root / 'scripts/benchmark').iterdir() if p.is_file()},
    'variants': {},
}
for label, tree in [('xerial', ci / 'upstream'), ('before', ci / 'before'), ('fork', root)]:
    sources = sorted({p for pattern in ('src/main/java/**/*', 'src/main/ext/**/*',
        'src/main/resources/sqlite-jdbc.properties', 'lib/inc_linux/*', 'Makefile', 'Makefile.common', 'VERSION', 'pom.xml')
        for p in tree.glob(pattern) if p.is_file()})
    manifest = {str(p.relative_to(tree)): digest(p) for p in sources}
    manifest_path = out / f'{label}-source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    library = tree / 'target/classes/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so'
    objects = tree / 'target/sqlite-3.53.4-Linux-x86_64'
    identity['variants'][label] = {
        'source_manifest_sha256': digest(manifest_path),
        'library_sha256': digest(library),
        'jni_object_sha256': digest(objects / 'NativeDB.o'),
        'jni_header_sha256': digest(tree / 'target/common-lib/NativeDB.h'),
        'sqlite_object_sha256': digest(sqlite_obj),
    }
    # A classpath run must not quietly pick up a prebuilt or differently-targeted driver.
    jdbc = (tree / 'target/classes/org/sqlite/JDBC.class').read_bytes()
    assert jdbc[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(jdbc[6:8], 'big') == 52, label
java = identity['jdk']
assert '25.0.2' in java and 'Temurin' in java, java
assert '25.0.2' in identity['javac'], identity['javac']
(out / 'build-identity.json').write_text(json.dumps(identity, indent=2) + '\n')
PY
"$JAVA_HOME/bin/jps" -lv > "$out/pre-measurement-java-processes.log"
if grep -E 'GradleDaemon|KotlinCompileDaemon' "$out/pre-measurement-java-processes.log"; then
    printf 'Build daemon still running; refusing to measure\n' >&2
    exit 1
fi
