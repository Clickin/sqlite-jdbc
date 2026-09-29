#!/usr/bin/env python3
"""Compare public observations, not timing; keep scope differences separate from primitive changes."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('run-lock-comparison.py'))
comparison = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(comparison)
save, digest = comparison.save, comparison.digest
VARIANTS = ('fork', 'fork-outer', 'reentrant', 'reentrant-outer')
SOURCES = ('ProfileSummary.java', 'LockCorrectness.java', 'LockScopeSafety.java',
           'ScopeLifecycleCases.java', 'ScopeTransactionCases.java', 'ScopeExtensionCases.java')
REGRESSIONS = ','.join(('PrepStmtTest', 'PreparedStatementThreadTest', 'StatementTest', 'ConnectionTest',
    'TransactionTest', 'ControlTransactionTest', 'SavepointTest', 'BusyPolicyTest', 'BusyHandlerTest',
    'UDFTest', 'UDFCustomErrorTest', 'ListenerTest', 'ProgressHandlerTest', 'BackupBusyWaitTest',
    'BackupSessionLifecycleTest', 'BackupTest', 'ResultSetTest', 'ResultSetWithoutResultsTest',
    'DBMetaDataTest', 'SQLiteConnectionPoolDataSourceTest', 'InsertQueryTest', 'ReadUncommittedTest',
    'SerializeTest', 'SQLiteConfigTest'))


def execute(command, log, timeout, java=None):
    save(log.with_suffix('.command.json'), list(map(str, command)))
    with log.open('w') as stream:
        process = subprocess.Popen(list(map(str, command)), stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if java:
                with log.with_name('timeout-thread-dump.txt').open('w') as dump:
                    try:
                        subprocess.run([str(java.with_name('jcmd')), str(process.pid), 'Thread.print', '-l'],
                                       stdout=dump, stderr=subprocess.STDOUT, timeout=10)
                    except subprocess.TimeoutExpired:
                        dump.write('jcmd timed out\n')
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            stream.write('\nPROCESS TIMEOUT\n')
            return 124


def junit_report(tree):
    suites, failed, skipped = [], [], []
    for path in sorted(tree.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append({key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')})
        for test in suite.findall('testcase'):
            name = test.get('classname', '') + '#' + test.get('name', '')
            if test.find('failure') is not None or test.find('error') is not None:
                failed.append(name)
            if test.find('skipped') is not None:
                skipped.append(name)
    return {'suites': len(suites), **{key: sum(s[key] for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')},
            'failed_tests': failed, 'skipped_tests': skipped}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/vt-wait-evidence/ci-lock-safety')
    parser.add_argument('--repetitions', type=int, default=2)
    parser.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument('--cases', nargs='+', help='Optional local smoke group/scenario filter')
    parser.add_argument('--skip-regressions', action='store_true', help='Local scenario smoke only')
    parser.add_argument('--fault-library', type=Path)
    args = parser.parse_args()
    if args.repetitions < 1 or len(args.variants) != len(set(args.variants)):
        parser.error('Positive repetitions and unique variants required')
    hosted = os.environ.get('GITHUB_ACTIONS') == 'true'
    if hosted and (args.cases or args.skip_regressions or set(args.variants) != set(VARIANTS) or not args.fault_library):
        parser.error('Hosted safety review requires all variants/cases and normal plus fault regressions')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    slf4j = Path.home() / '.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar'
    scope = ROOT / 'target/ci/lock-scope-variants'
    drivers = {'fork': ROOT / 'target/classes', 'reentrant': ROOT / 'target/ci/lock-variants/reentrant/target/classes',
               **{name: scope / name / 'target/classes' for name in ('fork-outer', 'reentrant-outer')}}
    os_name, library = {'Darwin': ('Mac', 'libsqlitejdbc.dylib'), 'Linux': ('Linux', 'libsqlitejdbc.so')}[platform.system()]
    arch = {'arm64': 'aarch64', 'amd64': 'x86_64'}.get(platform.machine().lower(), platform.machine().lower())
    native = drivers['fork'] / 'org/sqlite/native' / os_name / arch / library
    identity = {}
    for name in args.variants:
        manifest = {str(p.relative_to(drivers[name])): digest(p) for p in sorted(drivers[name].rglob('*.class'))}
        save(out / (name + '-classes.json'), manifest)
        identity[name] = {'classes': str(drivers[name]), 'class_manifest_sha256': digest(out / (name + '-classes.json'))}
    save(out / 'environment.json', {'purpose': 'Scope behavioral equivalence gate, not a throughput test',
        'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'java': subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT, text=True),
        'native_library': str(native), 'native_sha256': digest(native), 'variants': identity,
        'fault_library': str(args.fault_library.resolve()) if args.fault_library else None,
        'fault_sha256': digest(args.fault_library.resolve()) if args.fault_library else None,
        'scope_provenance': json.loads((scope / 'provenance.json').read_text()),
        'test_sources': {str(p.relative_to(ROOT)): digest(p) for p in sorted((ROOT / 'src/test').rglob('*')) if p.is_file()},
        'case_sources': {name: digest(ROOT / 'scripts/benchmark' / name) for name in SOURCES},
        'runner_sha256': digest(Path(__file__))})
    classes = out / 'classes'
    classes.mkdir()
    if execute([java.with_name('javac'), '-encoding', 'UTF-8', '-cp', os.pathsep.join([str(drivers['fork']), str(slf4j)]),
                '-d', classes, *[ROOT / 'scripts/benchmark' / name for name in SOURCES]], out / 'javac.log', 120):
        raise RuntimeError('Safety case compilation failed')

    def command(variant):
        return [java, '--enable-native-access=ALL-UNNAMED', '-Djdk.virtualThreadScheduler.parallelism=4',
            '-Djdk.virtualThreadScheduler.maxPoolSize=4', f'-Dorg.sqlite.lib.path={native.parent}',
            f'-Dorg.sqlite.lib.name={library}', '-cp', os.pathsep.join([str(classes), str(drivers[variant]), str(slf4j)]),
            'io.gateway.LockScopeSafety']

    cases = json.loads(subprocess.check_output([*command(args.variants[0]), '--list'], text=True))
    selected = [(group, case) for group, names in sorted(cases.items()) for case in names
                if args.cases is None or group + '/' + case in args.cases]
    if args.cases and {g + '/' + c for g, c in selected} != set(args.cases):
        raise RuntimeError('Unknown scenario filter')
    rows = []
    for group, case in selected:
        for rep in range(1, args.repetitions + 1):
            for variant in args.variants:
                directory = out / f'{group}-{case}-rep-{rep}-{variant}'
                directory.mkdir()
                status = execute([*command(variant), group, case, directory], directory / 'stdout.log', 45, java)
                result = directory / 'result.json'
                row = json.loads(result.read_text()) if result.exists() else {'observation_valid': False, 'error': 'No complete result'}
                row.update(group=group, scenario=case, repetition=rep, variant=variant, process_exit_code=status,
                           observation_valid=row.get('observation_valid', False) and status == 0,
                           raw_result=str(result.relative_to(out)))
                rows.append(row)
                save(directory / 'runner-result.json', row)
                save(out / 'observations.json', rows)
                print(f'OBSERVE {group}/{case} {variant} rep{rep}: valid={row["observation_valid"]}', flush=True)
    differences, unstable = [], []
    for group, case in selected:
        by_variant = {}
        for variant in args.variants:
            current = [r for r in rows if (r['group'], r['scenario'], r['variant']) == (group, case, variant)]
            if len(current) != args.repetitions or not all(r['observation_valid'] for r in current):
                continue
            values = {json.dumps(r['observation'], sort_keys=True) for r in current}
            if len(values) != 1:
                unstable.append({'group': group, 'scenario': case, 'variant': variant, 'observations': [json.loads(v) for v in sorted(values)]})
            else:
                by_variant[variant] = current[0]['observation']
        for before, after, axis in (('fork', 'fork-outer', 'scope'), ('reentrant', 'reentrant-outer', 'scope'), ('fork', 'reentrant', 'primitive')):
            if before in by_variant and after in by_variant and by_variant[before] != by_variant[after]:
                differences.append({'group': group, 'scenario': case, 'axis': axis, 'before': before, 'after': after,
                                    'before_observation': by_variant[before], 'after_observation': by_variant[after]})
    save(out / 'differences.json', differences)
    save(out / 'unstable-observations.json', unstable)

    regressions = []
    if not args.skip_regressions:
        mvn = shutil.which('mvn')
        if not mvn:
            raise RuntimeError('Maven required for existing behavioral regressions')
        for variant in args.variants:
            tree = out / 'regression-work' / variant
            tree.mkdir(parents=True)
            shutil.copy2(ROOT / 'pom.xml', tree / 'pom.xml')
            shutil.copytree(ROOT / 'src/test', tree / 'src/test')
            shutil.copytree(drivers[variant], tree / 'target/classes')
            report_base = out / 'regressions' / variant
            report_base.mkdir(parents=True)
            for stage, binary, tests in [('normal', native, REGRESSIONS),
                    *([('fault', args.fault_library.resolve(), 'BackupFaultInjectionTest,ControlTransactionTest#t12_*')] if args.fault_library else [])]:
                directory = report_base / stage
                directory.mkdir()
                goals = (['org.apache.maven.plugins:maven-resources-plugin:3.3.1:testResources',
                          'org.apache.maven.plugins:maven-compiler-plugin:3.16.0:testCompile'] if stage == 'normal' else [])
                args_line = f'--enable-native-access=ALL-UNNAMED -Dorg.sqlite.lib.path={binary.parent} -Dorg.sqlite.lib.name={binary.name}'
                cmd = [mvn, '-f', tree / 'pom.xml', '--batch-mode', '--no-transfer-progress', '-Dmaven.compiler.release=8',
                    '-Dmaven.compiler.fork=true', f'-Dmaven.compiler.executable={java.with_name("javac")}',
                    f'-Dtest={tests}', f'-DargLine={args_line}', *goals, 'surefire:test']
                status = execute(cmd, directory / 'maven.log', 600)
                reports = tree / 'target/surefire-reports'
                counts = junit_report(reports) if reports.exists() else {'suites': 0, 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'failed_tests': [], 'skipped_tests': []}
                if reports.exists():
                    shutil.copytree(reports, directory / 'surefire-reports')
                    shutil.rmtree(reports)  # Copied evidence; keep the next stage's counts separate.
                counts.update(variant=variant, stage=stage, process_exit_code=status, native_sha256=digest(binary),
                    valid=status == 0 and counts['tests'] > 0 and counts['failures'] == 0 and counts['errors'] == 0
                    and (stage != 'fault' or (counts['tests'] >= 5 and counts['skipped'] == 0)))
                regressions.append(counts)
                save(out / 'regressions.json', regressions)
                print(f'REGRESS {variant} {stage}: {counts}', flush=True)
    scope_changes = [d for d in differences if d['axis'] == 'scope']
    observed_cycles = [r for r in rows if r.get('observation', {}).get('deadlock_detected') is True]
    complete = len(rows) == len(selected) * len(args.variants) * args.repetitions and all(r['observation_valid'] for r in rows)
    regression_passed = bool(regressions) and all(r['valid'] for r in regressions)
    full_review = (args.cases is None and set(args.variants) == set(VARIANTS) and args.repetitions >= 2
                   and args.fault_library is not None and len(regressions) == 2 * len(VARIANTS))
    adoption = full_review and complete and not differences and not unstable and not observed_cycles and regression_passed
    summary = {'observations_complete': complete, 'observation_runs': len(rows), 'scenario_count': len(selected),
        'scope_changes': len(scope_changes), 'primitive_changes': sum(d['axis'] == 'primitive' for d in differences),
        'unstable_cells': len(unstable), 'detected_cycle_runs': len(observed_cycles),
        'regressions_executed': not args.skip_regressions, 'regressions_passed': regression_passed,
        'full_review_executed': full_review,
        'adoption_gate_passed': adoption,
        'semantics': 'Observable scope changes block an unqualified equivalence claim; not every ordering difference is a JDBC specification violation. Primitive changes and pre-existing failures are separate findings.'}
    save(out / 'summary.json', summary)
    (out / 'summary.md').write_text('# Lock-scope safety review\n\n' +
        f'Observation runs: {len(rows)}; complete: {complete}.\n\n' +
        f'Scope changes: {len(scope_changes)}; primitive changes: {summary["primitive_changes"]}; unstable cells: {len(unstable)}; cycle runs: {len(observed_cycles)}.\n\n' +
        f'Existing regressions passed: {regression_passed}. **Unqualified adoption gate: {"PASS" if adoption else "BLOCKED"}.**\n\n' +
        'See differences.json, observations.json, regressions.json and raw thread dumps. Behavioral change is not automatically a JDBC specification violation.\n')
    print(json.dumps(summary, indent=2), flush=True)
    if not adoption:
        raise SystemExit('Lock-scope adoption blocked or verification incomplete; inspect differential findings')


if __name__ == '__main__':
    main()
