#!/usr/bin/env python3
"""Wrap only the complete prepared executeLargeUpdate body in its existing DB lock.

Run after build-lock-variants.py. No production source/native file is modified.
"""
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('lock_builder', Path(__file__).with_name('build-lock-variants.py'))
builder = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(builder)
SOURCE = 'org/sqlite/jdbc3/JDBC3PreparedStatement.java'
SIGNATURE = 'public long executeLargeUpdate() throws SQLException {'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    output = ROOT / 'target/ci/lock-scope-variants'
    if output.exists():
        raise SystemExit('Refusing to overwrite ' + str(output))
    prior = json.loads((ROOT / 'target/ci/lock-variants/provenance.json').read_text())
    output.mkdir(parents=True)
    provenance = {'candidate': 'Existing DB guard spans complete JDBC3PreparedStatement.executeLargeUpdate body, including precondition checks; executeUpdate delegates. No other entrypoint widened.',
                  'builder_sha256': digest(Path(__file__)), 'variants': {}}
    javac = Path(os.environ['JAVA_HOME']) / 'bin/javac'
    for base_name in ('fork', 'reentrant'):
        base = ROOT if base_name == 'fork' else ROOT / 'target/ci/lock-variants/reentrant'
        source = base / 'src/main/java' / SOURCE
        expected = prior['base_sources'][SOURCE] if base_name == 'fork' else prior['variants']['reentrant']['generated_sources'][SOURCE]
        if digest(source) != expected:
            raise RuntimeError('Unreviewed base source: ' + str(source))
        text = source.read_text()
        if text.count(SIGNATURE) != 1:
            raise RuntimeError('Unexpected prepared update method')
        opening = text.index(SIGNATURE) + len(SIGNATURE) - 1
        tokens = builder.tokens(text)
        index = next(i for i, (_, start, _) in enumerate(tokens) if start == opening)
        closing = tokens[builder.pairs(tokens)[index]][1]
        body = text[opening + 1:closing]
        indented = ''.join('    ' + line if line.strip() else line for line in body.splitlines(keepends=True))
        if base_name == 'fork':
            replacement = '\n        synchronized (conn.getDatabase()) {' + indented + '    }\n    '
        else:
            replacement = ('\n        java.util.concurrent.locks.ReentrantLock operationLock = conn.getDatabase().connectionLock;'
                           + '\n        operationLock.lock();\n        try {' + indented
                           + '    } finally {\n            operationLock.unlock();\n        }\n    ')
        changed = text[:opening + 1] + replacement + text[closing:]
        name = base_name + '-outer'
        tree = output / name
        classes = tree / 'target/classes'
        shutil.copytree(base / 'target/classes', classes)
        generated = tree / 'src/main/java' / SOURCE
        generated.parent.mkdir(parents=True)
        generated.write_text(changed)
        diff = ''.join(difflib.unified_diff(text.splitlines(keepends=True), changed.splitlines(keepends=True),
                                          fromfile='a/' + SOURCE, tofile='b/' + SOURCE))
        (tree / 'source.diff').write_text(diff)
        command = [str(javac), '--release', '8', '-g', '-encoding', 'UTF-8', '-cp', str(base / 'target/classes'),
                   '-d', str(classes), str(generated)]
        with (tree / 'compile.log').open('w') as log:
            subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
        natives = {str(p.relative_to(classes)): digest(p) for p in (classes / 'org/sqlite/native').rglob('*')
                   if p.is_file() and p.suffix in ('.so', '.dylib', '.dll')}
        if any(digest(base / 'target/classes' / path) != value for path, value in natives.items()):
            raise RuntimeError('JNI binary changed')
        provenance['variants'][name] = {'base': base_name, 'base_source_sha256': expected,
            'generated_source_sha256': digest(generated), 'diff_sha256': digest(tree / 'source.diff'),
            'compile_command': command, 'class_files': {str(p.relative_to(classes)): digest(p) for p in classes.rglob('*.class')},
            'native_libraries': natives}
        print(name + ': complete prepared update guard compiled; native unchanged', flush=True)
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


if __name__ == '__main__':
    main()
