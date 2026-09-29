# Performance parity: P0-0 결과

작성일: 2026-09-29

## 판정

**P0-0 실패: probe 두 `stepControl` 호출을 plain `step`으로 바꿔도 CPU/op 격차의 54.4%만 제거됐다.** 지시서의 70% 기준에 미달하여 P0-1~P3 구현을 중단하고 async-profiler CPU 프로파일을 수집했다. 성능 경합 문제 해결 또는 xerial parity 달성을 주장하지 않는다.

실험 패치는 `target/`에만 적용했고 커밋하지 않았다. 기존 작업 트리의 미커밋 구현·문서·native library는 보존했다. 이 커밋에는 이 보고서만 포함한다.

## 환경과 통제

- macOS arm64, Temurin 25.0.2+10-LTS, async-profiler 4.5 macOS.
- xerial: `cab7981c19ce04d691f0675f0b2586afc2bbf803` (3.53.4.0), 별도 detached worktree에서 Java/JNI 빌드.
- fork: 현재 작업 트리에서 `mvn -q -DskipTests package` 성공. 기존 로컬 native build 사용.
- xerial JNI는 fork의 `target/sqlite-3.53.4-Mac-aarch64/sqlite3.o`에 링크했다. 실행 결과의 SQLite source ID와 compile options가 동일함을 확인했다.
- `JdbcLockBenchmark single`, 기존 runner의 classpath 및 JVM 옵션 재사용. `-Xms512m -Xmx512m`, VT scheduler parallelism/maxPoolSize 모두 4.
- 모든 실행은 순차 실행. 긴 측정은 xerial/fork/probe 순서를 반복별로 회전했다. 다른 시스템 부하까지 격리한 CI 결과는 아니다.
- probe 실험은 `DB.ensureAutocommit`의 BEGIN/COMMIT `stepControl` 두 호출만 `step`으로 대체했다. 기존 reset/rollback 코드는 유지했다. 정상 경로 비용을 분리하기 위한 안전하지 않은 실험이며 제품 코드로 사용할 수 없다.
- 모든 비계측 측정은 기존 `run-lock-comparison.py::validate`로 작업 수, worker 결과, 최종 카운터 및 integrity를 검증했다.

## Before / after

각 셀은 새 JVM 3회 중앙값. 프로파일과 JFR 측정은 이 표에서 제외했다.

### 지정된 로컬 크기: 측정 1,000,000 / warmup 200,000

| 후보 | CPU ns/op 중앙값 |
|---|---:|
| xerial | 698.274 |
| fork | 1,727.489 |
| probe plain 실험 | 1,145.701 |

격차 제거율: `(1727.489 - 1145.701) / (1727.489 - 698.274)` = **56.5%**.

### 짧은 실행 편향 확인: 측정 10,000,000 / warmup 2,000,000

| 후보 | CPU ns/op 중앙값 | CPU ns/op min–max | ops/s 중앙값 |
|---|---:|---:|---:|
| xerial | 676.184 | 673.398–685.634 | 1,486,522 |
| fork | 1,768.198 | 1,729.980–1,791.469 | 566,301 |
| probe plain 실험 | 1,174.046 | 1,149.112–1,195.072 | 853,613 |

격차 제거율: `(1768.198 - 1174.046) / (1768.198 - 676.184)` = **54.4%**.

실험본도 xerial 대비 CPU/op **+73.6%**, 처리량 **−42.6%**다. 초기 측정과 긴 측정 모두 70% gate를 통과하지 못했다. probe 비용은 중요하지만 단독으로 격차 대부분을 해소한다는 예측은 이 환경에서 성립하지 않는다.

## CPU 재귀속

각 후보를 `event=cpu,interval=1ms,cstack=fp`로 10,000,000 operations + 2,000,000 warmup 실행했다. 표본은 startup/warmup까지 포함하며, 본 측정 구간만의 정확한 시간 분해가 아니다. macOS CPU 프로파일이며 Linux perf_events 결과가 아니다.

첫 프로파일은 strip된 SQLite 내부 프레임이 인접 JNI 심볼 `NativeDB_deserialize`로 잘못 귀속됐다. 이를 deserialize 비용으로 해석하지 않았다. 동일한 기존 `NativeDB.o` 및 공유 `sqlite3.o`를 strip 없이 별도 dylib로 링크하여 세 후보를 다시 프로파일했다. 제품 native library는 교체하지 않았다.

