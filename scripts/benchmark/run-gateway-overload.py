#!/usr/bin/env python3
"""Observe Gateway admission overload; application throughput is not isolated JDBC lock cost."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[2]
VARIANTS = ('xerial', 'fork', 'flat', 'reentrant')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def run(command, log, timeout=300):
    save(log.with_suffix('.command.json'), list(map(str, command)))
    with log.open('w') as output:
        try:
            return subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output,
                                  stderr=subprocess.STDOUT, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            output.write(f'\nTIMEOUT after {timeout} seconds\n')
            return 124


def summarize(rows):
    summary = []
    for copies in (0, 4):
        for driver in VARIANTS:
            group = [r for r in rows if r['driver'] == driver and r['backup_concurrency'] == copies]
            complete = [r for r in group if 'requests_per_second' in r]
            cell = {'driver': driver, 'backups': copies, 'runs': len(group),
                    'complete_runs': len(complete),
                    'invalid_runs': sum(not r['experiment_valid'] for r in group),
                    'all_requests_success_runs': sum(r.get('application_all_requests_success', False) for r in group)}
            if complete:
                for name, values in {
                    'successes_per_second': [r['requests_per_second'] for r in complete],
                    'cpu_seconds': [r['cpu_seconds'] for r in complete],
                    'cpu_ms_per_attempt': [r['cpu_ms_per_attempt'] for r in complete],
                    'all_attempt_http_p99_ms': [r['http_latency']['p99_ms'] for r in complete],
                    'heartbeat_p99_ms': [r['heartbeat_delay']['p99_ms'] for r in complete],
                    'heartbeat_max_ms': [r['heartbeat_delay']['max_ms'] for r in complete],
                    'timer_jitter_p99_ms': [r['timer_jitter']['p99_ms'] for r in complete],
                    'timer_jitter_max_ms': [r['timer_jitter']['max_ms'] for r in complete],
                }.items():
                    cell.update({f'median_{name}': statistics.median(values),
                                 f'min_{name}': min(values), f'max_{name}': max(values)})
                for name in ('completed_attempts', 'successes', 'admission_rejections', 'other_failures',
                             'source_calls', 'backup_successes', 'backup_errors'):
                    cell[f'total_{name}'] = sum(r[name] for r in complete)
                attempts = cell['total_completed_attempts']
                cell['overall_completion_fraction'] = cell['total_successes'] / attempts if attempts else 0
                cell['overall_rejection_fraction'] = cell['total_admission_rejections'] / attempts if attempts else 0
                cell['total_cpu_seconds'] = sum(r['cpu_seconds'] for r in complete)
                cell['cpu_ms_per_attempt'] = cell['total_cpu_seconds'] * 1000 / attempts if attempts else 0
                cell['cpu_scope'] = 'whole measured process, including backups and rejected/failed work'
                cell['total_pins'] = sum(r['jfr']['pinned_events'] for r in complete)
            summary.append(cell)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/vt-wait-evidence/ci-gateway-overload')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--warmup-requests', type=int, default=6000)
    args = parser.parse_args()
    if args.seconds < 4 or args.warmup_requests < 12:
        parser.error('At least four seconds and twelve warmup requests required')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    base = ROOT / 'target/vt-wait-evidence/ci-benchmark'
    variants = ROOT / 'target/ci/lock-variants'
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    entries = (base / 'gateway-classpath.txt').read_text().strip().split(os.pathsep)
    common = [e for e in entries if 'sqlite-jdbc' not in Path(e).name]
    drivers = {'xerial': ROOT / 'target/ci/upstream/target/classes', 'fork': ROOT / 'target/classes',
               **{name: variants / name / 'target/classes' for name in ('flat', 'reentrant')}}
    native_resource = Path('org/sqlite/native/Linux/x86_64/libsqlitejdbc.so')
    natives = {name: drivers['xerial' if name == 'xerial' else 'fork'] / native_resource for name in VARIANTS}
    identity = {}
    for name, classes in drivers.items():
        manifest = {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob('*.class'))}
        if not manifest or 'org/sqlite/core/NativeDB.class' not in manifest:
            raise RuntimeError(f'Missing driver classes: {classes}')
        save(out / f'{name}-class-manifest.json', manifest)
        identity[name] = {'classes': str(classes), 'class_manifest_sha256': digest(out / f'{name}-class-manifest.json'),
                          'native_library': str(natives[name]), 'native_library_sha256': digest(natives[name])}
    if len({identity[n]['native_library_sha256'] for n in ('fork', 'flat', 'reentrant')}) != 1:
        raise RuntimeError('Fork variants do not share identical JNI')
    save(out / 'environment.json', {
        'arguments': {'seconds': args.seconds, 'warmup_requests': args.warmup_requests, 'repetitions': 4},
        'build_identity': json.loads((base / 'build-identity.json').read_text()),
        'variant_provenance': json.loads((variants / 'provenance.json').read_text()),
        'variants': identity,
        'sources': {name: digest(ROOT / 'scripts/benchmark' / name) for name in
                    ('run-gateway-overload.py', 'GatewayBenchmark.java', 'ProfileSummary.java', 'LockCorrectness.java')},
        'measurement_contract': 'Application overload observation, not equal-work JDBC lock cost. '
            'Four sequential Latin rotations per scenario, 12 HTTP clients, 4 VT carriers. '
            'Normal load and 4 destination-lock-contended backups; 750ms locks every 2 seconds; whole snapshot. '
            'HTTP_CONCURRENCY_LIMIT denotes source HTTP semaphore admission timeout, not necessarily HTTP 429. '
            'Expected admission rejection does not invalidate an observation; no application SLO threshold is configured. '
            'CPU totals and CPU/attempt include backups and rejected/failed work; HTTP percentiles include all attempts. '
            'JFR MonitorInflate during warmup and measurement. No async-profiler or native probes. '
            'JFR samples are not CPU-time attribution; heartbeat is carrier-availability evidence.',
    })
    classes = out / 'classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/benchmark' / name for name in
               ('GatewayBenchmark.java', 'ProfileSummary.java', 'LockCorrectness.java')]
    if run([java.with_name('javac'), '-encoding', 'UTF-8', '-cp',
            os.pathsep.join([str(drivers['fork']), *common]), '-d', classes, *sources], out / 'javac.log'):
        raise RuntimeError('Benchmark compilation failed; see javac.log')
    logback = out / 'logback.xml'
    logback.write_text('<configuration><appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="STDOUT"/></root></configuration>')

    def command(driver):
        return [java, '--enable-native-access=ALL-UNNAMED', '-Xms512m', '-Xmx512m',
                '-Djdk.virtualThreadScheduler.parallelism=4', '-Djdk.virtualThreadScheduler.maxPoolSize=4',
                '-Dio.netty.eventLoopThreads=4', f'-Dlogback.configurationFile={logback}',
                f'-Dorg.sqlite.lib.path={natives[driver].parent}', '-Dorg.sqlite.lib.name=libsqlitejdbc.so',
                '-Xlog:library=debug', '-Dbenchmark.monitor.inflation=true', '-Dbenchmark.overload=true',
                '-cp', os.pathsep.join([str(classes), str(drivers[driver]), *common])]

    if run([*command('fork'), 'io.gateway.GatewayBenchmark', '--check-overload-classification'],
           out / 'classification-check.log'):
        raise RuntimeError('Overload classification check failed')

    correctness = []
    # Upstream has different restart semantics; it remains the application-level baseline.
    for driver in ('fork', 'flat', 'reentrant'):
        directory = out / f'correctness-{driver}'
        directory.mkdir()
        status = run([*command(driver), 'io.gateway.LockCorrectness', directory], directory / 'stdout.log')
        correctness.append({'driver': driver, 'process_exit_code': status, 'outcome_passed': status == 0})
        save(out / 'correctness.json', correctness)
    if any(not row['outcome_passed'] for row in correctness):
        raise RuntimeError('Correctness failed; benchmark matrix not started. See correctness logs.')

    rows, analysis_jobs = [], []
    engine_identity = None
    for copies in (0, 4):
        for rep in range(4):
            order = VARIANTS[rep:] + VARIANTS[:rep]
            for driver in order:
                directory = out / f'backups-{copies}-rep-{rep + 1}-{driver}'
                directory.mkdir()
                print(f'RUN {directory.name}', flush=True)
                status = run([*command(driver), 'io.gateway.GatewayBenchmark', directory,
                              args.seconds, 12, copies, -1, args.warmup_requests],
                             directory / 'stdout.log', timeout=args.seconds + 360)
                result = directory / 'result.json'
                row = json.loads(result.read_text()) if result.exists() else {'error': 'No complete result.json'}
                valid = False
                if result.exists():
                    current = (row['sqlite_source_id'], sorted(row['compile_options']), row['source_http_policy'])
                    if engine_identity is None:
                        engine_identity = current
                    attempts = row['completed_attempts']
                    valid = (status == 0 and current == engine_identity and attempts > 0
                             and row['overload_mode'] is True
                             and Path(unquote(urlparse(row['driver_location']).path)).resolve() == drivers[driver].resolve()
                             and attempts == row['successes'] + row['admission_rejections'] + row['other_failures']
                             and row['failures'] == row['admission_rejections'] + row['other_failures']
                             and row['other_failures'] == 0 and row['backup_errors'] == 0
                             and sum(row['errors'].values()) == row['failures'] + row['backup_errors']
                             and row['http_latency']['count'] == attempts and row['integrity'] == 'ok'
                             and row['heartbeat_delay']['count'] > 0 and row['timer_jitter']['count'] > 0
                             and row['source_calls'] == row['successes']
                             and row['warmup_requests'] == args.warmup_requests
                             and row['warmup_successes'] + row['warmup_admission_rejections'] == args.warmup_requests
                             and row['warmup_source_calls'] == row['warmup_successes']
                             and sum(row['warmup_errors'].values()) == row['warmup_admission_rejections']
                             and row['positive_control']['pinned_events'] > 0
                             and row['backup_pages_per_step'] == -1
                             and row['backup_latency']['count'] == row['backup_successes']
                             and row['backup_successes'] == copies * len(range(1, args.seconds - 1, 2)))
                    row['cpu_ms_per_attempt'] = row['cpu_seconds'] * 1000 / max(1, attempts)
                    row['cpu_scope'] = 'whole measured process, including backups and rejected/failed work'
                    row['overall_completion_fraction'] = row['success_fraction']
                    row['overall_rejection_fraction'] = row['admission_rejection_fraction']
                row.update(driver=driver, repetition=rep + 1, backup_concurrency=copies,
                           order=list(order), process_exit_code=status, experiment_valid=valid,
                           native_library_sha256=identity[driver]['native_library_sha256'])
                for name in ('application', 'warmup-monitors', 'positive-control'):
                    recording = directory / f'{name}.jfr'
                    if recording.exists():
                        analysis_jobs.append((recording, directory / f'{name}-summary.json'))
                    else:
                        row['experiment_valid'] = False
                # Preserve the benchmark's raw result and raw error strings separately from runner verdicts.
                save(directory / 'observation.json', row)
                rows.append(row)
                save(out / 'results.json', rows)
                if row['experiment_valid']:
                    shutil.rmtree(directory / 'data')
                print(f'  valid={row["experiment_valid"]} all_requests_success={row.get("application_all_requests_success")} '
                      f'successes/s={row.get("requests_per_second")} cpu_ms/attempt={row.get("cpu_ms_per_attempt")} '
                      f'admission_rejections={row.get("admission_rejections")}', flush=True)
    # No analysis process competes with a measured JVM.
    analysis = []
    for recording, summary in analysis_jobs:
        status = run([java, '-Xmx1g', '-cp', os.pathsep.join([str(classes), *common]),
                      'io.gateway.ProfileSummary', recording, summary], summary.with_suffix('.log'), timeout=180)
        analysis.append({'recording': str(recording.relative_to(out)), 'process_exit_code': status,
                         'outcome_passed': status == 0 and summary.exists()})
        save(out / 'analysis.json', analysis)
    failed_analysis = {Path(a['recording']).parent.name for a in analysis if not a['outcome_passed']}
    for row in rows:
        name = f'backups-{row["backup_concurrency"]}-rep-{row["repetition"]}-{row["driver"]}'
        if name in failed_analysis:
            row['experiment_valid'] = False
        save(out / name / 'observation.json', row)
    save(out / 'results.json', rows)
    summary = summarize(rows)
    save(out / 'summary.json', summary)
    print(json.dumps(summary, indent=2), flush=True)
    if any(not r['experiment_valid'] for r in rows) or any(not a['outcome_passed'] for a in analysis):
        raise SystemExit('Invalid overload observations or incomplete JFR analysis; inspect evidence')


if __name__ == '__main__':
    main()
