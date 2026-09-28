#!/usr/bin/env python3
"""Build fixed-source lock experiments, sharing the already-built fork JNI binary.

Run after build-ci.sh. Nothing in src/ is edited and no native code is rebuilt.
NativeDB stays the JNI declaring class; a final subclass supplies Java lock guards.
This changes direct NativeDB construction and is deliberately not a release artifact.
"""
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/main/java"
OUTPUT = ROOT / "target/ci/lock-variants"
# Fail closed: this experiment was reviewed against this complete Java source tree.
EXPECTED_SOURCES = "00b72196db18dcb58e77febd2273153907ac4baf3e110a0260e60536f8ff56e6"
DB = "org/sqlite/core/DB.java"
NATIVE = "org/sqlite/core/NativeDB.java"
CONNECTION = "org/sqlite/SQLiteConnection.java"
JDBC_CONNECTION = "org/sqlite/jdbc3/JDBC3Connection.java"
INDEPENDENT = {
    "org/sqlite/Function.java",
    "org/sqlite/SQLiteJDBCLoader.java",
    "org/sqlite/core/CoreDatabaseMetaData.java",
    "org/sqlite/jdbc3/JDBC3DatabaseMetaData.java",
    "org/sqlite/date/FastDateParser.java",
}
# The lexer excludes strings, character literals, and comments before matching braces.
LEX = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|[^\s]')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tokens(source):
    return [(m.group(), m.start(), m.end()) for m in LEX.finditer(source)
            if not m.group().startswith(('//', '/*', '"', "'"))]


def pairs(ts):
    stack, result = [], {}
    for i, (text, _, _) in enumerate(ts):
        if text in ('{', '('):
            stack.append(i)
        elif text in ('}', ')'):
            if not stack or ts[stack[-1]][0] != {'}': '{', ')': '('}[text]:
                raise ValueError("Unbalanced Java delimiters")
            result[stack.pop()] = i
    if stack:
        raise ValueError("Unclosed Java delimiter")
    return result


def apply(source, edits):
    end = len(source)
    for start, stop, replacement in sorted(edits, reverse=True):
        if stop > end:
            raise ValueError("Overlapping source transformations")
        source = source[:start] + replacement + source[stop:]
        end = start
    return source


def native_override(source, ts, i, terminal):
    start = source.rfind('\n', 0, ts[i][1]) + 1
    declaration = source[start:ts[terminal][1]].strip()
    if re.search(r'\b(private|static|final)\b', declaration):
        raise ValueError("Native method cannot be overridden: " + declaration)
    opening = next(j for j in range(i, terminal) if ts[j][0] == '(')
    closing = next(j for j in range(opening, terminal) if ts[j][0] == ')')
    name = ts[opening - 1][0]
    args = source[ts[opening][2]:ts[closing][1]].strip()
    arguments = ', '.join(part.split()[-1] for part in args.split(',')) if args else ''
    signature = re.sub(r'\b(synchronized|native)\s+', '', declaration)
    returns = '' if ts[opening - 2][0] == 'void' else 'return '
    wrapper = ("    @Override\n    " + signature + " {\n"
               "        connectionLock.lock();\n        try {\n"
               f"            {returns}super.{name}({arguments});\n"
               "        } finally {\n            connectionLock.unlock();\n        }\n    }\n")
    return name, wrapper


