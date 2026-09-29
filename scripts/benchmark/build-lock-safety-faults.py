#!/usr/bin/env python3
"""Build the repository's existing fault JNI without replacing the normal JNI resource."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
identity = json.loads((root / 'target/vt-wait-evidence/ci-benchmark/build-identity.json').read_text())
shared_object = Path(identity['sqlite_object']['path'])
upstream = root / 'target/ci/upstream'
amalgamation = subprocess.check_output(['sh', str(upstream / 'amalgamation_version.sh'), '3.53.4'], text=True).strip()
shared_source = upstream / ('target/sqlite-amalgamation-' + amalgamation)
out = root / 'target/ci/lock-safety-faults'
out.mkdir(parents=True, exist_ok=False)
normal = root / 'target/classes/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so'
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
before = digest(normal)
java = Path(os.environ['JAVA_HOME']) / 'bin/javac'
command = ['make', '-j1', 'SHELL=/bin/bash', '.SHELLFLAGS=-eu -o pipefail -c',
    'OS_NAME=Linux', 'OS_ARCH=x86_64', 'CC=gcc-13', f'JAVAC={java} --release 8',
    f'JAVA_CLASSPATH={Path.home()}/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar',
    'OSINFO_PROG=target/classes/org/sqlite/util/OSInfo.class', '-o', str(shared_object), '-o', str(shared_source / 'sqlite3.h'),
    f'SQLITE_OBJ={shared_object}', f'SQLITE_SOURCE={shared_source}', f'SQLITE_HEADER={shared_source}/sqlite3.h',
    f'NATIVE_DLL={out}/libsqlitejdbc.so', f'NATIVE_TARGET_DIR={out}', 'native-faults']
(out / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
with (out / 'build.log').open('w') as log:
    subprocess.run(command, cwd=root, check=True, stdout=log, stderr=subprocess.STDOUT)
assert digest(normal) == before, 'Normal JNI resource changed'
assert digest(shared_object) == identity['sqlite_object']['sha256'], 'Shared SQLite object changed'
(out / 'identity.json').write_text(json.dumps({'normal_native_sha256': before,
    'fault_native_sha256': digest(out / 'libsqlitejdbc.so'), 'sqlite_object_sha256': digest(shared_object),
    'native_source_sha256': digest(root / 'src/main/java/org/sqlite/core/NativeDB.c'),
    'fault_define': 'SQLITEJDBC_TEST_FAULTS', 'normal_resource_unchanged': True}, indent=2) + '\n')
print('Fault JNI built from existing fault target; normal resource and common engine unchanged')
