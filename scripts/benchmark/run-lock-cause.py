#!/usr/bin/env python3
"""Sequential fixed-work lock-cause controls; profiled and diagnostic rows never enter headline rates."""
import argparse
import csv
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('lock_comparison', Path(__file__).with_name('run-lock-comparison.py'))
comparison = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(comparison)
comparison.SCENARIOS.update(shared2=2, shared8=8)
run, save, digest = comparison.run, comparison.save, comparison.digest
VARIANTS = ('xerial', 'fork', 'reentrant')
# Each tuple is (scenario, worker kind, scheduler carriers, lock granularity).
CELLS = [(f'shared4-vt-c{n}', 'shared4', 'virtual', n, 'jdbc') for n in (1, 2, 4, 8)] + [
    ('shared4-platform', 'shared4', 'platform', 4, 'jdbc'),
    ('shared2-vt-c4', 'shared2', 'virtual', 4, 'jdbc'),
    ('shared8-vt-c4', 'shared8', 'virtual', 4, 'jdbc'),
    ('shared16-vt-c4', 'shared16', 'virtual', 4, 'jdbc'),
    ('single-vt-c4', 'single', 'virtual', 4, 'jdbc'),
    ('private16-vt-c4', 'private16', 'virtual', 4, 'jdbc'),
    ('shared4-outer-op', 'shared4', 'virtual', 4, 'outer-op'),
    ('shared16-outer-op', 'shared16', 'virtual', 4, 'outer-op'),
]
PROFILE_CELLS = {'single-vt-c4', 'shared4-vt-c4', 'shared4-platform', 'shared16-vt-c4', 'shared4-outer-op'}
DIAGNOSTIC_CELLS = PROFILE_CELLS - {'single-vt-c4'}


def validate(row, cell, operations, warmup, kind, driver):
    _, scenario, worker_kind, carriers, granularity = cell
    errors = comparison.validate(row, scenario, operations, warmup, kind)
    if row.get('worker_kind') != worker_kind or row.get('lock_granularity') != granularity:
        errors.append('Effective worker kind / lock granularity mismatch')
    # Upstream xerial has no Java SQL busy-retry path, hence no counter to observe.
    busy_counter = driver != 'xerial'
    for phase in ('warmup', 'measurement'):
        if busy_counter and row.get(phase, {}).get('java_busy_waits') != 0:
            errors.append(f'{phase}: expected zero Java SQL busy-retry observations')
    if (row.get('scheduler_parallelism') != str(carriers)
            or row.get('scheduler_max_pool_size') != str(carriers)):
        errors.append('Effective scheduler carrier properties mismatch')
    if busy_counter and row.get('java_busy_counter_available') is not True:
        errors.append('Java SQL busy-retry counter unavailable')
    return errors


