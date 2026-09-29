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

## A′·B·C′·D. monitor inflation 제거 (지시서 v3)

작성일: 2026-09-29. 기존 절은 수정하지 않았다. 측정 환경은 §A와 같다: macOS arm64, Temurin 25.0.2+10, 같은 harness, fork 후보는 같은 `sqlite3.o`(`2f0fa135…`)에 링크했다. **Linux x86_64 CI는 아직 실행하지 않았다. 최종 합격 판정은 아니다.**

### A′. 메커니즘 확인

- `cstack=vm` 프로파일에서 fork의 `NativeDB.reset/busyTimeoutReadback` 아래에 `SharedRuntime::complete_monitor_locking_C` → `pthread_jit_write_protect_np`가 직접 나타났다. `monitor_enter_helper`/`monitor_exit_helper`/`ObjectMonitor::*` sample은 fork에만 있었다. v3 가설인 "inflate된 NativeDB monitor에서 `synchronized native` wrapper가 VM slow path를 탄다"는 채택한다.
- JFR `jdk.JavaMonitorInflate` 결과로 가설을 한 단계 수정한다. inflation 원인은 두 가지다.
  1. **non-top 재진입:** `withConnectionTimeout`이 `db → conn`을 잡은 뒤 `DB.prepare/execute`가 다시 `db`에 들어가는 경로다. 연결 초기화의 `SQLiteConfig.apply`에서 일어나며, 원인은 커밋되지 않은 작업 트리의 리뷰 수정이다.
  2. **lock stack overflow:** JDK 25 lightweight locking의 per-thread lock stack 용량은 8이다. 같은 monitor에 재귀적으로 들어갈 때마다 슬롯을 하나씩 쓴다. `no-inflate`/`conn-db` 단독 변형은 probe의 `stepControl` 경로에서 깊이가 8을 넘었고, JFR cause는 `VM Internal`이었다. 그래서 단독으로는 개선되지 않았다(1,768 ns/op).
- xerial도 측정 후 검증 쿼리에서 NativeDB inflation 1건이 있었다. 핵심은 inflation 여부 자체가 아니라, **hot path 이전에 inflate되는지와 hot path의 재귀 깊이**다.

| 후보 (3M/1M, 1회 smoke) | CPU ns/op |
|---|---:|
| xerial | 671.6 |
| fork | 1,758.1 |
| probe-plain | 1,180.0 |
| no-inflate / conn-db | 1,230.0 / 1,768.3 |
| probe-plain + no-inflate / + conn-db | 732.1 / 719.1 |

### 구현

| 항목 | 변경 |
|---|---|
| B (P0-1/2) | 새 native `autocommitProbe(begin, commit)`. deferred BEGIN과 COMMIT을 plain step 후 둘 다 reset한다. 호출 1회로 xerial의 step·step·reset·reset 4회를 대신한다. BEGIN 뒤 `sqlite3_txn_state(db, NULL) >= SQLITE_TXN_WRITE`일 때만 COMMIT을 step하지 않고 Java `stepControl`(기존 wait boundary)로 넘긴다. fault build의 probe COMMIT 주입도 이 경로로 넘겨 T12 계약을 유지한다. |
| C′-1 | `withConnectionTimeout`: `conn → db` 순서(upstream과 같은 순서)로 잡고, 실행 lambda의 내부 `synchronized(conn)` 3곳을 제거했다. `DB.statementExecutionOwner`: 같은 스레드의 중첩 실행(generated keys의 `SELECT last_insert_rowid()`, callback)은 monitor에 다시 들어가지 않는다. `executeLargeBatch`도 owner를 표시해, batch 안의 확장 hook이 `db → conn` 역전을 만들지 않게 했다. |
| C′-1 (깊이) | `SafeStmtPtr.pointerForMonitorOwner`: 이미 같은 DB monitor를 보유한 `DB.execute/executeUpdate/ensureAutoCommit`에서 `safeRun*`의 중복 재귀 진입을 없앴다. 검사 순서(restore guard → closed)는 같다. 소유 DB가 다르면 기존 `safeRun*` 경로를 탄다. `DB.execute(long, Object[])`의 중복 `synchronized`를 제거했다. prepared UPDATE hot path의 최대 재귀 깊이는 xerial 7, 이전 fork 8+, 현재 6이다. |
| D | `recoverTransactionRestartForStatement`: DB monitor를 보유한 호출자에서 pending이 없으면 재진입 없이 반환한다. setlk capability를 로드된 native library 단위의 static으로 공유한다(읽기 실패는 캐시하지 않음). |