아래는 unstripped 프로파일의 inclusive sample 수다. 행은 서로 겹칠 수 있으므로 합산하지 않는다.

| 프레임/경로 | xerial | fork | probe plain |
|---|---:|---:|---:|
| 전체 CPU samples | 8,471 | 21,855 | 14,224 |
| `pthread_jit_write_protect_np` (stub 포함) | 13 | 4,822 | 4,044 |
| `NativeDB.busyTimeoutReadback` | 0 | 5,893 | 0 |
| `NativeDB.attemptNoWaitBusy` | 0 | 2,865 | 0 |
| `SQLiteConnection.recoverTransactionRestart` | 0 | 257 | 249 |
| `ObjectMonitor::*` | 0 | 307 | 256 |
| `LightweightSynchronizer::*` | 0 | 300 | 244 |
| `SharedRuntime::monitor_exit_helper` | 0 | 582 | 454 |

관측과 해석:

1. **probe wait 경계 비용은 실제로 제거됐다.** 실험본의 readback/attempt CPU samples는 0이며, fork에서 `sqlite3RunParser` leaf 340 samples가 확인됐다. 이는 호출 횟수 계측이 아니라 CPU sampling 결과다.
2. **남은 가장 큰 fork-only leaf는 macOS JIT write protection 경로다.** 실험본에서 4,044 / 14,224 = 28.4%의 CPU samples가 이 프레임을 포함했다. stripped/unstripped 두 프로파일 모두 같은 경향이다. `reset`, `step`, `bind_int`, `bind_parameter_count`, `changes` 아래에서 관측된다. 이것이 JIT 컴파일 자체가 병목이라는 뜻은 아니다.
3. **[INFERENCE] JNI restore guard가 주요 후보다.** `NativeDB.c:229–236`의 `checkBackupAccess`는 `GetBooleanField`를 호출한다. `reset` 및 `bind_int` 같은 원래 단순한 statement JNI에도 이 검사가 들어간다. profile의 JIT protection 프레임은 이 JNI 경계 아래에 집중되어 있다. 다만 `jni_GetBooleanField` 자체는 unwind에 나타나지 않았고 guard 제거 대조 실험은 하지 않았으므로, guard가 28.4% 전부의 원인이라고 확정하지 않는다. 지시서의 P2를 macOS에서 단순한 수 % 비용으로 취급할 근거는 부족하다.
4. **monitor slow path도 남는다.** probe 우회 후에도 monitor 관련 samples가 유지된다. guard와 monitor 각각의 인과적 기여율은 아직 분리하지 않았다.

### 별도 JFR monitor 진단

측정 200,000 / warmup 100,000의 `diagnostic` 실행에서 `jdk.JavaMonitorInflate`를 확인했다.

| 후보 | warmup NativeDB inflation | measurement NativeDB inflation |
|---|---:|---:|
| xerial | 0 | 0 |
| fork | 1 | 0 |
| probe plain | 1 | 0 |

측정 구간 inflation 0은 fast monitor 경로라는 뜻이 아니다. warmup에서 이미 inflate된 객체는 측정 중 재-inflate될 필요가 없다. CPU 프로파일에는 fork 및 실험본 모두 monitor exit slow path가 남았다. `single` 결과이므로 공유 연결의 contention 또는 VT pinning 감소 증거로 확대 해석하지 않는다.

## 산출물과 재현

로컬 원자료: `target/vt-wait-evidence/perf-parity/`.

- `summary.json`: 반복 측정 전체, gate 계산, native/source SHA-256, JFR class별 inflation, CPU 요약.
- `{xerial,fork,probe-plain}-{0,1,2,long-0,long-1,long-2}/`: 실제 명령 배열 `command.json`, `stdout.log`, benchmark `result.json`.
- `*-cpu.collapsed`: 원래 native library의 CPU stacks.
- `*-symbols.collapsed`, `profile-summary.json`: strip 없이 재링크한 CPU stacks 및 요약.
- `*-monitors/`: warmup/measurement JFR, `jfr print --json --events jdk.JavaMonitorInflate` 결과.
- `unstripped-{xerial,fork}/`: 진단 전용 dylib.

`command.json`에 기록된 명령은 기존 harness의 다음 인터페이스를 사용한다:

```sh
java --enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m \
  -Djdk.virtualThreadScheduler.parallelism=4 \
  -Djdk.virtualThreadScheduler.maxPoolSize=4 \
  -Dorg.sqlite.lib.path="$NATIVE_DIR" -Dorg.sqlite.lib.name=libsqlitejdbc.dylib \
  -cp "target/perf-parity-harness:$DRIVER_CLASSES:target/classpath/slf4j-api.jar" \
  io.gateway.JdbcLockBenchmark "$OUTPUT" single 10000000 2000000 180 performance
```

원자료는 ignored `target/`의 로컬 증거이며 이 문서 커밋에 포함되지 않는다. 실험 소스와 실험 클래스는 측정 완료 후 제거했다. xerial baseline, harness 및 프로파일 증거는 유지했다.

## 합격 기준 및 후속 결정

| 항목 | 상태 |
|---|---|
| P0-0 격차 70% 이상 제거 | **실패: 54.4%** |
| P0-1, P0-2, P0-3 | 지시서 중단 조건으로 미구현 |
| P1, P2, P3 | 지시서 중단 조건으로 미구현 |
| counts mode readback/attempt 호출 수 | 미실행; sample 0을 호출 수 0으로 대체하지 않음 |
| CI single/private16/shared4/shared16 | 미실행, 합격 판정 불가 |
| Gateway HTTP-only / backup stress | 미실행, 기존 우위 보존 여부 판정 불가 |
| 전체 테스트 / fault / VT progress / Java 8 / Spotless | 제품 변경이 없어 미실행 |
| CI run URL | 없음; 로컬 P0-0 gate에서 중단 |
| `VIRTUAL-THREADS.md` | 동작이 바뀌지 않아 의도적으로 변경하지 않음 |

다음 변경 전 필요한 결정은 P0 단독 우선 계획의 재검토다. **[INFERENCE]** macOS에서는 statement JNI restore guard의 비용을 별도 대조 실험으로 먼저 분리할 가치가 크다. P2의 직접 호출자 안전성 감사 없이 C guard를 제거해서는 안 된다. Linux CI의 같은 CPU profile에서 이 플랫폼 특이 비용이 나타나는지도 확인해야 한다. monitor 범위 확장이나 ReentrantLock 전면 교체를 정당화하는 결과는 아니다.

## A. Restore-guard isolation

작성일: 2026-09-29. 기존 P0-0 결과는 수정하지 않았다. 이 절은 지시서 v2 §2.A의 대조 실험만 기록한다.

### 실험과 유효성

- 다섯 fork 후보(`fork`, `probe-plain`, `guard-nonvolatile`, `guard-off`, `probe-plain-guard-off`)와 xerial 기준을 `single`에서 각각 10,000,000회 측정, warmup 2,000,000회, 새 JVM 3회, 순차 실행 및 반복별 순서 회전으로 측정했다.
- `probe-plain`은 `ensureAutocommit`의 probe BEGIN/COMMIT 두 `stepControl`만 plain `step`으로 바꿨다. `guard-nonvolatile`은 Java field의 `volatile`만 제거했다. `guard-off`은 statement JNI 구간에서 explicit `checkBackupAccess` 조건 19개만 제거했다. `gethandle()` 검사는 유지했다.
- 각 플랫폼에서 xerial과 모든 fork 후보가 같은 SQLite source ID와 compile options를 보고했다. Fork 후보 JNI는 플랫폼별 공유 SQLite object에 링크했다. 기존 runner의 `validate`가 각 JVM의 전체 작업량, worker counter, 결과, DB integrity를 확인했다. 후보별 raw result와 실행 명령은 아래 `target/` 산출물에 보존했다.
- macOS: arm64, Temurin 25.0.2+10, async-profiler 4.5. Linux: OrbStack의 Ubuntu 24.04 arm64 컨테이너, 같은 Temurin 25.0.2+10, GCC 13.3.0. **Linux 측정은 x86_64 GitHub Actions CI가 아니라 로컬 VM 결과다.** 따라서 CI 합격 판정으로 사용하지 않는다.

### 처리량 측정

CPU는 ns/op 중앙값(min–max), 처리량은 ops/s 중앙값이다. xerial은 기준선이다.

