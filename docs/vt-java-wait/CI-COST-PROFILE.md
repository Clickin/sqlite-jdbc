# CI 비용 위치 계측

**확인된 추가 비용 경로는 DB monitor의 비연속 재진입과 inflation이다.** `withConnectionTimeout`이 DB를 잠근 상태에서 `conn`을 잠그고, `DB.prepare/execute`가 다시 DB를 잠근다. Linux에서 async-profiler 없이도 이 경로의 NativeDB monitor inflation을 재현했고, 별도 CPU profile에서는 fork의 monitor slow-path sample 증가가 반복됐다. **전체 성능 차이의 몇 %인지까지는 분해하지 않았으며, production 락은 변경하지 않았다.**

## 질문과 사전 가설

기존 [동일 toolchain CI 비교](CI-BENCHMARK.md)는 정상 HTTP에서 현재 fork의 처리량이 xerial보다 중앙값 기준 4.79% 낮고, 프로세스 CPU/성공 요청은 13.64 → 14.42ms임을 관찰했다. 이번 실험은 차이의 위치를 찾으며 production driver/Gateway 동작을 최적화하거나 보호 장치를 제거하지 않는다.

| 가설 | 확인할 관측 | 구분할 한계 |
|---|---|---|
| live timeout 조회와 handler 설치·복구 | readBusyTimeout/handler call 수, CPU stack, sampled native thread-CPU/elapsed | readback·attempt·gethandle은 서로 중첩됨; 시간을 더하면 중복 계산 |
| JNI wrapper·보호 검사 | checkBackupAccess/gethandle/prepare/step/column metadata call 수, JNI/JVM 접근 함수 CPU stack | probe 자체가 inlining/코드 배치에 영향을 줄 수 있음 |
| Java 객체·statement bookkeeping | sampled allocation bytes/class/stack 및 요청당 값, GC pause, allocation 관련 CPU | allocation sample weight는 추정치이며 객체 크기와 다름 |
| 동기화·대기 | lock/park stack별 sampled duration, monitor/parking CPU frame | lock event 부재는 경합하지 않은 monitor의 CPU 비용이 없다는 뜻이 아님 |

## 공통 조건과 대조군

- 기존 pinned upstream source, Gateway snapshot, Temurin 25.0.2, compiler-plugin 3.16.0/release8, GCC13, 한 번 만든 shared `sqlite3.o`를 그대로 사용한다.
- xerial/current fork, HTTP-only. 12 client, carrier4, warmup6,000건, 측정30초, 각 cell3회. 총 30 JVM 실행을 한 job에서 순차 실행한다.
- mode 순서를 순환하고 driver 순서를 바꾼다. offline JFR 분석은 모든 측정 JVM이 끝난 뒤 별도 순차 실행한다.
- 같은 source ID/compile options, warmup source 호출 수, HTTP outcome, positive JFR control, DB integrity를 검증한다.

| Mode | native library | 추가 계측 | 목적 |
|---|---|---|---|
| stock | 원래 object를 다시 link한 symbol 보존본 | 기존 benchmark JFR만 | 비계측 대조군 |
| async | stock과 같은 `.text` | CPU 1ms, Java allocation 512KiB, lock 10ms, GC/safepoint | 실 코드 CPU·할당·대기 stack |
| probe-off | 측정 전용으로 생성한 C | counter OFF, 기존 JFR | probe 코드 배치/진입·종료 분기 비용 |
| counts | 같은 측정 C | thread-local 호출 수 | 실제 호출 빈도 |
| sampled | 같은 측정 C | 호출 수 + 1/256 CPU/wall timer | 함수별 비용 범위 |

symbol 보존본은 기존 `NativeDB.o`와 같은 shared engine object를 그대로 link하며, 원래 library와 `.text` SHA가 같아야 한다. 디버깅을 위해 다른 최적화 수준의 엔진을 비교하지 않는다. 계측 library는 `target/ci/cost-probes/`에만 만들며 production resource를 덮어쓰지 않는다.

## CPU와 wall을 혼동하지 않는 검증

async-profiler **4.5** release binary와 Java API를 SHA-256으로 고정한다.

- Linux x64 archive: `89546fbb9ee0fc5496c7edd4099b0709489bc78b0d8057ccbb4b801f6b032b62`
- API JAR: `81d6cae9971d41af140b1c627f40d511182e004b491307dc3c7de3b199ebcbbb`