def transform(source, path, variant):
    ts = tokens(source)
    ends = pairs(ts)
    edits, wrappers, inventory = [], [], []
    for i, (word, start, stop) in enumerate(ts):
        if word != 'synchronized':
            continue
        line = source.count('\n', 0, start) + 1
        indent = re.match(r'[ \t]*', source[source.rfind('\n', 0, start) + 1:]).group()
        if ts[i + 1][0] == '(':
            last = ends[i + 1]
            expression = ''.join(t[0] for t in ts[i + 2:last])
            body = last + 1
            if ts[body][0] != '{':
                raise ValueError(f"Unexpected synchronized block: {path}:{line}")
            target = {'db': 'db', 'conn': 'conn.getDatabase()',
                      'getDatabase()': 'getDatabase()',
                      'conn.getDatabase()': 'conn.getDatabase()'}.get(expression)
            if expression == 'this' and path in (DB, NATIVE):
                target = 'this'
            if expression == 'this' and path == JDBC_CONNECTION:
                target = 'getDatabase()'
            if target is None:
                if path not in INDEPENDENT:
                    raise ValueError(f"Unreviewed monitor {path}:{line}: {expression}")
                action = 'retained-independent-monitor'
            elif variant == 'flat':
                if expression == 'conn':
                    # All three scopes are inside withConnectionTimeout's existing DB guard.
                    edits.append((start, ts[body][1], ''))
                    action = 'remove-redundant-connection-monitor'
                else:
                    action = 'retained-monitor'
            else:
                lock = target + '.connectionLock'
                edits.append((start, ts[body][2],
                              '{\n' + indent + '    ' + lock + '.lock();\n' + indent + '    try {'))
                close = ts[ends[body]]
                edits.append((close[1], close[2],
                              '} finally {\n' + indent + '        ' + lock + '.unlock();\n'
                              + indent + '    }\n' + indent + '}'))
                action = 'db-reentrant-block'
            inventory.append({'line': line, 'monitor': expression, 'action': action})
            continue

        terminal = next(j for j in range(i + 1, len(ts)) if ts[j][0] in ('{', ';'))
        native = any(t[0] == 'native' for t in ts[i + 1:terminal])
        if path not in (DB, NATIVE):
            if path not in INDEPENDENT:
                raise ValueError(f"Unreviewed synchronized method: {path}:{line}")
            action = 'retained-independent-method'
        elif variant == 'flat':
            action = 'retained-method'
        else:
            header = source[source.rfind('\n', 0, start) + 1:ts[terminal][1]]
            if re.search(r'\bstatic\b', header):
                raise ValueError("Unexpected class monitor: " + header)
            edits.append((start, stop + (source[stop:stop + 1] == ' '), ''))
            if native:
                if path != NATIVE or ts[terminal][0] != ';':
                    raise ValueError("Unexpected native declaration")
                name, wrapper = native_override(source, ts, i, terminal)
                wrappers.append((name, wrapper))
                action = 'jni-subclass-override:' + name
            else:
                if ts[terminal][0] != '{':
                    raise ValueError("Synchronized method has no body")
                edits.append((ts[terminal][2], ts[terminal][2],
                              '\n' + indent + '    connectionLock.lock();\n' + indent + '    try {'))
                close = ts[ends[terminal]]
                edits.append((close[1], close[1],
                              '} finally {\n' + indent + '        connectionLock.unlock();\n'
                              + indent + '    }\n' + indent))
                action = 'db-reentrant-method'
        inventory.append({'line': line, 'method': True, 'native': native, 'action': action})
    return apply(source, edits), wrappers, inventory


def native_signatures(source):
    ts = tokens(source)
    signatures = []
    for i, token in enumerate(ts):
        if token[0] == 'native':
            last = next(j for j in range(i, len(ts)) if ts[j][0] == ';')
            signatures.append(' '.join(t[0] for t in ts[i:last]))
    return signatures