**하지 않은 것**

- **P0-3의 "PRAGMA stmt 캐시"는 잘못된 설계라 적용하지 않았다.** `PRAGMA busy_timeout`은 prepare 시점 값을 `OP_Int64` 상수로 굳힌다(`sqlite3.c` `returnSingleInt(v, db->busyTimeout)`). readback과 첫 시도의 병합도 보류했다. 절감은 control statement당 JNI 1회뿐인데, 인터럽트와 timeout 0의 기존 순서 계약을 유지하려면 분기가 늘어난다.
- **C′-2(Java synchronized wrapper)도 하지 않았다.** C′-1 후 비경합 경로에서 sqlite monitor inflation이 0이고, shared 시나리오가 이미 xerial을 넘는다. 모든 플랫폼 native 재빌드 비용에 비해 근거가 부족하다.

### 락 획득 순서

| 경로 | 순서 |
|---|---|
| Statement/PreparedStatement execute·executeQuery·executeUpdate | conn → db (owner 표시) |
| 같은 스레드의 중첩 실행 (owner) | 새로 획득 없음 |
| `Statement.executeLargeBatch` | db (owner 표시) |
| commit/rollback/setAutoCommit/savepoint/tryEnforceTransactionMode/prepareStatement | db |
| DatabaseMetaData 조회 | metadata → conn → db |
| ResultSet close | db |
| getTypeMap/setTypeMap | conn |

- 새로 생긴 `db → conn` 간선은 없다. 남은 이론적 간선은 commit/update hook 안에서 같은 연결로 JDBC statement를 실행하는 경우다. 이는 SQLite가 금지하는 사용이고, upstream에도 같은 간선이 있다.
- 외부 `synchronized(connection)`과의 관계는 upstream과 같다. statement 실행 중 connection monitor를 보유한다.

### 검증

- JDK 25 전체: **521 tests, 0 failures, 0 errors, 13 skipped**. 증가한 테스트 수는 작업 트리의 기존 테스트 추가분이다.
- `make test-faults`: BackupFaultInjectionTest 3개와 T12 2개 모두 통과.
- lock-safety 29개 시나리오 × {변경 전 작업 트리, 변경 후} × 2회는 모두 유효했고, 관측 차이는 **0**이다. metadata lock cycle, external connection/db monitor, generated keys, restart pending, query timeout race를 포함한다. 러너는 `run-lock-safety.py`와 같은 `LockScopeSafety` case를 두 classpath로 실행했다.
- JFR(`final`): 측정 구간에서 sqlite 클래스의 monitor inflation 0건. `cstack=vm` 프로파일의 monitor slow-path frame 0.
- JDK 8 smoke(`scripts/vt-java8-smoke.sh`, Amazon Corretto 8.0.472): `JAVA8-SMOKE-PASS`. 다른 플랫폼 native 빌드는 아직 확인하지 않았다.

### macOS 측정 (3회, 순서 회전)

CPU는 ns/op 중앙값(min–max), 처리량은 ops/s 중앙값이다. single은 10M/2M, 나머지는 4M/1M이다.

| 시나리오 | xerial CPU | fork CPU | final CPU | final/xerial CPU | xerial ops/s | final ops/s | final/xerial 처리량 |
|---|---:|---:|---:|---:|---:|---:|---:|
| single | 683.7 (679.0–688.8) | 1,752.3 | 703.4 (697.6–704.8) | 1.029 | 1,473,173 | 1,430,029 | 0.971 |
| private16 | 748.8 (727.9–778.9) | 2,121.0 | 755.5 (720.5–983.6) | 1.009 | 5,319,681 | 5,208,615 | 0.979 |
| shared4 | 3,125.0 (3,083.2–3,152.1) | 4,051.1 | 2,782.6 (2,774.3–2,802.4) | 0.890 | 571,980 | 628,848 | **1.099** |
| shared16 | 3,179.2 (3,170.9–3,207.3) | 4,807.8 | 2,911.1 (2,880.1–2,921.6) | 0.916 | 558,364 | 606,271 | **1.086** |

