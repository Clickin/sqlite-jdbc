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