[Profiler::selectEngine](https://github.com/async-profiler/async-profiler/blob/v4.5/src/profiler.cpp)상 `event=cpu`만으로는 perf/ctimer가 없는 환경에서 wall engine이 선택될 수 있다. 로컬 실제 HTTP smoke에서 `event=cpu, engine=wall`인 recording을 직접 확인했다. 이 데이터를 CPU 증거로 사용하지 않는다.

이번 job은 `record-cpu`로 Linux perf engine을 강제한다. `ProfileSummary`는 [JFR에 기록된 engine 설정](https://github.com/async-profiler/async-profiler/blob/v4.5/src/flightRecorder.cpp)이 **`perf_events`인지 추가 검증**한다. 그 밖의 engine, CPU/할당 sample 누락은 JSON에 오류를 기록하고 nonzero exit다. 실제 wall fallback recording을 다시 분석하여 CPU sample 0 + 오류로 거부하는 음성 검증을 통과했다.

기본 JDK `NativeMethodSample`은 CPU 시간으로 집계하지 않는다. AP CPU sampling과 Java allocation/lock의 의미는 [공식 profiling modes](https://github.com/async-profiler/async-profiler/blob/v4.5/docs/ProfilingModes.md)를 따른다. JVM agent는 warmup 전에 로드하고 실제 수집은 warmup 뒤에 시작한다. 기본/계측 mode에 고비용의 동일 JFR park stack을 무조건 추가하지 않는다. lock은 async mode의 profiler에서 sampling한다.

## Native probe의 정확성과 오버헤드

- `gethandle`, `checkBackupAccess`, `readBusyTimeout`, `attemptNoWaitBusy`, prepare/step/finalize/column metadata/text/parameter count/native exec, busy handler/timeout 호출을 계측한다.
- 없는 함수는 unavailable로 남긴다. upstream에 fork-only 함수가 없는 것을 비용 0 측정으로 위장하지 않는다.
- 정확한 source signature와 build identity를 확인한 뒤, GNU cleanup scope를 삽입한 별도 C를 만든다. 원래 return/error path와 handler 반환값을 보존한다.
- carrier/OS thread별 shard를 한 번만 할당한다. 각 counter는 single-writer relaxed atomic load/store이며 공유 counter의 hot atomic RMW를 피한다.
- 종료 시 새 계측 진입을 닫고, 이미 진입한 scope를 최대 5초 동안 drain한 뒤 snapshot한다. background SQL과 snapshot의 C data race를 피하며, drain/clock 실패는 유효한 데이터로 취급하지 않는다.
- CPU는 `CLOCK_THREAD_CPUTIME_ID`, elapsed는 `CLOCK_MONOTONIC`. 범주/OS thread별 256회에 한 번, phase를 달리하여 표본을 얻는다. 결정적 주기 sampling이지 독립 random sampling은 아니다.
- empty timer sequence의 비용도 기록한다. 이를 기계적으로 빼서 정확한 production 비용이라고 주장하지 않는다. 아주 짧은 함수는 clock/probe cost와 분리되지 않을 수 있다.
- category 시간은 **inclusive sampled 합계**다. nested category를 합산해 전체 CPU 차이라고 보고하지 않는다.

실제 로컬 Gateway 요청에서 OFF=0 calls/samples, counts=호출 수만, sampled=호출 수와 thread-CPU/wall sample이 모두 기록되는 것을 확인했다. Linux에서는 같은 5 modes × 2 drivers의 짧은 사전 실행을 통과해야 긴 실험을 시작한다.

## 실행과 산출물

- [workflow](../../.github/workflows/vt-cost-profile.yml)
- [native probe builder](../../scripts/benchmark/build-cost-probes.py), [collector](../../scripts/benchmark/native-cost-probe.h), [JNI bridge](../../scripts/benchmark/CostProbe.java)
- [sequential experiment runner](../../scripts/benchmark/run-cost-profile.py), [offline JFR reader](../../scripts/benchmark/ProfileSummary.java)
- branch: `benchmarks/vt-cost/20260928-attribution`
- source, flags, `.text` identity, per-run argv/outcome/JFR/counters/CPU/heap/lock summary를 artifact로 남긴다. 완료는 기존 REST background observer의 단일 종료 callback을 사용한다.

## 완료한 CI 실행

1. [비용 계측 run 36432647253](https://github.com/Clickin/sqlite-jdbc/actions/runs/36432647253), commit `90758fa59bdafdba466430151a25363c0a3b12cc`: 5 modes × 2 drivers × 3회 = **30회**, 측정 요청 **128,165건 성공**, warmup 180,000건 성공. 실패 0, integrity 정상.
2. [monitor 확인 run 36442549170](https://github.com/Clickin/sqlite-jdbc/actions/runs/36442549170), commit `1b976080f0f4b0157646471a7e20f1f5216d1b82`: stock/async × 2 drivers × 3회 = **12회**, 측정 요청 **51,063건 성공**, warmup 72,000건 성공. 실패 0, integrity 정상.

두 실행의 source/compile options/shared engine과 native `.text` identity를 검사했다. shared SQLite object는 기존과 같은 `52c0c3e3fcdf73822de1808b2b87dd209d52acc483ced083b911190bf34c43de`다. 양성 pin control은 42/42 통과했다.

첫 실행은 **Intel Xeon Platinum 8573C, 4 vCPU**였다. 앞선 4.79% 비교의 AMD EPYC 7763와 다른 host이므로, 이전 절대 CPU 시간을 이번 계측 값에서 빼거나 성능 회복으로 해석하지 않는다. 같은 job의 stock 기준을 다시 측정했다.

## 1. 관측 오버헤드부터 분리

| Mode | xerial req/s | fork req/s | xerial CPU ms/성공 | fork CPU ms/성공 |
|---|---:|---:|---:|---:|
| stock | 146.15 | 141.92 | 9.675 | 10.070 |
| async | 140.60 | 135.13 | 10.577 | 11.477 |
| probe-off | 146.01 | 140.61 | 9.492 | 10.422 |
| counts | 146.65 | 139.11 | 9.857 | 10.428 |
| sampled | 147.31 | 138.18 | 9.637 | 10.776 |

각 cell은 3회 중앙값. 이 host의 비계측 처리량 차이는 **−2.89%**, 프로세스 CPU 차이는 **+0.396ms/성공 요청**이다. stock 처리량 CV는 xerial 0.84%, fork 1.37%였다.

async mode는 stock보다 CPU/요청이 xerial **+0.903ms**, fork **+1.407ms** 늘었다. 따라서 AP에서 보인 driver 차이 **+0.900ms**를 stock 차이 **+0.396ms**의 정확한 구성 요소로 나눌 수 없다. probe-off도 단순한 timer 0이 아니라 변경된 코드 배치/분기가 있으므로 항상 느려지는 순수 상수 오버헤드가 아니다.

실제 AP recording은 `engine=perf_events`, `event=cpu`, interval 1ms였고, native stack mode는 **`vm`**였다. 요청한 `dwarf` 대신 [프로파일러가 사용 가능한 VMStructs 방식을 우선 선택](https://github.com/async-profiler/async-profiler/blob/v4.5/src/profiler.cpp)한 결과다. wall fallback과 구분해 실제 설정을 기록했다.

## 2. 가장 일관된 CPU 신호: monitor enter/exit

CPU sample은 각 driver의 성공 요청 합계로 정규화했다. 단위는 **성공 요청 1,000건당 sample 수**이며, 시간의 정확한 분해나 호출 횟수가 아니다.

| Leaf frame | xerial | fork | 차이 |
|---|---:|---:|---:|
| `SharedRuntime::complete_monitor_locking_C` | 0.00 | 57.16 | +57.16 |
| `SharedRuntime::complete_monitor_unlocking_C` | 0.00 | 20.20 | +20.20 |
| `LightweightSynchronizer::quick_enter` | 0.31 | 15.27 | +14.96 |
| `LightweightSynchronizer::exit` | 0.47 | 15.36 | +14.89 |
| `ObjectMonitor::spin_enter` | 0.24 | 14.37 | +14.14 |
| `ObjectMonitor::try_lock` | 1.02 | 14.12 | +13.10 |
| native `checkBackupAccess` | 없음 | 9.28 | +9.28 |
| `NativeDB.attemptNoWaitBusy` | 없음 | 5.09 | +5.09 |
| `NativeDB.busyTimeoutReadback` | 없음 | 4.35 | +4.35 |

`complete_monitor_locking_C`의 세 paired 차이는 **+56.62 / +56.32 / +58.55**로 일관됐다. AP 전체 CPU는 10,117.38 → 11,002.96 samples/1,000 successes였지만, monitor 항목이 차이 전부를 설명한다고 주장하지 않는다. 포함 관계가 있는 inclusive frame과 leaf 값을 합산하지 않았다.

### AP 밖에서 검증한 실제 발생 지점

CPU profile만으로는 monitor slow-path의 JDBC caller가 완전히 복원되지 않았다. 따라서 기존 workload를 그대로 두고, **JDK의 `jdk.JavaMonitorInflate`를 connection 생성/warmup과 측정 구간에 각각 기록**했다. positive UDF pin control은 warmup recording에서 제외했다.

| Driver/mode | warmup NativeDB inflation — rep1/2/3 | 측정 중 — rep1/2/3 |
|---|---|---|
| xerial / stock | 0 / 0 / 0 | 0 / 0 / 0 |
| fork / stock | 8 / 8 / 8 | 4 / 3 / 3 |
| xerial / async | 0 / 0 / 0 | 0 / 0 / 0 |
| fork / async | 8 / 8 / 8 | 5 / 3 / 4 |

모두 `org.sqlite.core.NativeDB`, cause **`Monitor Enter`**였다. 따라서 profiler를 켰기 때문에만 발생한 현상은 아니다.

warmup stack:

```text
SQLiteConnection.<init>
  → SQLiteConfig.apply
    → JDBC3Statement.execute
      → withConnectionTimeout: synchronized(db)
        → execute lambda: synchronized(conn)
          → DB.prepare: synchronized(db)  ← inflation
```

측정 중에도 `JDBC3PreparedStatement.execute → withConnectionTimeout → execute lambda → DB.execute`와 connection validation의 `DB.prepare` 경로에서 관측했다.

관련 source:

- [`JDBC3Statement.withConnectionTimeout`](../../src/main/java/org/sqlite/jdbc3/JDBC3Statement.java): DB monitor를 잡고 `callable.call()` 실행.
- 같은 파일의 `execute` lambda: `synchronized(conn)` 안에서 `DB.prepare`.
- [`JDBC3PreparedStatement.execute`](../../src/main/java/org/sqlite/jdbc3/JDBC3PreparedStatement.java): conn monitor 안에서 `DB.execute`.
- [`DB.prepare/execute`](../../src/main/java/org/sqlite/core/DB.java): synchronized instance method이므로 같은 NativeDB monitor에 재진입.

[JDK 25.0.2의 LockStack::try_recursive_enter](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/lockStack.inline.hpp)는 lock stack 맨 위가 같은 객체일 때만 lightweight recursive entry를 허용한다. [LightweightSynchronizer::enter](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/lightweightSynchronizer.cpp)는 fast recursive entry가 실패했는데 해당 객체가 lock stack에 있으면 `inflate_fast_locked_object`를 호출한다.

이번 `db → conn → db`가 그 조건이다. **오래 줄을 서서 기다리지 않더라도**, inflated monitor를 사용하는 진입·해제 경로의 CPU 비용이 생길 수 있다. JFR inflation 건수는 대기 시간이나 정상 요청당 비용 그 자체는 아니다.

## 3. JNI 경계 비용: 실제 추가된 작업과 아닌 작업

호출 수는 **counts mode**, sample 시간은 별도 **sampled mode**에서 구했다.

| 요청당 호출 | xerial | fork |
|---|---:|---:|
| native `gethandle` | 839.14 | 1,007.22 |
| native `checkBackupAccess` | 함수 없음 | 6,844.81 |
| live timeout readback | 함수 없음 | 84.04 |
| no-wait control attempt | 함수 없음 | 84.04 |
| ordinary `step` | 495.13 | 411.10 |
| native `prepare_utf8` / `finalize` 각각 | 276.07 | 276.08 |
| `column_name_utf8` | 1,345.40 | 1,345.42 |
| `bind_parameter_count` | 300.05 | 300.06 |
| busy handler 설치 / timeout 복구 각각 | 0 | 84.04 |

따라서 fork에는 readback/정책 전환/보호 검사가 추가됐지만, **ordinary step 84회가 no-wait attempt로 교체된 것**이다. 이번 workload에서 prepare/finalize나 column metadata 횟수가 더 많아졌다는 가설은 지지하지 않는다. `_exec_utf8` 직접 실행은 측정 구간 양쪽 모두 0회다.

timed sample의 raw inclusive thread-CPU는 fork에서 readback 약 1,367ns, guard 약 479ns, handler 설치/복구 약 457/473ns였다. 그러나 empty timer만 **219–306ns**이고 TLS/scope overhead는 여기에도 포함되지 않는다. guard 479ns × 6,845회를 곱해 **3.28ms가 production 추가 비용**이라고 주장하면 틀린 해석이다. clock 비용을 임의로 빼서 나머지를 확정 비용으로 삼지도 않았다.

## 4. 할당·GC 및 lock 대기 해석

- sampled allocation weight: xerial **1,758,514 B/요청**, fork **1,737,509 B/요청**.
- `org.sqlite.*` class weight: **56,769 → 48,653 B/요청**.
- stock의 30초당 GC pause 중앙값: **38.310 → 38.434ms**. 각 30초의 약 0.128%이며 뚜렷한 pause 증가가 없다.
- SafeStmtPtr allocation 증가나 전체 heap 압력 증가가 주원인이라는 관측은 없었다. 샘플링 추정치이며 모든 concurrent GC CPU를 별도로 측정한 것은 아니다.
- hidden lambda의 `0x...` 주소는 JVM마다 다르므로 owner별 aggregate로 묶었다. 다른 주소를 새 allocation class 비용으로 세지 않았다.

AP lock sample에는 framework/SecureRandom 경로가 있으나 이를 gap의 원인이나 정확한 대기 시간으로 사용하지 않는다. **[AP4.5 LockTracer](https://github.com/async-profiler/async-profiler/blob/v4.5/src/lockTracer.cpp)는 64-bit에서 monitor 진입 시작 시각을 pthread TLS에 저장한다.** [INFERENCE] JDK25 VT가 monitor 대기 중 carrier를 바꾸면 enter/entered 시각 대응이 틀어질 위험이 있다. 수초 단위 AP lock duration에 대해서는 독립적인 JDK wait event 검증 없이 정량 결론을 내리지 않았다. 이 제한은 별도로 관측한 **JDK JavaMonitorInflate**와 구분한다.

## 결론과 다음 변경의 경계

**가장 먼저 검토할 곳은 `withConnectionTimeout → synchronized(conn) → synchronized DB.prepare/execute`의 중첩 락 구조다.** 새 DB monitor는 트랜잭션 재시작/실행을 원자적으로 보호하기 위해 들어갔으므로 단순히 없애면 이전 정확성 버그를 되살릴 수 있다. 다음 최적화에서는 그 보호를 유지하면서 서로 다른 monitor를 사이에 둔 재진입을 없앨 수 있는지 caller 전체를 확인해야 한다.

이번에는 production 락/timeout/backup 정책을 바꾸지 않았다. 추가 JNI 비용도 관측했지만 timer 해상도와 profiling perturbation 때문에 **전체 2.89% 또는 이전 host의 4.79%를 정확히 몇 %씩 분해하지는 않았다.** monitor 구조 변경 전후의 비계측 A/B가 있어야 회복량을 말할 수 있다.

## 결과 파일

- [정규화된 결과·native 호출 수·inflation stack](ci-cost-profile-results.json)
- 사용자 제공 Python 분석 명령: exit 0. 전체 stdout은 `target/vt-wait-evidence/github-36432647253/vt-wait-evidence/ci-cost-profile/requested-attribution.log`.
- CPU/할당 분석용 pretty extracts: 같은 디렉터리의 `analysis-extracts/`.
- raw artifacts: `target/vt-wait-evidence/github-{36432647253,36442549170}/`.
- artifact SHA-256: 1차 `2435fd52331a240465178f5284ebb8dc88b1fa7267671d9b2b187aa688e1f923`, 후속 `f87047b25bd13a35f18ca1cc64fcc770cf73ed7d6628e888679b8badaef28b03`.
- 각 실행은 background REST observer가 **94회/2,844초**, **46회/1,376초** 대기한 뒤 완료 결과를 한 번만 반환했다. `gh run watch` 진행 로그는 사용하지 않았다.

현재 workflow의 push 기본은 `stock async`와 monitor inflation 기록이다. full mode는 workflow_dispatch `phase=full` 또는 runner의 기본 mode set으로 재현한다. 원래 30-run 실험의 정확한 source는 첫 run commit에 보존되어 있다.