def profile_errors(profile, mode):
    errors = list(profile.get('errors', []))
    if mode == 'async':
        settings, cpu = profile.get('settings', {}), profile.get('cpu', {})
        if (profile.get('producer') != 'async-profiler'
                or settings.get('jdk.ActiveRecording/event') != 'cpu'
                or settings.get('jdk.ActiveRecording/engine') != 'perf_events'
                or cpu.get('available') is not True or cpu.get('samples', 0) <= 0
                or profile.get('non_cpu_samples', {}).get('wall_clock_records', 0) != 0):
            errors.append('Missing nonzero perf_events CPU profile; wall-clock fallback is not CPU evidence')
        samples = cpu.get('samples', 0)
        events = profile.get('seen_event_types', {})
        observed = events.get('jdk.ExecutionSample', 0) + events.get('profiler.ExecutionSample', 0)
        if samples != observed or sum(cpu.get('leaf_frames', {}).values()) != samples:
            errors.append('CPU profile event / leaf sample count mismatch')
    elif profile.get('producer') != 'jdk-jfr' or 'monitor_inflations' not in profile:
        errors.append('Missing JDK monitor diagnostic summary')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/vt-wait-evidence/ci-lock-cause')
    parser.add_argument('--operations', type=int, default=2_000_000)
    parser.add_argument('--warmup-operations', type=int, default=500_000)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument('--profiler-home', type=Path)
    parser.add_argument('--profiler-jar', type=Path)
    parser.add_argument('--skip-profiles', action='store_true', help='Local smoke only: omit async CPU profiles, retain JFR diagnostics')
    parser.add_argument('--native-root', type=Path, help='Class/resource root containing the shared org/sqlite/native library')
    parser.add_argument('--timeout-seconds', type=int, default=180)
    parser.add_argument('--slf4j-api', type=Path, default=Path.home() /
                        '.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar')
    args = parser.parse_args()
    hosted = os.environ.get('GITHUB_ACTIONS') == 'true' or os.environ.get('CI', '').lower() in ('1', 'true')
    if (min(args.operations, args.repetitions, args.timeout_seconds) < 1 or args.warmup_operations < 0
            or args.operations + args.warmup_operations > 2**63 - 1
            or len(set(args.variants)) != len(args.variants)):
        parser.error('Require positive operations/repetitions/timeout, nonnegative warmup, long-sized totals, unique variants')
    if hosted and (args.skip_profiles or not {'xerial', 'fork'} <= set(args.variants)):
        parser.error('Hosted evidence requires xerial and fork variants and all profiles')
    if not args.skip_profiles and (not args.profiler_home or not args.profiler_jar):
        parser.error('Require --profiler-home and --profiler-jar, or --skip-profiles for local smoke')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    base = ROOT / 'target/vt-wait-evidence/ci-benchmark'
    variant_root = ROOT / 'target/ci/lock-variants'
    java_name = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java_name:
        raise RuntimeError('JDK 25 required: set JAVA_HOME')
    java = Path(java_name).resolve()
    for executable in (java, java.with_name('javac')):
        if run([executable, '-version'], out / f'{executable.name}-version.log'):
            raise RuntimeError('Cannot read JDK identity')
    java_version = (out / 'java-version.log').read_text()
    javac_version = (out / 'javac-version.log').read_text()
    if not javac_version.startswith('javac 25.'):
        raise RuntimeError('Benchmark requires JDK 25; found ' + javac_version)
    if run([java, '-Xms512m', '-Xmx512m', '-XX:+PrintFlagsFinal', '-version'], out / 'jvm-flags.log'):
        raise RuntimeError('Cannot capture benchmark JVM flags')
    slf4j = args.slf4j_api.resolve()
    if not slf4j.is_file():
        raise RuntimeError(f'Missing slf4j-api 1.7.36: {slf4j}')
    drivers = {'xerial': ROOT / 'target/ci/upstream/target/classes', 'fork': ROOT / 'target/classes',
               'reentrant': variant_root / 'reentrant/target/classes'}
    source_roots = {'xerial': ROOT / 'target/ci/upstream/src/main/java', 'fork': ROOT / 'src/main/java',
                    'reentrant': variant_root / 'reentrant/src/main/java'}
    machine = {'arm64': 'aarch64', 'amd64': 'x86_64'}.get(platform.machine().lower(), platform.machine().lower())
    systems = {'Darwin': ('Mac', 'libsqlitejdbc.dylib'), 'Linux': ('Linux', 'libsqlitejdbc.so')}
    if platform.system() not in systems:
        raise RuntimeError('Supported native platforms: Linux and macOS')
    os_name, library = systems[platform.system()]
    resource = Path('org/sqlite/native') / os_name / machine / library
    # xerial keeps its own JNI library (built by build-ci.sh against the same SQLite object).
    natives = {name: (drivers['xerial'] if name == 'xerial'
                      else args.native_root.resolve() if args.native_root else drivers['fork']) / resource
               for name in VARIANTS}
    native_hashes = {name: digest(natives[name]) for name in args.variants}
    identity = {}
    for name in args.variants:
        manifest = {str(p.relative_to(drivers[name])): digest(p) for p in sorted(drivers[name].rglob('*.class'))}
        sources = {str(p.relative_to(source_roots[name])): digest(p) for p in sorted(source_roots[name].rglob('*.java'))}
        if 'org/sqlite/core/NativeDB.class' not in manifest or not sources:
            raise RuntimeError(f'Missing {name} driver classes or source tree')
        save(out / f'{name}-class-manifest.json', manifest)
        save(out / f'{name}-source-manifest.json', sources)
        shutil.copytree(source_roots[name], out / 'sources' / name)
        identity[name] = {'classes': str(drivers[name]), 'class_manifest_sha256': digest(out / f'{name}-class-manifest.json'),
                          'source_root': str(source_roots[name]), 'source_manifest_sha256': digest(out / f'{name}-source-manifest.json'),
                          'native_library': str(natives[name]), 'native_library_sha256': native_hashes[name]}
    build_path, provenance_path = base / 'build-identity.json', variant_root / 'provenance.json'
    if hosted and not build_path.is_file():
        raise RuntimeError('Hosted runs require build-identity.json')
    if 'reentrant' in args.variants and not provenance_path.is_file():
        raise RuntimeError('Missing generated variant provenance.json')
    ap_library = args.profiler_home.resolve() / 'lib/libasyncProfiler.so' if not args.skip_profiles else None
    profiler_jar = args.profiler_jar.resolve() if not args.skip_profiles else None
    profiler_identity = ({'library': str(ap_library), 'library_sha256': digest(ap_library),
                          'jar': str(profiler_jar), 'jar_sha256': digest(profiler_jar)} if ap_library else
                         {'scope': 'omitted-local-smoke', 'cpu_attribution_available': False})
    source_names = ('run-lock-cause.py', 'run-lock-comparison.py', 'build-lock-variants.py',
                    'JdbcLockBenchmark.java', 'ProfileSummary.java')
    benchmark_sources = out / 'sources/benchmark'
    benchmark_sources.mkdir()
    for name in source_names:
        shutil.copy2(ROOT / 'scripts/benchmark' / name, benchmark_sources / name)
    save(out / 'environment.json', {
        'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'run_scope': 'hosted' if hosted else 'local-smoke',
        'java_executable': str(java), 'java_version': java_version, 'javac_version': javac_version,
        'platform': platform.platform(), 'logical_cpus': os.cpu_count(),
        'build_identity': json.loads(build_path.read_text()) if build_path.is_file() else {'scope': 'local-smoke', 'build_identity_available': False},
        'variant_provenance': json.loads(provenance_path.read_text()) if provenance_path.is_file() else None,
        'variants': identity, 'profiler': profiler_identity, 'slf4j_api': {'path': str(slf4j), 'sha256': digest(slf4j)},
        'sources': {name: digest(benchmark_sources / name) for name in source_names},
        'matrix': [dict(zip(('cell', 'scenario', 'worker_kind', 'carriers', 'lock_granularity'), cell)) for cell in CELLS],
        'mode_contract': {'stock': 'Unprofiled fixed-work headline measurements, all cells and repetitions',
                          'async': 'One separate matched-work sampler overhead control per selected cell; not headline',
                          'diagnostic': 'Separate 100000 measured / 20000 warmup operations; JFR monitor/park data, not headline'},
        'measurement_contract': 'Fresh sequential JVMs; fixed total prepared UPDATE operations, independent of worker/carrier count; '
            'in-memory SQLite; same native library, statements/connections through warmup and measurement; '
            'per-worker statements and rows; existing DB lock for outer-op; no SQL retries, HTTP, admission, disk or backup. '
            'Zero phase Java busy observations required. All stock runs precede profiled runs; all offline analysis follows all JVM measurements. '
            'Whole-process CPU normalized only after exact-work validation. Scheduler carrier properties do not control platform workers. '
            'CPU samples are not nanoseconds; inclusive and leaf stacks are separate. Park/monitor durations are not CPU or evidence of spinning. '
            'Async recording starts after warmup and stops before postchecks, with a small worker-creation/join fringe around the exact measured CPU window. '
            'Profile normalization uses completed operations, not an assertion that its recording window exactly equals the CPU delta window.',
    })
    classes = out / 'classes'
    classes.mkdir()
    if run([java.with_name('javac'), '-encoding', 'UTF-8', '-cp', os.pathsep.join([str(drivers['fork']), str(slf4j)]),
            '-d', classes, benchmark_sources / 'JdbcLockBenchmark.java', benchmark_sources / 'ProfileSummary.java'], out / 'javac.log'):
        raise RuntimeError('Benchmark compilation failed; see javac.log')
    save(out / 'benchmark-class-manifest.json', {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob('*.class'))})
    rows, analysis_jobs, analysis = [], [], []
    engine_identity, jdk_identity = None, None

    def cell_run(driver, cell, mode, repetition, order):
        nonlocal engine_identity, jdk_identity
        label, scenario, worker_kind, carriers, granularity = cell
        operations, warmup = (100_000, 20_000) if mode == 'diagnostic' else (args.operations, args.warmup_operations)
        kind = 'diagnostic' if mode == 'diagnostic' else 'performance'
        directory = out / f'{mode}-{label}-rep-{repetition}-{driver}'
        directory.mkdir()
        command = [java, '--enable-native-access=ALL-UNNAMED', '-Xms512m', '-Xmx512m',
                   f'-Djdk.virtualThreadScheduler.parallelism={carriers}', f'-Djdk.virtualThreadScheduler.maxPoolSize={carriers}',
                   f'-Dbenchmark.worker.kind={worker_kind}', f'-Dbenchmark.lock.granularity={granularity}',
                   f'-Dorg.sqlite.lib.path={natives[driver].parent}', f'-Dorg.sqlite.lib.name={library}', '-Xlog:library=debug']
        cp = [str(classes), str(drivers[driver]), str(slf4j)]
        if mode == 'async':
            command += [f'-agentpath:{ap_library}', f'-Dbenchmark.async.library={ap_library}']
            cp.append(str(profiler_jar))
        command += ['-cp', os.pathsep.join(cp), 'io.gateway.JdbcLockBenchmark', directory, scenario,
                    operations, warmup, args.timeout_seconds, kind]
        print('RUN ' + directory.name, flush=True)
        status = run(command, directory / 'stdout.log', timeout=args.timeout_seconds * 4 + 60)
        result = directory / 'result.json'
        try:
            row = json.loads(result.read_text()) if result.exists() else {}
            errors = validate(row, cell, operations, warmup, kind, driver)
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
        recordings = ['async-profile'] if mode == 'async' else ['warmup-monitors', 'measurement-monitors'] if mode == 'diagnostic' else []
        for name in recordings:
            recording = directory / f'{name}.jfr'
            if not recording.is_file() or recording.stat().st_size == 0:
                errors.append('Missing recording: ' + name)
            else:
                analysis_jobs.append((row, recording, directory / f'{name}-summary.json'))
        if errors:
            row.pop('cpu_ns_per_operation', None)
            row.pop('operations_per_second', None)
        row.update(driver=driver, cell=label, scenario=scenario, mode=mode, kind=kind, repetition=repetition,
                   configured_worker_kind=worker_kind, configured_carriers=carriers, configured_lock_granularity=granularity,
                   order=list(order), process_exit_code=status, outcome_passed=not errors, validation_errors=errors,
                   workload_classification='no-sql-busy-retry' if not errors else 'invalid',
                   native_library_sha256=native_hashes[driver], raw_result=str(result.relative_to(out)))
        save(directory / 'runner-result.json', row)
        rows.append(row)
        save(out / 'results.json', rows)
        print(f'  passed={not errors} errors={errors}', flush=True)

    for cell in CELLS:
        for rep in range(args.repetitions):
            offset = rep % len(args.variants)
            order = args.variants[offset:] + args.variants[:offset]
            for driver in order:
                cell_run(driver, cell, 'stock', rep + 1, order)
    for mode, selected in (('async', PROFILE_CELLS), ('diagnostic', DIAGNOSTIC_CELLS)):
        if mode == 'async' and args.skip_profiles:
            continue
        for index, cell in enumerate(c for c in CELLS if c[0] in selected):
            order = args.variants if index % 2 == 0 else list(reversed(args.variants))
            for driver in order:
                cell_run(driver, cell, mode, 1, order)
    # No analysis JVM competes with a measured JVM.
    for row, recording, summary_path in analysis_jobs:
        status = run([java, '-Xmx1g', '-cp', classes, 'io.gateway.ProfileSummary', recording, summary_path],
                     summary_path.with_suffix('.log'), timeout=180)
        try:
            profile = json.loads(summary_path.read_text()) if summary_path.is_file() else {}
            errors = profile_errors(profile, row['mode'])
        except (ValueError, TypeError, AttributeError) as failure:
            profile, errors = {}, ['Malformed profile summary: ' + str(failure)]
        if status:
            errors.append(f'ProfileSummary exited {status}')
        phase = 'warmup' if recording.name.startswith('warmup-') else 'measurement'
        completed = row.get(phase, {}).get('completed_operations', 0)
        item = {'recording': str(recording.relative_to(out)), 'summary': str(summary_path.relative_to(out)),
                'cell': row['cell'], 'driver': row['driver'], 'mode': row['mode'], 'phase': phase,
                'process_exit_code': status, 'outcome_passed': not errors and row['outcome_passed'],
                'validation_errors': errors, 'completed_operations': completed}
        if item['outcome_passed'] and row['mode'] == 'async':
            item['cpu_samples_per_completed_operation'] = profile['cpu']['samples'] / completed
            item['normalization'] = 'Raw CPU leaf/inclusive sample counts and allocation sampled byte weights in summary divide by completed_operations; inclusive frames overlap, do not sum. Wait duration is not CPU.'
        analysis.append(item)
        if errors:
            row['outcome_passed'] = False
            row['validation_errors'].extend(errors)
            save(recording.parent / 'runner-result.json', row)
        save(out / 'analysis.json', analysis)
    save(out / 'results.json', rows)
    summary = []
    for cell in CELLS:
        group = [r for r in rows if r['mode'] == 'stock' and r['cell'] == cell[0]]
        for item in comparison.summarize(group, args.variants, args.repetitions):
            if item['scenario'] == cell[1]:
                item.update(cell=cell[0], worker_kind=cell[2], carriers=cell[3], lock_granularity=cell[4], mode='stock')
                summary.append(item)
    save(out / 'summary.json', summary)
    overhead = []
    for row in (r for r in rows if r['mode'] == 'async'):
        baseline = next(s for s in summary if s['cell'] == row['cell'] and s['driver'] == row['driver'])
        item = {'cell': row['cell'], 'driver': row['driver'], 'profile_valid': row['outcome_passed'],
                'baseline_valid_runs': baseline['valid_runs'], 'baseline_noisy': baseline['noisy']}
        if row['outcome_passed'] and baseline['valid_runs'] == args.repetitions:
            for metric in ('cpu_ns_per_operation', 'operations_per_second'):
                item[metric + '_change_percent'] = (row[metric] / baseline[metric]['median'] - 1) * 100
        overhead.append(item)
    save(out / 'profile-overhead.json', overhead)
    expected = {'stock': len(CELLS) * args.repetitions * len(args.variants),
                'async': 0 if args.skip_profiles else len(PROFILE_CELLS) * len(args.variants),
                'diagnostic': len(DIAGNOSTIC_CELLS) * len(args.variants)}
    counts = {mode: sum(r['mode'] == mode for r in rows) for mode in expected}
    failed = any(not r['outcome_passed'] for r in rows + analysis) or counts != expected
    failed |= len(analysis) != expected['async'] + 2 * expected['diagnostic']
    save(out / 'validity.json', {'outcome_passed': not failed, 'expected_runs': expected, 'actual_runs': counts,
                               'expected_recordings': expected['async'] + 2 * expected['diagnostic'],
                               'analyzed_recordings': len(analysis), 'profiles_omitted_local_smoke': args.skip_profiles})
    fields = ['cell', 'scenario', 'driver', 'mode', 'repetition', 'configured_worker_kind', 'configured_carriers',
              'configured_lock_granularity', 'outcome_passed', 'operations', 'warmup_operations',
              'cpu_ns_per_operation', 'operations_per_second', 'native_library_sha256', 'raw_result', 'validation_errors']
    with (out / 'results.csv').open('w', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    report = ['# JDBC lock-cause controls', '',
              f'Scope: {"hosted" if hosted else "local smoke (not performance evidence)"}; async profiles {"OMITTED" if args.skip_profiles else "included"}.',
              'Fixed work; headline table contains only stock JVMs. CPU profiler and 100k/20k JFR diagnostics are separate.',
              'All scenarios require exact counters/integrity and zero Java SQL busy-retry observations.',
              'No automatic root-cause claim: parked duration is not CPU or proof of spinning; inspect CPU leaf/inclusive stacks and overhead controls.',
              'Noisy: fewer than two repeats, shortest measurement <5s, or CPU/throughput CV >5%.', '',
              '| Cell | Driver | Valid / runs | CPU ns/op min / median / max | CPU CV | Ops/s min / median / max | Ops/s CV | Noisy |',
              '|---|---|---:|---:|---:|---:|---:|---:|']
    for item in summary:
        metrics = []
        for name in ('cpu_ns_per_operation', 'operations_per_second'):
            metric = item.get(name)
            metrics.extend([' / '.join(f'{metric[k]:.2f}' for k in ('min', 'median', 'max')) if metric else 'invalid',
                            f'{metric["cv"]:.2%}' if metric and metric['cv'] is not None else 'n/a'])
        report.append(f'| {item["cell"]} | {item["driver"]} | {item["valid_runs"]} / {item["runs"]} | '
                      + ' | '.join(metrics) + f' | {item["noisy"]} |')
    report.extend(['', f'Overall validation: {"FAIL" if failed else "PASS"}. Runs by mode: {counts}.',
                   'See environment.json, results.json, analysis.json, profile-overhead.json and validity.json. All raw JFRs, sources and command logs are retained.'])
    (out / 'summary.md').write_text('\n'.join(report) + '\n')
    if failed:
        raise SystemExit('Invalid or incomplete lock-cause evidence; inspect runner-result.json and analysis.json')


if __name__ == '__main__':
    main()
