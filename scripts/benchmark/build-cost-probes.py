#!/usr/bin/env python3
"""Build measurement-only native libraries from build-ci.sh outputs (Linux/GCC 13).

No production file is rewritten. Exact compiler/link argv come from the original
build logs; both variants reuse the original upstream SQLite object. Run after
build-ci.sh. --self-test exercises injection/rejection without a native build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shlex
import subprocess


JNI = "Java_org_sqlite_core_NativeDB_"
# Exact, inspected definition prefixes: mismatched signatures fail, never guess.
FUNCTIONS = [
    ("gethandle", "gethandle", "static sqlite3 * gethandle(JNIEnv *env, jobject nativeDB)\n{", False),
    ("checkBackupAccess", "checkBackupAccess", "static int checkBackupAccess(JNIEnv *env, jobject nativeDB)\n{", True),
    ("readBusyTimeout", "readBusyTimeout", "static int readBusyTimeout(sqlite3 *db) {", True),
    ("attemptNoWaitBusy", JNI + "attemptNoWaitBusy", "JNIEXPORT jlong JNICALL " + JNI + "attemptNoWaitBusy(\n    JNIEnv *env, jobject this, jlong stmtPtr, jboolean autoCommitProbe, jint firstAttemptTimeout)\n{", True),
    ("prepare_utf8", JNI + "prepare_1utf8", "JNIEXPORT jlong JNICALL " + JNI + "prepare_1utf8(\n        JNIEnv *env, jobject this, jbyteArray sql)\n{", False),
    ("step", JNI + "step", "JNIEXPORT jint JNICALL " + JNI + "step(\n        JNIEnv *env, jobject this, jlong stmt)\n{", False),
    ("column_name_utf8", JNI + "column_1name_1utf8", "JNIEXPORT jobject JNICALL " + JNI + "column_1name_1utf8(\n        JNIEnv *env, jobject this, jlong stmt, jint col)\n{", False),
    ("bind_parameter_count", JNI + "bind_1parameter_1count", "JNIEXPORT jint JNICALL " + JNI + "bind_1parameter_1count(\n        JNIEnv *env, jobject this, jlong stmt)\n{", False),
    ("_exec_utf8", JNI + "_1exec_1utf8", "JNIEXPORT jint JNICALL " + JNI + "_1exec_1utf8(\n        JNIEnv *env, jobject this, jbyteArray sql)\n{", False),
    ("finalize", JNI + "finalize", "JNIEXPORT jint JNICALL " + JNI + "finalize(\n        JNIEnv *env, jobject this, jlong stmt)\n{", False),
    ("column_text_utf8", JNI + "column_1text_1utf8", "JNIEXPORT jobject JNICALL " + JNI + "column_1text_1utf8(\n        JNIEnv *env, jobject this, jlong stmt, jint col)\n{", False),
]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inject(source, variant):
    require(variant in ("xerial", "fork"), f"Unknown variant: {variant}")
    categories = []
    modified = source
    for index, (name, symbol, signature, fork_only) in enumerate(FUNCTIONS):
        expected = not fork_only or variant == "fork"
        count = source.count(signature)
        require(count == int(expected), f"{variant}: expected {int(expected)} exact definitions of {symbol}, got {count}")
        if not expected:
            require(symbol not in source, f"{variant}: unexpected {symbol}; inspect its signature")
        if expected:
            modified = modified.replace(signature, signature + f"\n    CP_SCOPE({index});", 1)
        categories.append({"name": name, "symbol": symbol, "available": expected,
                           "reason": "exact definition instrumented" if expected else "not present in upstream source"})
    # These wrapper probes surround JNI-to-engine calls only. No SQLite source or
    # engine object is changed, and callback types/return codes remain intact.
    wrappers = []
    for name, parameters, arguments in [
        ("sqlite3_busy_handler", "sqlite3 *db, int (*callback)(void *, int), void *context", "db, callback, context"),
        ("sqlite3_busy_timeout", "sqlite3 *db, int milliseconds", "db, milliseconds"),
    ]:
        require(name + "(" in source, f"{variant}: no {name} call sites")
        index = len(categories)
        categories.append({"name": name, "symbol": name, "available": True,
                           "reason": "inclusive typed wrapper around NativeDB.c calls, engine untouched"})
        wrappers.append(f"static int cp_wrap_{name}({parameters}) {{\n    CP_SCOPE({index});\n    return {name}({arguments});\n}}\n#define {name}(...) cp_wrap_{name}(__VA_ARGS__)\n")
    require("handleTimeout" not in source, f"{variant}: handleTimeout appeared; inspect before adding a probe")
    categories.append({"name": "handleTimeout", "symbol": "handleTimeout", "available": False,
                       "reason": "no such NativeDB.c symbol; handler install/restore calls measured separately"})
    anchor = '#include "sqlite3.h"'
    require(source.count(anchor) == 1, f"{variant}: SQLite include anchor changed")
    names = ", ".join(json.dumps(item["name"]) for item in categories)
    present = ", ".join(str(int(item["available"])) for item in categories)
    prelude = (f"\n#define CP_CATEGORY_COUNT {len(categories)}\n"
               f"#define CP_NAMES {{{names}}}\n#define CP_PRESENT {{{present}}}\n"
               '#include "native-cost-probe.h"\n' + "\n".join(wrappers))
    return modified.replace(anchor, anchor + prelude, 1), categories


def original_commands(log):
    compiles, links = [], []
    for line in log.read_text().replace("\\\n", "").splitlines():
        if not line.lstrip().startswith(("gcc-13 ", "/usr/bin/gcc-13 ")):
            continue
        args = shlex.split(line)
        if "-c" in args and any(arg.endswith("/org/sqlite/core/NativeDB.c") for arg in args):
            compiles.append(args)
        elif "-shared" in args and any(arg.endswith("/NativeDB.o") for arg in args):
            links.append(args)
    require(len(compiles) == len(links) == 1, f"Expected one NativeDB compile/link command in {log}; got {len(compiles)}/{len(links)}")
    return compiles[0], links[0]


def set_output(args, path):
    result = list(args)
    require(result.count("-o") == 1, f"Expected one output argument: {args}")
    result[result.index("-o") + 1] = str(path)
    return result




def build(root, output):
    require(platform.system() == "Linux" and platform.machine() == "x86_64", "Cost probes require Linux x86_64")
    require(not output.exists(), f"Refusing to overwrite existing evidence: {output}")
    evidence = root / "target/vt-wait-evidence/ci-benchmark"
    upstream = root / "target/ci/upstream"
    shared = upstream / "target/sqlite-3.53.4-Linux-x86_64/sqlite3.o"
    shared_hash = digest(shared)
    scripts = root / "scripts/benchmark"
    output.mkdir(parents=True)
    provenance = {
        "schema": 1,
        "shared_sqlite_object": str(shared),
        "shared_sqlite_object_sha256": shared_hash,
        "probe_header_sha256": digest(scripts / "native-cost-probe.h"),
        "builder_sha256": digest(scripts / "build-cost-probes.py"),
        "java_bridge_sha256": digest(scripts / "CostProbe.java"),
        "compiler_version": subprocess.check_output(["gcc-13", "--version"], text=True),
        "timing_semantics": "Inclusive sampled durations overlap; never add nested categories as disjoint CPU. No clock-cost subtraction. Sampling period 256 per category/thread.",
        "variants": {},
    }
    for variant, tree in (("xerial", upstream), ("fork", root)):
        dest = output / variant
        symbols = dest / "symbols"
        instrumented = dest / "instrumented"
        symbols.mkdir(parents=True)
        instrumented.mkdir()
        source = tree / "src/main/java/org/sqlite/core/NativeDB.c"
        original = tree / "src/main/resources/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"
        compile_args, link_args = original_commands(evidence / f"{variant}-native-build.log")
        jni_arg = next(arg for arg in link_args if arg.endswith("/NativeDB.o"))
        sqlite_args = [arg for arg in link_args if arg.endswith("/sqlite3.o")]
        require(len(sqlite_args) == 1 and (tree / sqlite_args[0]).resolve() == shared.resolve(), f"{variant}: linker did not use the shared SQLite object")
        original_jni = (tree / jni_arg).resolve()
        original_c_args = [arg for arg in compile_args if arg.endswith("/org/sqlite/core/NativeDB.c")]
        require(len(original_c_args) == 1 and (tree / original_c_args[0]).resolve() == source, f"{variant}: unexpected JNI source input")
        build_identity = json.loads((evidence / "build-identity.json").read_text())["variants"][variant]
        manifest_path = evidence / f"{variant}-source-manifest.json"
        require(digest(manifest_path) == build_identity["source_manifest_sha256"], f"{variant}: source manifest changed")
        manifest = json.loads(manifest_path.read_text())
        require(digest(source) == manifest[str(source.relative_to(tree))], f"{variant}: JNI source changed after original build")
        for path, key in ((original, "library_sha256"), (original_jni, "jni_object_sha256"),
                          (tree / "target/common-lib/NativeDB.h", "jni_header_sha256"),
                          (shared, "sqlite_object_sha256")):
            require(digest(path) == build_identity[key], f"{variant}: original build input changed: {path}")
        commands = []

        def run(args):
            commands.append({"cwd": str(tree), "argv": args})
            print(shlex.join(args), flush=True)
            with (dest / "commands.log").open("a") as log:
                log.write("$ " + shlex.join(args) + "\n")
                log.flush()
                subprocess.run(args, cwd=tree, stdout=log, stderr=subprocess.STDOUT, check=True)

        control = symbols / "libsqlitejdbc.so"
        run(set_output(link_args, control))
        text_hashes = {}
        for name, library in (("original", original), ("symbols", control)):
            text = dest / f"{name}.text.bin"
            run(["objcopy", "-O", "binary", "--only-section=.text", str(library), str(text)])
            text_hashes[name] = digest(text)
        require(text_hashes["original"] == text_hashes["symbols"], f"{variant}: unstripped control .text differs from original")
        generated, categories = inject(source.read_text(), variant)
        generated_path = instrumented / "NativeDB.c"
        generated_path.write_text(generated)
        # Keep the exact header used beside the generated translation unit.
        (instrumented / "native-cost-probe.h").write_bytes((scripts / "native-cost-probe.h").read_bytes())
        compiled = instrumented / "NativeDB.o"
        instrument_compile = set_output(compile_args, compiled)
        instrument_compile[instrument_compile.index(original_c_args[0])] = str(generated_path)
        # Debug information only; preserve optimization, assertions and policy flags.
        instrument_compile.append("-g")
        run(instrument_compile)
        instrument_link = set_output(link_args, instrumented / "libsqlitejdbc.so")
        instrument_link[instrument_link.index(jni_arg)] = str(compiled)
        run(instrument_link)
        require(digest(shared) == shared_hash, "Shared SQLite object was modified")
        item = {
            "tree": str(tree), "source_sha256": digest(source),
            "source_manifest_sha256": digest(manifest_path),
            "jni_header_sha256": digest(tree / "target/common-lib/NativeDB.h"),
            "original_jni_object_sha256": digest(original_jni),
            "shared_sqlite_object_sha256": shared_hash,
            "original_library_sha256": digest(original),
            "symbols_library_sha256": digest(control),
            "instrumented_library_sha256": digest(instrumented / "libsqlitejdbc.so"),
            "generated_c_sha256": digest(generated_path),
            "text_sha256": text_hashes, "text_identical": True,
            "original_compile_argv": compile_args, "original_link_argv": link_args,
            "commands": commands, "categories": categories,
        }
        (dest / "provenance.json").write_text(json.dumps(item, indent=2) + "\n")
        provenance["variants"][variant] = item
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"Native cost probe provenance: {output / 'provenance.json'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    build(root, (args.output or root / "target/ci/cost-probes").resolve())


if __name__ == "__main__":
    main()