B 단독(`b-probe`)의 single은 1,060.4 ns/op였다. macOS에서는 v1 §4 기준(single/private16 CPU ≤ ×1.03이고 처리량 ≥ ×0.97, shared 처리량 ≥ ×0.97)을 모두 충족한다. single은 경계값에 가깝다. **Linux x86_64 CI와 Gateway HTTP 측정 전에는 합격을 주장하지 않는다.**

Raw evidence: `target/vt-wait-evidence/perf-parity-v3/` — `macos/perf-*-summary.json`, 실행별 `command.json`/`result.json`/`stdout.log`, `macos/vmprof-*`/`prof-*`(cpu.collapsed), `macos/smoke*-*/rec.jfr`, `lock-safety/c1c-summary.json`, 후보 classes(`java/*/source.diff` 포함)와 natives.

### 커밋과 CI

리뷰하기 쉽도록 기존 미커밋 작업과 v3 작업을 분리했다. 커밋마다 해당 단계 소스로 macOS native를 다시 빌드해 함께 넣었다. 첫 두 커밋의 dylib은 해당 단계를 새로 빌드한 결과와 바이트 단위로 같다.

| 커밋 | 내용 |
|---|---|
| `f09534dd` fix | 기존 리뷰 수정: restore guard, 트랜잭션 재시작 원자성, pre-interrupt 거부 |
| `3d424fc6` perf | 기존 최적화: 첫 readback snapshot 재사용. **v3 비교 기준(before) 리비전** |
| `892c7196` ci | 벤치마크·lock·cost workflow와 harness |
| `356007b1` docs | CI 증거 문서, 지시서 v1–v3, P0-0/§A 결과 |
| `5ea96528` perf | B: `autocommitProbe` |
| `d6c1ee4a` perf | C′-1: monitor inflation 제거 |
| `670f8798` perf | D: 재시작 fast path, setlk 공유 |
| `42b88073` ci | before를 `3d424fc6`에서 빌드하고 source pin을 갱신한다. lock comparison은 xerial/fork만 비교한다(flat/reentrant 변형은 폐기) |

- 이전 `pre-optimization.patch` 방식의 before는 새 소스에 적용되지 않는다. 비교 기준으로서도 의미를 잃어서 `3d424fc6` 리비전으로 대체했다.
- Linux x86_64 판정은 `benchmarks/vt-lock/…`(`vt-lock-comparison.yml`: single/private16/shared4/shared16)와 `benchmarks/vt/…`(`vt-benchmark.yml`: Gateway HTTP, 백업 스트레스) 브랜치 push로 실행한다.

### CI 결과 (Linux x86_64)

처리량 비율은 fork/xerial 중앙값이다. hosted runner의 CPU 세대가 run마다 달라서, 서로 다른 run의 수치를 직접 비교하지 않는다.

| Run | 대상 | runner CPU | single | private16 | shared4 | shared16 | 판정 |
|---|---|---|---:|---:|---:|---:|---|
| 36573127724 lock comparison | `42b88073` | EPYC 7763 (Zen 3) | 0.975 | 0.982 | **0.568** | **0.575** | 실패 |
| 36584178780 lock comparison | `b2b0f928` (fast path) | EPYC 9V45 (Zen 5) | 0.996 | 0.993 | 1.054 | 0.986 | 통과 (shared CV 5–19%, noisy) |
| 36585068320 lock-cause | `db0d9586` | 기록 없음 (4 vCPU) | 0.988 | 0.970 | 1.037 | 1.028 | 격차 없음 |

- 첫 run의 JFR에서 fork FJP carrier park 수는 9,137이고 xerial은 4,762였다. shared connection monitor 경합으로 VT가 unmount된 흔적이다.
- `b2b0f928` fast path: `tryEnforceTransactionMode`가 강제할 것이 없으면 DB monitor를 잡지 않는다. 이러면 statement 한 번의 monitor 획득 횟수가 upstream과 같아진다.
  - fast path 이후 두 run 모두 shared 격차가 나타나지 않았다.
  - 다만 runner가 달라서 개선이 fast path 덕분인지 하드웨어 차이 때문인지는 분리되지 않는다.
  - 로컬 Linux arm64 docker에서는 fast path 적용 전에도 fork가 11–18% 빨라서 격차가 재현되지 않았다.
- lock-cause(`db0d9586`)의 다른 셀:
  - carrier 1개인 `shared4-vt-c1`은 0.947이다.
  - carrier 2/8개, platform thread, shared2/8, outer-op 셀은 모두 0.99–1.13이다.
  - xerial/fork의 async CPU profile과 JFR은 artifact에 있다.
