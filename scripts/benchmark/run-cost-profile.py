#!/usr/bin/env python3
"""Normal-load attribution, with stock and probe-disabled overhead controls.

All JVMs and all offline analysis run sequentially. Production inputs are unchanged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=ROOT / 'target/vt-wait-evidence/ci-cost-profile')
p.add_argument('--profiler-home', type=Path, required=True)
p.add_argument('--profiler-jar', type=Path, required=True)
p.add_argument('--repetitions', type=int, default=3)
p.add_argument('--seconds', type=int, default=30)
p.add_argument('--warmup-requests', type=int, default=6000)
a = p.parse_args()
if min(a.repetitions, a.seconds) < 1 or a.warmup_requests < 12:
    p.error('Positive repetitions/duration and at least twelve warmup requests required')
out = a.output.resolve()
if out.exists():
    raise RuntimeError(f'Refusing to overwrite {out}')
out.mkdir(parents=True)
java = Path(os.environ['JAVA_HOME']) / 'bin/java'
javac = java.with_name('javac')
base = ROOT / 'target/vt-wait-evidence/ci-benchmark'
probes = ROOT / 'target/ci/cost-probes'
profiler_home = a.profiler_home.resolve()
profiler_jar = a.profiler_jar.resolve()
ap_library = profiler_home / 'lib/libasyncProfiler.so'
entries = (base / 'gateway-classpath.txt').read_text().strip().split(os.pathsep)
common = [entry for entry in entries if 'sqlite-jdbc' not in Path(entry).name]
classes = out / 'classes'; classes.mkdir()
compile_cp = os.pathsep.join(entries + [str(profiler_jar)])

def run(command, log, timeout=300, check=True):
    with log.open('w') as output:
        result = subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output,
                                stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'exit {result.returncode}: see {log}')
    return result.returncode

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

run([javac, '-encoding', 'UTF-8', '-cp', compile_cp, '-d', classes,
     ROOT / 'scripts/benchmark/GatewayBenchmark.java',
     ROOT / 'scripts/benchmark/CostProbe.java', ROOT / 'scripts/benchmark/ProfileSummary.java'], out / 'javac.log')
logback = out / 'logback.xml'
logback.write_text('<configuration><appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="STDOUT"/></root></configuration>')
metadata = {
    'arguments': {key: str(value) if isinstance(value, Path) else value for key, value in vars(a).items()},
    'build_identity': json.loads((base / 'build-identity.json').read_text()),
    'probe_provenance': json.loads((probes / 'provenance.json').read_text()),
    'profiler_library_sha256': digest(ap_library), 'profiler_jar_sha256': digest(profiler_jar),
    'mode_contract': {'stock': 'unmodified native .text, original benchmark JFR only',
                      'async': 'same .text as stock, CPU+sampled Java allocation+lock profiler and supplemental JFR',
                      'probe-off': 'instrumented native, counters disabled; probe code-layout/branch control',
                      'counts': 'instrumented native, thread-local counters only',
                      'sampled': 'instrumented native, counts plus sampled CPU/wall timers'},
    'timing_semantics': 'Native categories are inclusive and may overlap; elapsed is not CPU; do not add nested costs.',
}
(out / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
drivers = {'xerial': ROOT / 'target/ci/upstream/target/classes', 'fork': ROOT / 'target/classes'}
modes = ['stock', 'async', 'probe-off', 'counts', 'sampled']
rows = []
identity = None
analysis_jobs = []
for rep in range(1, a.repetitions + 1):
    # Rotate mode order and reverse driver order to avoid a single chronological contrast.
    offset = (rep - 1) % len(modes)
    for mode in modes[offset:] + modes[:offset]:
        order = ['xerial', 'fork'] if rep % 2 else ['fork', 'xerial']
        for driver in order:
            directory = out / f'{mode}-rep-{rep}-{driver}'; directory.mkdir()
            native = probes / driver / ('symbols' if mode in ['stock', 'async'] else 'instrumented')
            cp = os.pathsep.join([str(classes), str(drivers[driver]), *common, str(profiler_jar)])
            command = [str(java), '--enable-native-access=ALL-UNNAMED', '-Xms512m', '-Xmx512m',
                       '-Djdk.virtualThreadScheduler.parallelism=4', '-Djdk.virtualThreadScheduler.maxPoolSize=4',
                       '-Dio.netty.eventLoopThreads=4', f'-Dlogback.configurationFile={logback}',
                       f'-Dorg.sqlite.lib.path={native}', '-Dorg.sqlite.lib.name=libsqlitejdbc.so',
                       '-Xlog:library=debug']
            if mode == 'async':
                command += [f'-agentpath:{ap_library}', f'-Dbenchmark.async.library={ap_library}']
            if mode in ['probe-off', 'counts', 'sampled']:
                command += [f'-Dbenchmark.cost.mode={dict(zip(modes[2:], [0, 1, 2]))[mode]}']
            command += ['-cp', cp, 'io.gateway.GatewayBenchmark', str(directory), str(a.seconds), '12', '0', '-1', str(a.warmup_requests)]
            (directory / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
            print(f'RUN {directory.name}', flush=True)
            status = run(command, directory / 'stdout.log', timeout=a.seconds + 360, check=False)
            if not (directory / 'result.json').exists():
                raise RuntimeError(f'Incomplete measurement: {directory} (exit {status})')
            row = json.loads((directory / 'result.json').read_text())
            current_identity = (row['sqlite_source_id'], sorted(row['compile_options']))
            if identity is None:
                identity = current_identity
            if current_identity != identity:
                raise RuntimeError(f'Driver identity mismatch: {directory}')
            if row['warmup_requests'] != a.warmup_requests or row['warmup_source_calls'] != a.warmup_requests:
                raise RuntimeError(f'Warmup mismatch: {directory}')
            valid = (status == 0 and row['failures'] == 0 and not row['errors'] and row['integrity'] == 'ok'
                     and row['source_calls'] == row['successes'] and row['positive_control']['pinned_events'] > 0)
            row.update(mode=mode, driver=driver, repetition=rep, process_exit_code=status,
                       outcome_passed=valid, native_library_sha256=digest(native / 'libsqlitejdbc.so'))
            if mode in ['probe-off', 'counts', 'sampled'] and 'native_cost' not in row:
                raise RuntimeError(f'Missing native counters: {directory}')
            if 'native_cost' in row:
                counters = row['native_cost']['categories']
                if mode == 'probe-off' and any(c['calls'] or c['samples'] for c in counters.values()):
                    raise RuntimeError(f'Disabled probe recorded calls: {directory}')
                if mode in ['counts', 'sampled'] and not counters['gethandle']['calls']:
                    raise RuntimeError(f'No measured JNI handle calls: {directory}')
                if mode == 'counts' and any(c['samples'] for c in counters.values()):
                    raise RuntimeError(f'Count-only probe ran timers: {directory}')
                if mode == 'sampled' and not counters['gethandle']['samples']:
                    raise RuntimeError(f'No native timing samples: {directory}')
            if mode == 'async':
                if not (directory / 'async-profile.jfr').exists():
                    raise RuntimeError(f'Missing CPU/allocation profile: {directory}')
                analysis_jobs.append((cp, directory / 'async-profile.jfr', directory / 'async-summary.json'))
            analysis_jobs.append((cp, directory / 'application.jfr', directory / 'jfr-summary.json'))
            (directory / 'result.json').write_text(json.dumps(row, indent=2) + '\n')
            rows.append(row)
            (out / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
            if valid:
                shutil.rmtree(directory / 'data')
            print(f'  rps={row["requests_per_second"]:.2f} cpu_ms/request={row["cpu_seconds"] * 1000 / max(1,row["successes"]):.3f} valid={valid}', flush=True)

# No analysis JVM competes with a measured JVM.
for cp, recording, summary in analysis_jobs:
    run([java, '-Xmx1g', '-cp', cp, 'io.gateway.ProfileSummary', recording, summary],
        summary.with_suffix('.log'), timeout=180)
summary = []
for mode in modes:
    for driver in drivers:
        group = [r for r in rows if r['mode'] == mode and r['driver'] == driver]
        rates = [r['requests_per_second'] for r in group]
        summary.append({'mode': mode, 'driver': driver, 'runs': len(group),
                        'median_rps': statistics.median(rates), 'min_rps': min(rates), 'max_rps': max(rates),
                        'median_cpu_ms_per_success': statistics.median(r['cpu_seconds'] * 1000 / r['successes'] for r in group),
                        'failed_runs': sum(not r['outcome_passed'] for r in group)})
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2), flush=True)
if any(not row['outcome_passed'] for row in rows):
    raise SystemExit('Complete profiling matrix contains failed application outcomes; see results.json')