def main():
    base = ROOT / 'target/classes'
    if not (base / 'org/sqlite/JDBC.class').is_file():
        raise SystemExit('Run build-ci.sh first: missing target/classes')
    if OUTPUT.exists():
        raise SystemExit(f'Refusing to overwrite previous experiment: {OUTPUT}')
    sources = {str(p.relative_to(SOURCE)): p.read_text() for p in sorted(SOURCE.rglob('*.java'))}
    manifest = {name: digest(SOURCE / name) for name in sources}
    tree_hash = hashlib.sha256(''.join(f'{p} {h}\n' for p, h in manifest.items()).encode()).hexdigest()
    if tree_hash != EXPECTED_SOURCES:
        raise SystemExit(f'Unreviewed Java source tree: {tree_hash}; expected {EXPECTED_SOURCES}')
    java_home = Path(os.environ['JAVA_HOME'])
    javac = str(java_home / 'bin/javac')
    slf4j = Path.home() / '.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar'
    if not slf4j.is_file():
        raise SystemExit('Run build-ci.sh first: missing pinned slf4j-api compile dependency')
    libraries = {str(p.relative_to(base)): digest(p)
                 for p in sorted((base / 'org/sqlite/native').rglob('*'))
                 if p.is_file() and p.suffix in ('.so', '.dylib', '.dll')}
    if not libraries:
        raise SystemExit('No existing fork JNI library available')
    OUTPUT.mkdir(parents=True)
    provenance = {
        'java_source_tree_sha256': tree_hash,
        'base_sources': manifest,
        'builder_sha256': digest(Path(__file__)),
        'java_release': 8,
        'javac': subprocess.check_output([javac, '-version'], text=True, stderr=subprocess.STDOUT).strip(),
        'compile_dependency': {'path': str(slf4j), 'sha256': digest(slf4j)},
        'shared_native_libraries': libraries,
        'variants': {},
        'limitations': [
            'Experimental source variants, not a supported binary/source-compatible driver release.',
            'Reentrant NativeDB is abstract; JDBC constructs final LockedNativeDB. Direct new NativeDB is unavailable.',
            'The JNI bridge subclass can affect devirtualization/inlining independently of lock choice.',
            'External synchronized(DB/Connection) no longer coordinates with the reentrant driver.',
            'Existing unsynchronized cancellation and private backup JNI calls retain their original contract; backup calls run within migrated outer guards.',
            'Function, metadata, loader, and date-cache monitors are independent and deliberately retained.',
        ],
    }
    for variant in ('flat', 'reentrant'):
        tree = OUTPUT / variant
        classes = tree / 'target/classes'
        shutil.copytree(base, classes)
        generated, inventory, wrappers = {}, {}, []
        for name, source in sources.items():
            text, additions, locks = transform(source, name, variant)
            generated[name] = text
            wrappers.extend(additions)
            if locks:
                inventory[name] = locks
        if variant == 'reentrant':
            generated[DB] = generated[DB].replace(
                'public abstract class DB implements Codes {',
                'public abstract class DB implements Codes {\n'
                '    public final java.util.concurrent.locks.ReentrantLock connectionLock =\n'
                '            new java.util.concurrent.locks.ReentrantLock(false);', 1)
            generated[NATIVE] = generated[NATIVE].replace('public final class NativeDB extends DB {',
                                                        'public abstract class NativeDB extends DB {', 1)
            generated[CONNECTION] = generated[CONNECTION].replace(
                'new NativeDB(url, fileName, config)',
                'new org.sqlite.core.LockedNativeDB(url, fileName, config)', 1)
            imports = '\n'.join(re.findall(r'^import .*;', sources[NATIVE], re.MULTILINE))
            generated['org/sqlite/core/LockedNativeDB.java'] = (
                'package org.sqlite.core;\n\n' + imports + '\n\n'
                '/** Experimental Java guards; super calls retain NativeDB JNI symbol resolution. */\n'
                'public final class LockedNativeDB extends NativeDB {\n'
                '    public LockedNativeDB(String url, String fileName, SQLiteConfig config) throws SQLException {\n'
                '        super(url, fileName, config);\n    }\n\n'
                + '\n'.join(wrapper for _, wrapper in wrappers) + '}\n')
            if not wrappers or native_signatures(generated[NATIVE]) != native_signatures(sources[NATIVE]):
                raise ValueError('JNI declarations changed')
            for name, text in generated.items():
                if name not in INDEPENDENT and any(t[0] == 'synchronized' for t in tokens(text)):
                    raise ValueError('Residual driver monitor: ' + name)
        comment = 'synchronized(conn.getDatabase())' if variant == 'flat' else 'the DB connectionLock guard'
        generated['org/sqlite/core/CoreStatement.java'] = generated['org/sqlite/core/CoreStatement.java'].replace(
            'a synchronized(connection)\n     * block', comment)
        diff = []
        for name, text in generated.items():
            path = tree / 'src/main/java' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            if text != sources.get(name):
                diff.extend(difflib.unified_diff(sources.get(name, '').splitlines(keepends=True),
                                                text.splitlines(keepends=True),
                                                fromfile='a/src/main/java/' + name,
                                                tofile='b/src/main/java/' + name))
        (tree / 'source.diff').write_text(''.join(diff))
        command = [javac, '--release', '8', '-g', '-encoding', 'UTF-8',
                   '-cp', os.pathsep.join([str(base), str(slf4j)]), '-d', str(classes)]
        command += [str(tree / 'src/main/java' / name) for name in generated]
        print(' '.join(command), flush=True)
        subprocess.run(command, check=True, cwd=ROOT)
        for name, expected in libraries.items():
            if digest(classes / name) != expected or digest(base / name) != expected:
                raise ValueError('Native binary changed: ' + name)
        for path in classes.rglob('*.class'):
            header = path.read_bytes()[:8]
            if header[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(header[6:8], 'big') != 52:
                raise ValueError('Not a Java 8 class: ' + str(path))
        details = {
            'classes': str(classes.relative_to(ROOT)),
            'compile_command': command,
            'source_diff_sha256': digest(tree / 'source.diff'),
            'generated_sources': {name: digest(tree / 'src/main/java' / name) for name in generated},
            'class_files': {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob('*.class'))},
            'lock_inventory': inventory,
            'native_overrides': [name for name, _ in wrappers],
            'shared_native_libraries': libraries,
        }
        (tree / 'provenance.json').write_text(json.dumps(details, indent=2) + '\n')
        provenance['variants'][variant] = details
        print(f'{variant}: {sum(len(rows) for rows in inventory.values())} lock sites inventoried; '
              f'{len(wrappers)} native overrides; JNI binary unchanged', flush=True)
    (OUTPUT / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


if __name__ == '__main__':
    main()
