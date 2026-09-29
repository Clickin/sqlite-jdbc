#!/usr/bin/env python3
"""Sequential fixed-work JDBC lock comparison; diagnostic JFR never enters headline rates."""
import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
VARIANTS = ('xerial', 'fork', 'flat', 'reentrant')
SCENARIOS = {'single': 1, 'private16': 16, 'shared4': 4, 'shared16': 16}
CORRECTNESS = {'cancellation_while_execution_locked', 'concurrent_close', 'public_native_serialization',
               'jni_callbacks_and_serialization', 'shared_connection_generated_key_contention',
               'commit_restart_recovery', 'rollback_restart_recovery'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def run(command, log, timeout=300):
    save(log.with_suffix('.command.json'), list(map(str, command)))
    with log.open('w') as output:
        try:
            return subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output,
                                  stderr=subprocess.STDOUT, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            output.write(f'\nTIMEOUT after {timeout} seconds\n')
            return 124


def share(total, workers, worker):
    return total // workers + (worker < total % workers)


def validate(row, scenario, operations, warmup, kind):
    """Independently reject partial work rather than normalizing by successful operations."""
    errors = []
    workers = SCENARIOS[scenario]
    if (row.get('valid') is not True or row.get('scenario') != scenario or row.get('kind') != kind
            or row.get('operations') != operations or row.get('warmup_operations') != warmup
            or row.get('worker_count') != workers
            or row.get('connection_count') != (1 if scenario.startswith('shared') else workers)
            or row.get('integrity') != 'ok'):
        errors.append('invalid result identity, work contract, or integrity')
    for name, total in (('warmup', warmup), ('measurement', operations)):
        phase = row.get(name, {})
        details = phase.get('workers', [])
        if (phase.get('planned_operations') != total or phase.get('completed_operations') != total
                or len(details) != workers):
            errors.append(f'{name}: incomplete work')
        for i, worker in enumerate(details):
            if (worker.get('worker') != i or worker.get('planned') != share(total, workers, i)
                    or worker.get('completed') != share(total, workers, i)
                    or worker.get('duration_ns', -1) < 0 or worker.get('start_offset_ns', -1) < 0):
                errors.append(f'{name}: worker {i} invariant')
        if phase.get('wall_ns', 0) <= 0 or phase.get('process_cpu_ns', -1) < 0:
            errors.append(f'{name}: missing CPU/wall clock')
        if details and phase.get('startup_spread_ns') != (
                max(w.get('start_offset_ns', 0) for w in details)
                - min(w.get('start_offset_ns', 0) for w in details)):
            errors.append(f'{name}: startup spread mismatch')
    counters = row.get('counters', [])
    if len(counters) != workers:
        errors.append('missing final counters')
    for i, counter in enumerate(counters):
        expected = share(warmup, workers, i) + share(operations, workers, i)
        if counter != {'worker': i, 'expected': expected, 'actual': expected}:
            errors.append(f'counter {i}: wrong final result')
    if not row.get('sqlite_source_id') or not row.get('compile_options') or not row.get('jdk'):
        errors.append('missing runtime provenance')
    if kind == 'performance' and not errors:
        measured = row['measurement']
        for name, expected in (
                ('cpu_ns_per_operation', measured['process_cpu_ns'] / operations),
                ('operations_per_second', operations * 1e9 / measured['wall_ns'])):
            if not math.isclose(row.get(name, -1), expected, rel_tol=1e-12):
                errors.append(f'{name}: invalid denominator')
    return errors


def summarize(rows, variants, repetitions):
    summary = []
    for scenario in SCENARIOS:
        for driver in variants:
            group = [r for r in rows if r['scenario'] == scenario and r['driver'] == driver]
            valid = [r for r in group if r['outcome_passed']]
            cell = {'scenario': scenario, 'driver': driver, 'runs': len(group), 'valid_runs': len(valid)}
            if len(valid) == repetitions:
                for name in ('cpu_ns_per_operation', 'operations_per_second'):
                    values = [r[name] for r in valid]
                    mean = statistics.mean(values)
                    cell[name] = {'min': min(values), 'median': statistics.median(values), 'max': max(values),
                                  'cv': statistics.pstdev(values) / mean if mean > 0 else None}
                cell['min_measurement_seconds'] = min(r['measurement']['wall_ns'] for r in valid) / 1e9
                cell['short_run'] = cell['min_measurement_seconds'] < 5
                cell['noisy'] = repetitions < 2 or cell['short_run'] or any(
                    cell[name]['cv'] is None or cell[name]['cv'] > 0.05
                    for name in ('cpu_ns_per_operation', 'operations_per_second'))
            else:
                cell['noisy'] = True
            summary.append(cell)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/vt-wait-evidence/ci-lock-comparison')
    parser.add_argument('--operations', type=int, default=5_000_000)
    parser.add_argument('--warmup-operations', type=int, default=1_000_000)
    parser.add_argument('--repetitions', type=int, default=4)
    parser.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument('--timeout-seconds', type=int, default=180)
    parser.add_argument('--diagnostic-divisor', type=int, default=50,
                        help='Separate diagnostic operation totals are divided by this value (minimum 1)')
    parser.add_argument('--native-root', type=Path,
                        help='Fork-family class/resource root containing org/sqlite/native; upstream stays separate')
    parser.add_argument('--slf4j-api', type=Path, default=Path.home() /
                        '.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar')
    args = parser.parse_args()
    if (args.operations < 1 or args.warmup_operations < 0 or args.repetitions < 1
            or args.timeout_seconds < 1 or args.diagnostic_divisor < 1
            or args.operations + args.warmup_operations > 2**63 - 1
            or len(set(args.variants)) != len(args.variants)):
        parser.error('Require positive operations/repetitions/timeouts/divisor, nonnegative warmup, long-sized totals, unique variants')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    base = ROOT / 'target/vt-wait-evidence/ci-benchmark'
    variant_root = ROOT / 'target/ci/lock-variants'
    java_name = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java_name:
        raise RuntimeError('JDK 25 required: set JAVA_HOME')
    java = Path(java_name).resolve()
    java_version = subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT, text=True)
    (out / 'java-version.txt').write_text(java_version)
    javac_version = subprocess.check_output([java.with_name('javac'), '-version'], stderr=subprocess.STDOUT, text=True)
    (out / 'javac-version.txt').write_text(javac_version)
    if not javac_version.startswith('javac 25.'):
        raise RuntimeError('Benchmark requires JDK 25; found ' + javac_version)
    slf4j = args.slf4j_api.resolve()
    if not slf4j.is_file():
        raise RuntimeError(f'Missing slf4j-api 1.7.36: {slf4j}')
    drivers = {'xerial': ROOT / 'target/ci/upstream/target/classes', 'fork': ROOT / 'target/classes',
               **{name: variant_root / name / 'target/classes' for name in ('flat', 'reentrant')}}
    source_roots = {'xerial': ROOT / 'target/ci/upstream/src/main/java', 'fork': ROOT / 'src/main/java',
                    **{name: variant_root / name / 'src/main/java' for name in ('flat', 'reentrant')}}
    machine = {'arm64': 'aarch64', 'amd64': 'x86_64'}.get(platform.machine().lower(), platform.machine().lower())
    systems = {'Darwin': ('Mac', 'libsqlitejdbc.dylib'), 'Linux': ('Linux', 'libsqlitejdbc.so')}
    if platform.system() not in systems:
        raise RuntimeError('Supported native platforms: Linux and macOS')
    os_name, library = systems[platform.system()]
    resource = Path('org/sqlite/native') / os_name / machine / library
    natives = {name: ((args.native_root.resolve() if args.native_root else drivers['fork'])
                     if name != 'xerial' else drivers['xerial']) / resource for name in args.variants}
    identity = {}
    for name in args.variants:
        classes = drivers[name]
        manifest = {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob('*.class'))}
        sources = {str(p.relative_to(source_roots[name])): digest(p)
                   for p in sorted(source_roots[name].rglob('*.java'))}
        if 'org/sqlite/core/NativeDB.class' not in manifest or not sources:
            raise RuntimeError(f'Missing {name} driver classes or source tree')
        save(out / f'{name}-class-manifest.json', manifest)
        save(out / f'{name}-source-manifest.json', sources)
        identity[name] = {'classes': str(classes), 'class_manifest_sha256': digest(out / f'{name}-class-manifest.json'),
                          'source_root': str(source_roots[name]),
                          'source_manifest_sha256': digest(out / f'{name}-source-manifest.json'),
                          'native_library': str(natives[name]), 'native_library_sha256': digest(natives[name])}
    if len({identity[n]['native_library_sha256'] for n in args.variants if n != 'xerial'}) > 1:
        raise RuntimeError('Fork variants do not share identical JNI')
    build_path = base / 'build-identity.json'
    hosted = os.environ.get('GITHUB_ACTIONS') == 'true' or os.environ.get('CI', '').lower() in ('1', 'true')
    if hosted and not build_path.is_file():
        raise RuntimeError('Hosted runs require build-identity.json')
    provenance_path = variant_root / 'provenance.json'
    if any(n in args.variants for n in ('flat', 'reentrant')) and not provenance_path.is_file():
        raise RuntimeError('Missing generated variant provenance.json')
    save(out / 'environment.json', {
        'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'run_scope': 'hosted' if hosted else 'local-smoke',
        'java_version': java_version, 'javac_version': javac_version,
        'platform': platform.platform(), 'logical_cpus': os.cpu_count(),
        'build_identity': json.loads(build_path.read_text()) if build_path.is_file() else {'scope': 'local-smoke', 'build_identity_available': False},
        'variant_provenance': json.loads(provenance_path.read_text()) if provenance_path.is_file() else None,
        'variants': identity, 'slf4j_api': {'path': str(slf4j), 'sha256': digest(slf4j)},
        'sources': {name: digest(ROOT / 'scripts/benchmark' / name) for name in
                    ('run-lock-comparison.py', 'JdbcLockBenchmark.java', 'ProfileSummary.java', 'LockCorrectness.java')},
        'measurement_contract': 'Fixed total prepared UPDATE operations; in-memory SQLite; per-worker statements and rows; '
            'same connections/statements through warmup and measurement; one VT per worker; ready/start barrier; '
            'wall ends after all joins; whole-process CPU delta over the measurement window divided by planned work only '
            'after full validation. Four carrier threads. No retries, admission, HTTP, pool, backups or timed denominator. '
            'Fresh sequential JVMs, cyclic variant rotations per scenario. JFR only in separate diagnostic JVMs. '
            'No latency sampling. Startup spread and worker durations are not per-operation latency.',
    })
    classes = out / 'classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/benchmark' / name for name in
               ('JdbcLockBenchmark.java', 'ProfileSummary.java', 'LockCorrectness.java')]
    if run([java.with_name('javac'), '-encoding', 'UTF-8', '-cp', os.pathsep.join([str(drivers['fork']), str(slf4j)]),
            '-d', classes, *sources], out / 'javac.log'):
        raise RuntimeError('Benchmark compilation failed; see javac.log')

    def command(driver):
        return [java, '--enable-native-access=ALL-UNNAMED', '-Xms512m', '-Xmx512m',
                '-Djdk.virtualThreadScheduler.parallelism=4', '-Djdk.virtualThreadScheduler.maxPoolSize=4',
                f'-Dorg.sqlite.lib.path={natives[driver].parent}', f'-Dorg.sqlite.lib.name={library}',
                '-Xlog:library=debug', '-cp', os.pathsep.join([str(classes), str(drivers[driver]), str(slf4j)])]

    correctness = []
    for driver in args.variants:
        if driver == 'xerial':
            correctness.append({'driver': driver, 'skipped': True,
                                'reason': 'Fork-only public API/restart semantics; performance cells still validate exact work and integrity',
                                'outcome_passed': True})
            continue
        directory = out / f'correctness-{driver}'
        directory.mkdir()
        status = run([*command(driver), 'io.gateway.LockCorrectness', directory], directory / 'stdout.log')
        result = directory / 'correctness.json'
        passed = json.loads(result.read_text()).get('passed', []) if result.exists() else []
        correctness.append({'driver': driver, 'process_exit_code': status, 'passed': passed,
                            'outcome_passed': status == 0 and set(passed) == CORRECTNESS and len(passed) == 7})
        save(out / 'correctness.json', correctness)
    save(out / 'correctness.json', correctness)
    if any(not row['outcome_passed'] for row in correctness):
        raise RuntimeError('Correctness failed; performance matrix not started')

    rows, diagnostics, analysis_jobs = [], [], []
    engine_identity = None
    jdk_identity = None

    def cell(driver, scenario, operations, warmup, kind, repetition, order):
        nonlocal engine_identity, jdk_identity
        directory = out / f'{kind}-{scenario}-rep-{repetition}-{driver}'
        directory.mkdir()
        print('RUN ' + directory.name, flush=True)
        status = run([*command(driver), 'io.gateway.JdbcLockBenchmark', directory, scenario,
                      operations, warmup, args.timeout_seconds, kind], directory / 'stdout.log',
                     timeout=args.timeout_seconds * 4 + 60)
        result = directory / 'result.json'
        try:
            row = json.loads(result.read_text()) if result.exists() else {}
            errors = validate(row, scenario, operations, warmup, kind)
        except (ValueError, TypeError, KeyError, AttributeError) as failure:
            row, errors = {}, ['Malformed result: ' + str(failure)]
        if status:
            errors.append(f'JVM exited {status}')
        current = (row.get('sqlite_source_id'), tuple(row.get('compile_options', [])))
        current_jdk = row.get('jdk', {}).get('runtime_version')
        if not errors:
            if engine_identity is None:
                engine_identity, jdk_identity = current, current_jdk
            if current != engine_identity:
                errors.append('SQLite source ID / compile options differ from matrix baseline')
            if current_jdk != jdk_identity or not str(current_jdk).startswith('25.'):
                errors.append('Effective JDK differs or is not JDK 25')
        if kind == 'diagnostic':
            for name in ('warmup-monitors', 'measurement-monitors'):
                recording = directory / f'{name}.jfr'
                if not recording.exists() or recording.stat().st_size == 0:
                    errors.append('Missing diagnostic recording: ' + name)
                else:
                    analysis_jobs.append((recording, directory / f'{name}-summary.json'))
        if errors:
            row.pop('cpu_ns_per_operation', None)
            row.pop('operations_per_second', None)
        row.update(driver=driver, scenario=scenario, kind=kind, repetition=repetition, order=list(order),
                   process_exit_code=status, outcome_passed=not errors, validation_errors=errors,
                   native_library_sha256=identity[driver]['native_library_sha256'],
                   raw_result=str(result.relative_to(out)))
        save(directory / 'runner-result.json', row)
        print(f'  passed={not errors} errors={errors}', flush=True)
        return row

    for scenario in SCENARIOS:
        for rep in range(args.repetitions):
            offset = rep % len(args.variants)
            order = args.variants[offset:] + args.variants[:offset]
            for driver in order:
                rows.append(cell(driver, scenario, args.operations, args.warmup_operations,
                                 'performance', rep + 1, order))
                save(out / 'results.json', rows)
    for scenario in ('single', 'shared16'):
        for driver in args.variants:
            diagnostics.append(cell(driver, scenario, max(1, args.operations // args.diagnostic_divisor),
                                    max(1, args.warmup_operations // args.diagnostic_divisor) if args.warmup_operations else 0,
                                    'diagnostic', 1, args.variants))
            save(out / 'diagnostics.json', diagnostics)
    # No analysis process competes with a benchmark JVM.
    analysis = []
    for recording, summary_path in analysis_jobs:
        status = run([java, '-Xmx1g', '-cp', str(classes), 'io.gateway.ProfileSummary', recording, summary_path],
                     summary_path.with_suffix('.log'), timeout=180)
        valid = status == 0 and summary_path.is_file()
        if valid:
            profile = json.loads(summary_path.read_text())
            valid = not profile.get('errors') and 'monitor_inflations' in profile
        analysis.append({'recording': str(recording.relative_to(out)), 'process_exit_code': status,
                         'outcome_passed': valid})
        save(out / 'analysis.json', analysis)
    summary = summarize(rows, args.variants, args.repetitions)
    save(out / 'summary.json', summary)
    fields = ['scenario', 'driver', 'repetition', 'outcome_passed', 'operations', 'warmup_operations',
              'cpu_ns_per_operation', 'operations_per_second', 'native_library_sha256', 'raw_result', 'validation_errors']
    with (out / 'results.csv').open('w', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    report = ['# Isolated JDBC lock comparison', '',
              'Fixed total work; valid performance JVMs only. Separate JFR diagnostics are excluded.',
              'CV is population standard deviation / mean. CV > 5%, measured duration < 5s, or fewer than two runs is flagged noisy.',
              'These descriptive measurements do not establish a winner; a smoke run is not performance evidence.', '',
              '| Scenario | Driver | Valid / runs | CPU ns/op min / median / max | CPU CV | Ops/s min / median / max | Ops/s CV | Noisy |',
              '|---|---|---:|---:|---:|---:|---:|---:|']
    for item in summary:
        metrics = []
        for name in ('cpu_ns_per_operation', 'operations_per_second'):
            metric = item.get(name)
            metrics.extend([' / '.join(f'{metric[k]:.2f}' for k in ('min', 'median', 'max')) if metric else 'invalid',
                            f'{metric["cv"]:.2%}' if metric and metric['cv'] is not None else 'n/a'])
        report.append(f'| {item["scenario"]} | {item["driver"]} | {item["valid_runs"]} / {item["runs"]} | '
                      + ' | '.join(metrics) + f' | {item["noisy"]} |')
    failed = any(not row['outcome_passed'] for row in rows + diagnostics + analysis)
    expected_runs = len(SCENARIOS) * args.repetitions * len(args.variants)
    failed |= len(rows) != expected_runs or len(diagnostics) != 2 * len(args.variants) or len(analysis) != 4 * len(args.variants)
    report.extend(['', f'Overall validation: {"FAIL" if failed else "PASS"}. '
                   f'{len(rows)} performance JVMs, {len(diagnostics)} diagnostic JVMs; see correctness.json and analysis.json.'])
    (out / 'summary.md').write_text('\n'.join(report) + '\n')
    print(json.dumps(summary, indent=2), flush=True)
    if failed:
        raise SystemExit('Invalid or incomplete benchmark evidence; inspect runner-result.json, correctness.json and analysis.json')


if __name__ == '__main__':
    main()