- Gateway HTTP 36573132854(`42b88073`):
  - 일반 부하: fork 172.5 rps, xerial 174.8 rps(0.987), before 166.3 rps.
  - 백업 스트레스: fork 129.3 rps, xerial 76.0 rps. xerial은 6회 모두 요청 실패가 있어(총 253건) workflow가 설계대로 실패로 끝났다.

### CI 테스트 정리

- 첫 CI(36573127048)는 non-macOS native가 새 JNI 심볼(`autocommitProbe`)을 갖고 있지 않아 `UnsatisfiedLinkError`로 실패했다.
  - `04547712`: Build Native를 `natives/**` push로 실행하게 했다. workflow_dispatch는 기본 브랜치에 workflow가 없어 쓸 수 없다.
  - `73f0f26c`: 그 결과 봇이 native 24개를 갱신했다.
- native 갱신 후 CI(36585066825)에서는 `VtCarrierProgressTest`만 실패했다. 이 브랜치에서 전체 테스트가 CI에서 끝까지 돈 것은 처음이다. `dac81757`이 테스트의 환경 가정을 고쳤다.
  - JDK 21: JEP 491 이전에는 monitor 안에서 기다리는 VT가 carrier를 pin한다. 그래서 independent-progress 시나리오를 JDK 24+로 한정했다. exclusion 시나리오는 계속 실행한다.
  - GraalVM native image: child JVM을 띄울 수 없어 `@DisabledInNativeImage`를 붙였다.
  - QEMU riscv64: busy budget 1.5s가 lock 해제보다 먼저 끝났다. budget을 10s로 늘렸다. 대기는 lock 해제 시점에 끝나므로 빠른 host에서는 비용이 없다.
  - QEMU 에뮬레이션 job에서는 테스트를 skip한다(`SKIP_TEST_MULTIARCH`).
    - aarch64 alpine: JFR recording이 0바이트로 기록됐다. 같은 JDK 패키지를 native arm64 alpine에서 돌리면 정상이다(`PINNED=0`).
    - riscv64: child JVM이 `libjvm.so`(`frame::interpreter_frame_method`)에서 SIGSEGV로 죽었다. 직전 run에서는 같은 테스트가 통과했다.
    - 같은 증명은 네이티브 runner(ubuntu x86_64/arm64, macOS, Windows)에서 계속 실행된다.
- GraalVM native image 18개 job이 모두 실패했다(`06bc3b90`에서 수정).
  - 원인: `NativeDB.c`는 이 브랜치에서 JNI 진입점 이름을 `*FromSQLite`/`*Callback` wrapper로 바꾸고 `restoreSessionActive` 필드를 추가했다. 그런데 `SqliteJdbcFeature`는 옛 이름을 등록하고 있었다.
  - 결과: native image의 `JNI_OnLoad`가 `NoSuchMethodError`로 실패해 연결을 열 수 없었다. native image 사용자에게는 실제로 드라이버가 동작하지 않는 결함이다.
  - `NativeImageJniRegistrationTest`를 추가했다. `NativeDB.c`가 조회하는 멤버와 Feature 등록을 JVM 단위 테스트로 대조하고, 이전 Feature에서는 누락된 10개 멤버를 모두 보고한다.

### CI 시간 정리

- 이전 run의 전체 시간 29분 중 대부분은 QEMU job이 차지했다: riscv64 29분, aarch64 15분, ppc64le 12분, armv7 10분, alpine 9분.
- GraalVM job은 18개였고, 각각 2–5분이 걸렸다.
- 바꾼 점(`ci.yml`):
  - 같은 브랜치에 새로 push하면 이전 run을 취소한다(master는 제외).
  - GraalVM·QEMU job은 lint와 기본 test가 통과한 뒤에만 실행한다.
  - master 이외 브랜치 push에서는 GraalVM을 ubuntu·JDK 25의 2개 job만 돌리고 QEMU job은 생략한다. PR, 수동 실행, master에서는 전체 매트릭스를 돌린다.
  - glibc aarch64는 QEMU 대신 네이티브 `ubuntu-24.04-arm` runner에서 test job으로 실행한다.
- `scripts/ci-local-check.sh`로 push 전에 spotless(JDK 17)와 JDK 25/21 전체 테스트를 돌린다. 로컬에서 약 1분 걸린다. `--native`와 `GRAALVM_HOME`을 주면 native image 테스트도 돌린다.