| 후보 | macOS CPU ns/op | macOS ops/s | Linux arm64 CPU ns/op | Linux arm64 ops/s |
|---|---:|---:|---:|---:|
| xerial | 680.7 (662.7–690.6) | 1,478,802 | 663 (647–664) | 1,512,388 |
| fork | 1,805.5 (1,742.6–1,805.8) | 554,697 | 1,568 (1,449–1,781) | 637,681 |
| probe-plain | 1,180.9 (1,161.9–1,209.3) | 849,118 | 1,003 (943–1,102) | 998,160 |
| guard-nonvolatile | 1,800.6 (1,778.7–1,815.1) | 556,098 | 1,508 (1,469–1,533) | 663,572 |
| guard-off | 1,804.2 (1,770.1–1,807.3) | 555,038 | 1,552 (1,474–1,769) | 644,870 |
| probe-plain + guard-off | 1,174.4 (1,162.4–1,189.9) | 853,359 | 870 (862–910) | 1,151,481 |

macOS에서 `guard-off`은 `fork`보다 CPU/op가 **0.07%** 낮을 뿐이고, `guard-nonvolatile`도 **0.27%** 차이다. 결합본은 `probe-plain`보다 **0.55%** 낮다. 각 차이는 반복 범위에 비해 미미하다. `pthread_jit_write_protect_np` inclusive samples도 `fork` 4,879/21,562 (22.6%)에서 `guard-off` 4,832/21,983 (22.0%)으로 의미 있게 줄지 않았다. `guard-nonvolatile`은 4,888/22,078 (22.1%)이었다.

Linux arm64에서는 `guard-off` 단독 중앙값이 fork보다 **1.0%** 낮지만 범위가 크게 겹친다. `probe-plain + guard-off`는 `probe-plain`보다 13.3% 낮았으나, 이 결과는 가상화된 로컬 Linux 측정이며 cyclic order에서 `probe-plain`이 항상 결합본보다 먼저 실행됐다. 독립 CI 재현 전에는 guard 효과로 귀속하지 않는다.

### CPU profile

각 후보의 별도 10M/2M profile은 startup/warmup을 포함한다. 표의 수는 inclusive sample이며 호출 횟수가 아니다.

| 후보 | macOS 전체 / `pthread_jit_write_protect_np` | Linux 전체 / `checkBackupAccess` |
|---|---:|---:|
| xerial | 8,568 / 12 | 8,346 / 0 |
| fork | 21,562 / 4,879 | 19,056 / 182 |
| probe-plain | 14,574 / 4,038 | 13,400 / 126 |
| guard-nonvolatile | 22,078 / 4,888 | 21,065 / 195 |
| guard-off | 21,983 / 4,832 | 18,134 / 61 |
| probe-plain + guard-off | 14,525 / 4,017 | 10,942 / 11 |

Linux에서 `jni_GetBooleanField`, `GetBooleanField`, `ThreadInVMfromNative` 이름의 frame은 표본에서 관측되지 않았다. `checkBackupAccess` frame은 fork 182개(전체의 0.96%)에서 guard-off 61개(0.34%)로 줄었다. guard 제거가 실제 native 경로에 적용된 것은 확인되지만, 이 CPU profile은 field accessor의 호출 횟수나 latency를 측정하지 않는다.

### 판정

**지시서 v2 §3의 중단 조건에 해당한다.** guard-off가 macOS `single`에서 효과를 내지 않았다. 따라서 `GetBooleanField`/volatile이 남은 macOS 성능 격차나 JIT write-protect samples의 주원인이라는 가설은 이 대조 실험으로 지지되지 않는다. `volatile`만 제거해도 개선되지 않았다. Linux 로컬 결과는 직접 guard frame이 줄었음을 보였지만, fork 단독 개선은 측정 변동 범위이고 x86_64 CI에서 검증되지 않았다.

**B–D 구현은 시작하지 않았다.** 제품 Java/C/native resource는 변경하지 않았고, 실험은 커밋하지 않았다. 성능 parity 또는 CI 합격을 주장하지 않는다.

Raw evidence: `target/vt-wait-evidence/perf-parity-guard-isolation/` — `macos/performance-summary.json`, `macos/profile-summary.json`, per-run `command.json`/`result.json`/`stdout.log`/`cpu.collapsed`; Linux 대응 자료는 `linux-arm64-local/`에 있다. Linux CI run URL은 없다.

