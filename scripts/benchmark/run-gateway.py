#!/usr/bin/env python3
"""Paired real gateway HTTP runs; only the sqlite JDBC classpath entry changes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument("--gateway", type=Path, default=ROOT.parent / "gateway")
p.add_argument("--output", type=Path, default=ROOT / "target/vt-wait-evidence/gateway-benchmark")
p.add_argument("--seconds", type=int, default=20)
p.add_argument("--clients", type=int, default=12)
p.add_argument("--warmup-requests", type=int, default=120, help="Exact total successful warmup HTTP requests, at least the client count")
p.add_argument("--carriers", type=int, default=4)
p.add_argument("--repetitions", type=int, default=3)
p.add_argument("--scenarios", type=int, nargs="+", default=[0, 1, 4], help="Concurrent online backups")
p.add_argument("--backup-pages", type=int, default=-1, help="-1 copies a whole snapshot; 128 reproduces the incremental-backup pilot")
p.add_argument("--prepare", action="store_true", help="Compile gateway fixtures and current fork before measurement")
p.add_argument("--before-classes", type=Path, help="Include an immutable pre-optimization fork classes/resources directory")
p.add_argument("--xerial-native-dir", type=Path, help="Use a verified ABI-compatible local upstream native build for xerial")
p.add_argument("--xerial-classes", type=Path, help="Run locally compiled upstream classes/resources instead of the baseline JAR")
p.add_argument("--require-matching-compile-options", action="store_true", help="Require identical SQLite compile options across variants")
p.add_argument("--discard-databases", action="store_true", help="Delete each fresh run's data directory only after successful validation")
a = p.parse_args()
if min(a.seconds, a.clients, a.carriers, a.repetitions) < 1 or min(a.scenarios) < 0:
    p.error("invalid workload size")
if a.warmup_requests < a.clients:
    p.error("--warmup-requests must be >= --clients")
a.output = a.output.resolve(); a.gateway = a.gateway.resolve()
a.output.mkdir(parents=True, exist_ok=True)
java_home = Path(os.environ["JAVA_HOME"])
java, javac = str(java_home / "bin/java"), str(java_home / "bin/javac")
classpath_file = a.output / "gateway-classpath.txt"

def run(cmd, log, cwd=ROOT, timeout=600, check=True):
    with log.open("w") as f:
        completed = subprocess.run(list(map(str, cmd)), cwd=cwd, stdout=f, stderr=subprocess.STDOUT, timeout=timeout)
    if check and completed.returncode:
        raise RuntimeError(f"exit {completed.returncode}: {cmd}; see {log}")
    return completed.returncode

if a.prepare:
    init = a.output / "classpath.gradle"
    init.write_text('''gradle.projectsEvaluated {
    def backend = rootProject.project(':backend')
    backend.tasks.register('benchmarkClasspath') {
        dependsOn backend.tasks.named('testClasses')
        doLast { new File(System.getProperty('benchmark.classpath.output')).text = backend.sourceSets.test.runtimeClasspath.asPath }
    }
}
''')
    run([a.gateway / "gradlew", "--no-daemon", "-I", init, f"-Dbenchmark.classpath.output={classpath_file}", ":backend:benchmarkClasspath"], a.output / "gateway-build.log", a.gateway)
    run(["mvn", "-q", "-DskipTests", "compile"], a.output / "fork-build.log")

entries = classpath_file.read_text().strip().split(os.pathsep)
baselines = [e for e in entries if Path(e).name == "sqlite-jdbc-3.53.4.0.jar"]
if len(baselines) != 1:
    raise RuntimeError(f"Expected one version-matched xerial 3.53.4.0, found {baselines}")
baseline = baselines[0]
common = [e for e in entries if "sqlite-jdbc" not in Path(e).name]
classes = a.output / "classes"; classes.mkdir(exist_ok=True)
run([javac, "-encoding", "UTF-8", "-cp", os.pathsep.join(entries), "-d", classes, ROOT / "scripts/benchmark/GatewayBenchmark.java"], a.output / "compile.log")
logback = a.output / "logback.xml"
logback.write_text('<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')

def capture(cmd, cwd=ROOT):
    return subprocess.check_output(cmd, cwd=cwd, stderr=subprocess.STDOUT, text=True).strip()

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

native_platform = {
    ("Linux", "x86_64"): ("Linux/x86_64", "libsqlitejdbc.so"),
    ("Darwin", "arm64"): ("Mac/aarch64", "libsqlitejdbc.dylib"),
}.get((platform.system(), platform.machine()))
if native_platform is None:
    raise RuntimeError(f"Unsupported native platform: {platform.system()}/{platform.machine()}")
native_folder, native_name = native_platform
native_resource = Path("org/sqlite/native") / native_folder / native_name
native = ROOT / "target/classes" / native_resource
xerial_runtime = str(a.xerial_classes.resolve()) if a.xerial_classes else baseline
gateway_has_git = (a.gateway / ".git").exists()
require_matching_options = a.require_matching_compile_options or bool(a.before_classes and a.xerial_native_dir)
metadata = {
    "arguments": {k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items()},
    "java": capture([java, "-version"]), "host": capture(["uname", "-a"]),
    "fork_head": capture(["git", "rev-parse", "HEAD"]),
    "gateway_head": capture(["git", "rev-parse", "HEAD"], a.gateway) if gateway_has_git else None,
    "gateway_status": capture(["git", "status", "--short"], a.gateway) if gateway_has_git else None,
    "gateway_identity": "git-and-source-manifest" if gateway_has_git else "source-manifest",
    "xerial_jar": baseline, "xerial_sha256": digest(baseline), "fork_native_sha256": digest(native),
    "xerial_runtime": xerial_runtime, "native_resource": str(native_resource),
    "require_matching_compile_options": require_matching_options,
    "runner_sha256": digest(ROOT / "scripts/benchmark/GatewayBenchmark.java"),
}
if a.before_classes:
    metadata["before_native_sha256"] = digest(a.before_classes / native_resource)
if a.xerial_native_dir:
    metadata["xerial_override_native_sha256"] = digest(a.xerial_native_dir / native_name)
elif a.xerial_classes:
    metadata["xerial_classes_native_sha256"] = digest(a.xerial_classes / native_resource)
patch = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)
(a.output / "fork-working-tree.patch").write_bytes(patch)
metadata["fork_patch_sha256"] = hashlib.sha256(patch).hexdigest()
# The local application includes uncommitted source files; HEAD alone is not a reproducer.
source_files = sorted({f for pattern in ["backend/src/**/*", "backend/*.kts", "backend/gradle.lockfile",
    "*.kts", "gradle.properties", "gradlew*", "gradle/wrapper/*", "gradle/verification-metadata.xml"]
    for f in a.gateway.glob(pattern) if f.is_file()})
with zipfile.ZipFile(a.output / "gateway-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for f in source_files:
        archive.write(f, f.relative_to(a.gateway))
source_manifest = {str(f.relative_to(a.gateway)): digest(f) for f in source_files}
(a.output / "gateway-source-manifest.json").write_text(json.dumps(source_manifest, indent=2) + "\n")
metadata["gateway_source_manifest_sha256"] = digest(a.output / "gateway-source-manifest.json")
(a.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
results = []
drivers = {"xerial": xerial_runtime, "fork": str(ROOT / "target/classes")}
if a.before_classes:
    drivers = {"xerial": xerial_runtime, "before": str(a.before_classes.resolve()), "fork": str(ROOT / "target/classes")}
labels = list(drivers)
for copies in a.scenarios:
    for rep in range(a.repetitions):
        # Rotate sequential JVMs to balance order; never run competing drivers on the host.
        offset = rep % len(labels)
        order = labels[offset:] + labels[:offset]
        for label in order:
            out = a.output / f"backups-{copies}-rep-{rep+1}-{label}"
            if out.exists():
                raise RuntimeError(f"Refusing to overwrite a prior run: {out}")
            out.mkdir()
            driver = drivers[label]
            cp = os.pathsep.join([str(classes), driver] + common)
            cmd = [java, "--enable-native-access=ALL-UNNAMED", "-Xms512m", "-Xmx512m",
                   f"-Djdk.virtualThreadScheduler.parallelism={a.carriers}",
                   f"-Djdk.virtualThreadScheduler.maxPoolSize={a.carriers}",
                   "-Dio.netty.eventLoopThreads=4", f"-Dlogback.configurationFile={logback}",
                   "-cp", cp, "io.gateway.GatewayBenchmark", str(out), str(a.seconds), str(a.clients), str(copies), str(a.backup_pages), str(a.warmup_requests)]
            if label == "xerial" and a.xerial_native_dir:
                cmd[1:1] = [f"-Dorg.sqlite.lib.path={a.xerial_native_dir.resolve()}",
                            f"-Dorg.sqlite.lib.name={native_name}", "-Xlog:library=debug"]
            (out / "command.json").write_text(json.dumps(cmd, indent=2) + "\n")
            print(f"RUN {out.name}", flush=True)
            status = run(cmd, out / "stdout.log", timeout=a.seconds + 180, check=False)
            if not (out / "result.json").is_file():
                raise RuntimeError(f"Run produced no complete measurement (exit {status}): {out}")
            row = json.loads((out / "result.json").read_text())
            row["process_exit_code"] = status
            row["outcome_passed"] = (
                status == 0 and row["failures"] == 0 and not row["errors"]
                and row["integrity"] == "ok" and row["source_calls"] == row["successes"])
            if results:
                if row["sqlite_source_id"] != results[0]["sqlite_source_id"]:
                    raise RuntimeError(f"SQLite source ID differs: {out}")
                if require_matching_options and sorted(row["compile_options"]) != sorted(results[0]["compile_options"]):
                    raise RuntimeError(f"SQLite compile options differ: {out}")
            row["databases_discarded"] = False
            if a.discard_databases and row["outcome_passed"]:
                data = out / "data"
                if data.is_symlink() or data.resolve() != out.resolve() / "data":
                    raise RuntimeError(f"Refusing to delete unowned data: {data}")
                shutil.rmtree(data)
                row["databases_discarded"] = True
            (out / "result.json").write_text(json.dumps(row, indent=2) + "\n")
            row.update(driver=label, repetition=rep+1)
            results.append(row)
            (a.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
            print(f"  rps={row['requests_per_second']:.2f} p99={row['http_latency']['p99_ms']:.2f}ms "
                  f"heartbeat_p99={row['heartbeat_delay']['p99_ms']:.2f}ms pins={row['jfr']['pinned_events']} "
                  f"failures={row['failures']} exit={status}", flush=True)
summary = []
for copies in a.scenarios:
    for label in labels:
        rows = [r for r in results if r["backup_concurrency"] == copies and r["driver"] == label]
        summary.append({"backups": copies, "driver": label, "runs": len(rows),
            "failed_runs": sum(not r["outcome_passed"] for r in rows),
            "median_rps": statistics.median(r["requests_per_second"] for r in rows),
            "min_rps": min(r["requests_per_second"] for r in rows),
            "max_rps": max(r["requests_per_second"] for r in rows),
            "median_http_p99_ms": statistics.median(r["http_latency"]["p99_ms"] for r in rows),
            "min_http_p99_ms": min(r["http_latency"]["p99_ms"] for r in rows),
            "max_http_p99_ms": max(r["http_latency"]["p99_ms"] for r in rows),
            "median_heartbeat_p99_ms": statistics.median(r["heartbeat_delay"]["p99_ms"] for r in rows),
            "min_heartbeat_p99_ms": min(r["heartbeat_delay"]["p99_ms"] for r in rows),
            "max_heartbeat_p99_ms": max(r["heartbeat_delay"]["p99_ms"] for r in rows),
            "median_cpu_seconds": statistics.median(r["cpu_seconds"] for r in rows),
            "min_cpu_seconds": min(r["cpu_seconds"] for r in rows),
            "max_cpu_seconds": max(r["cpu_seconds"] for r in rows),
            "min_warmup_elapsed_seconds": min(r["warmup_elapsed_seconds"] for r in rows),
            "max_warmup_elapsed_seconds": max(r["warmup_elapsed_seconds"] for r in rows),
            "max_heartbeat_ms": max(r["heartbeat_delay"]["max_ms"] for r in rows),
            "total_pins": sum(r["jfr"]["pinned_events"] for r in rows),
            "total_failures": sum(r["failures"] for r in rows)})
(a.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
failed_runs = sum(not row["outcome_passed"] for row in results)
if failed_runs:
    raise SystemExit(f"Completed all {len(results)} measurements; {failed_runs} had failed outcomes. See results.json and summary.json.")
