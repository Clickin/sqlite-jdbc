# shared4 CPU 증가: busy waiting과 VT 락 인계 원인 분석

## 결론

**SQLite BUSY 재시도가 원인이라는 증거는 없다. AQS의 bounded spin은 실제로 관측됐다. 다만 가장 강한 증거는 “여러 구간으로 나뉜 DB 락 획득·해제와 VT 재스케줄링의 상호작용”을 가리킨다. Spin 하나로 CPU 증가를 설명하거나, spin을 끄면 해결된다고 결론 낼 수 없다.**

- 로컬 32개 대조/진단 실행과 Linux CI 90개 JVM의 warmup·측정에서 **Java SQLite busy 재시도 counter는 모두 0**이었다. 실제 SQLite BUSY를 만든 로컬 양성 대조군은 fork/reentrant 각각 **5회**를 기록했다.
- CPU profile에서 AQS의 `Thread.onSpinWait()`가 있는 source line에 표본이 잡혔다. 동시에 ForkJoinPool의 task 처리·idle scan과 VT/continuation 경로 비용도 나타났다.
- 같은 UPDATE 전체를 **기존 DB 락**으로 한 번 감싸자 ReentrantLock의 stock CPU/op가 **29.99% 감소**, 처리량은 **27.01% 증가**했다. 그런데 별도 profile의 AQS spin 위치 표본은 오히려 증가했다. 따라서 “spin이 많아서 느리다”만으로는 관측을 설명하지 못한다.
- Production driver의 락은 바꾸지 않았다. Outer guard는 원인을 구분하는 실험 개입이며 바로 적용할 최적화가 아니다.

## 1. 기존 결과에서 왜 4개만 CPU/op가 나빠 보였나

기존 [동일 작업량 CI 36509360919](https://github.com/Clickin/sqlite-jdbc/actions/runs/36509360919)의 raw worker/CPU 시간을 다시 계산했다. `sharedN`은 **연결 1개를 N개 작업자가 직접 공유**한다. Connection pool의 독점 대여와는 다르다.

| 기존 조건 | fork 평균 CPU 사용량 중앙값 | Reentrant 평균 CPU 사용량 중앙값 | 처리량 변화 | CPU/op 변화 |
|---|---:|---:|---:|---:|
| shared4 | 약 1.853 core | 약 2.181 core | +0.40% | +17.24% |
| shared16 | 약 1.821 core | 약 2.180 core | +36.58% | −12.16% |

여기서 core는 process CPU-time/wall-time을 논리 CPU 단위로 환산한 값이다. 물리 core의 점유 개수를 계측한 값은 아니다.

각 실행에서 다음 관계가 정확히 성립한다.

```text
CPU / operation = (process CPU time / wall time) / (operations / wall time)
                = 평균 CPU 사용량 / 처리량
```

즉 **ReentrantLock이 더 많은 CPU를 쓰는 현상은 기존 shared16에도 있었다.** shared16에서는 처리량 증가가 이를 상쇄했고, shared4에서는 처리량이 거의 늘지 않아 CPU/op 증가로 드러난 것이다. 이것은 기존 수치의 해석이며, 그 CPU가 전부 spin이라는 뜻은 아니다. 서로 다른 지표의 중앙값끼리는 위 항등식이 정확히 맞을 필요가 없으므로 표는 반올림한 설명용 값이다.

## 2. 사전 가설과 구분 방법

| 가설 | 구분할 관측/개입 |
|---|---|
| SQLite가 BUSY라 반복 대기 | `DB.javaWaitObservations` 전후 차이, 실제 BUSY 양성 대조군 |
| AQS 재획득 spin/CAS 경쟁 | Linux CPU profile의 method·source line·BCI, carrier 수 변화 |
| VT park/unpark·scheduler 비용 | 같은 JDBC/AQS를 platform thread로 실행, scheduler/continuation CPU leaf 확인 |
| UPDATE 안의 여러 guard 경계에서 인계 증가 | 기존 DB 락을 UPDATE 전체 동안 유지하는 outer-op 대조군 |
| 작업 시작·종료 편향 | worker별 시작 offset·수행 시간·최초 완료 시점, fixed-work 검증 |

SQLite의 이름에 있는 `BUSY`와 CPU busy-waiting은 별개다. [Java BUSY 재시도](../../src/main/java/org/sqlite/core/DB.java)는 counter를 증가시키고 `Thread.sleep`한다. 이번 측정은 메모리 DB의 동일 연결 하나를 Java guard로 직렬화하며, 다른 연결의 writer·backup·파일 잠금 경합이 없다. Counter는 Java retry sleep의 관측치이며 임의의 native mutex 호출 전체를 세는 계측은 아니다.

## 3. 이번 CI와 공통 조건

- [CI 36516852571](https://github.com/Clickin/sqlite-jdbc/actions/runs/36516852571): **success**.
- Source `b25e6dabf04a195ccfe546c9925c318d17a8107d`, branch `benchmarks/vt-lock-cause/20260929-shared4`.
- Ubuntu 24.04.5, AMD EPYC 7763, guest 4 vCPU / 2 threads per core. Temurin 25.0.2, GCC13. 두 후보는 같은 native 바이너리를 사용한다.
- Stock: **12개 조건 × 2개 구현 × 3회 = 72개 JVM**, 각각 측정 2,000,000회·warmup 500,000회. 구현 순서를 교대하며 순차 실행한다.
- 별도 async-profiler CPU profile 10개 JVM은 같은 작업량. 별도 JFR 진단 8개 JVM은 측정 100,000회·warmup 20,000회. 계측 결과를 stock 성능 집계에 섞지 않는다.
- 90/90 JVM의 정확한 작업 수·final counter·integrity·Java busy counter·JDK/SQLite identity를 검사했다. 26/26 recording 분석이 통과했다.
- CPU backend는 전부 `perf_events`, interval 1ms다. 실제 native stack mode는 `vm`이었다. Source line·BCI까지 보존했다.
- Stock 측정 144,000,000회, CPU-profile 측정 20,000,000회, 진단 측정 800,000회. 모든 작업이 완료됐으며 retry/drop은 없다.
- `gh watch` 없이 background REST observer가 29회 조회 후 1,276.0초에 최종 callback만 전달했다. 이후 artifact를 조회했다.

## 4. 비계측 시나리오 결과

모두 각 cell 3회 중앙값이다. CPU/처리량 변화는 ReentrantLock 대 fork다. 일반 shared 조건은 JDBC 내부 guard를 그대로 쓰며, outer-op만 guard 범위를 확장한다. 별도 표시가 없으면 carrier 4개다.

| 조건 | fork CPU µs/op | Reentrant CPU µs/op | CPU 변화 | 처리량 변화 | 품질 주의 |
|---|---:|---:|---:|---:|---|
| 4 VT / carrier 1 | 3.990 | 3.565 | -10.65% | +12.24% | 없음 |
| 4 VT / carrier 2 | 11.260 | 8.920 | -20.78% | +36.74% | 없음 |
| 4 VT / carrier 4 | 10.410 | 14.555 | +39.82% | -13.53% | 없음 |
| 4 VT / carrier 8 | 11.345 | 14.380 | +26.75% | -9.57% | 있음 |
| 4 platform threads | 8.305 | 9.030 | +8.73% | -2.69% | 있음 |
| 2 VT / carrier 4 | 10.860 | 10.735 | -1.15% | +13.91% | 있음 |
| 8 VT / carrier 4 | 10.520 | 14.375 | +36.64% | -12.10% | 있음 |
| 16 VT / carrier 4 | 10.300 | 14.450 | +40.29% | -15.55% | 없음 |
| 1 VT / carrier 4 | 3.980 | 3.555 | -10.68% | +11.72% | 없음 |
| 16 VT / 독립 연결 16개 | 7.050 | 6.265 | -11.13% | +12.41% | 있음 |
| 4 VT / UPDATE 전체 guard | 11.770 | 10.190 | -13.42% | +27.88% | 없음 |
| 16 VT / UPDATE 전체 guard | 11.430 | 10.470 | -8.40% | +18.11% | 없음 |

품질 주의는 어느 후보든 CV >5%, 측정 <5초, 또는 반복 <2회인 경우다. 특히 platform 조건의 fork 변동과 carrier8/작업자8 조건의 fork 변동을 감안해야 한다. 짧은 private16도 품질 주의다. 표본을 삭제하거나 자동 우승 판정을 하지 않았다.

### Carrier 수: 실제 동시 경합과 scheduler 조건

작업자 4개를 유지해도 carrier1에서는 ReentrantLock이 CPU −10.65%, 처리량 +12.24%였고, carrier2에서는 각각 −20.78%, +36.74%였다. Carrier4에서는 CPU +39.82%, 처리량 −13.53%로 바뀌었다.

이는 “작업자가 네 개라서 SQLite가 busy”라는 설명보다 **실제 동시 실행·락 인계·VT scheduler 조건의 상호작용**을 지지한다. Carrier1은 이 blocking 없는 UPDATE 루프에서 사실상 병렬 락 획득 경쟁을 없애는 대조군이다. 실제 서버의 carrier를 1개로 줄이라는 권고가 아니다. Guest SMT topology는 기록했지만 affinity나 하드웨어 cache/PMU counter는 통제하지 않았다.

### VT를 platform thread로 바꾼 대조군

ReentrantLock 자체의 CPU/op는 **14.555 → 9.030µs**로 약 38% 줄었고 처리량은 비슷했다. Platform에서도 AQS와 같은 SQLite/JDBC 경로를 사용한다. VT 경로의 추가 비용을 지지하지만, platform 후보 간 차이는 fork CV 경고가 있어 정확한 비용 분해 비율로 쓰지 않는다.

### 이전 shared16 이득의 해석 한계

이번 조건에서는 shared16도 ReentrantLock의 CPU가 증가했다. 이전 실행은 5M 측정/1M warmup/4회였고, 이번에는 2M/500k/3회 및 진단 확장 harness를 사용했다. CPU 모델명이 같아도 동일 host/run은 아니다. **이전 수치를 폐기하거나 두 실행의 절대 시간을 직접 빼지 않는다.** 다만 “4개만 고정적으로 나쁜 예외, 16개는 항상 유리”라고 일반화할 근거는 없다. 두 실행에서 공통으로 확인한 shared4 CPU 증가를 이번 제어군과 profile로 분석한다.

## 5. 실제로 어디에서 CPU를 썼나

아래는 별도 CPU profile의 **완료 operation 1,000,000회당 leaf sample 수**다. 시간이나 호출 횟수가 아니다. AQS spin 행은 `acquire`의 post-unpark `Thread.onSpinWait()` 위치인 line 782에 귀속된 표본이다. Scheduler interrupt 행은 `Thread.interrupted` leaf이면서 caller stack에 `ForkJoinTask$InterruptibleTask.exec`가 있는 표본만 센다.

| CPU leaf 표본 위치 | fork·4 VT | Reentrant·4 VT | Reentrant·4 platform | Reentrant·UPDATE 전체 guard |
|---|---:|---:|---:|---:|
| AQS post-unpark spin 소스 line 782 | 0.0 | 628.0 | 3.0 | 2,410.0 |
| scheduler task의 interrupt 상태 확인·정리 | 16.0 | 1,689.5 | 0.0 | 1,272.5 |
| ForkJoinPool.deactivate idle scan | 5.0 | 757.5 | 0.0 | 336.0 |
| 그 외 AQS/ReentrantLock leaf | 0.0 | 1,179.0 | 810.5 | 1,576.0 |
| VT/continuation leaf | 1.0 | 231.5 | 0.5 | 141.5 |

확인된 source:

- [AQS.acquire의 bounded post-unpark spin](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/java.base/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java#L779-L792): 깨어난 첫 waiter가 획득에 실패하면 짧은 재시도 뒤 다시 park할 수 있다. 전체 acquire가 무조건 spin만 하는 것은 아니다.
- [ForkJoinPool.deactivate](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/java.base/share/classes/java/util/concurrent/ForkJoinPool.java#L2042-L2073): idle 전환 중 queue를 검사하고 일부 경로에서 `onSpinWait`한다. 이것은 AQS lock spin과 별도다.
- [Thread.interrupted](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/java.base/share/classes/java/lang/Thread.java#L1624-L1627): interrupt 상태 확인·정리다. 이 표본 증가는 실제 cancel/interrupt 요청이 많았다는 뜻이 아니라 **scheduler task 실행 경로의 비용**이다.

**AQS spin은 실제로 존재하지만, 공유 VT 실행에서는 scheduler 경로와 queue/CAS, continuation 비용도 같이 발생했다.** AQS method 전체 표본을 전부 spin으로 간주하지 않았고, inclusive frame을 합산해 CPU 시간을 계산하지 않았다. Native SQLite 내부 일부는 library bucket으로만 보이므로 native 함수별 비용이나 cache locality 기여는 분해하지 못했다.

## 6. 가장 강한 개입: 한 UPDATE 안의 락 인계 경계를 줄이기

[executeLargeUpdate](../../src/main/java/org/sqlite/jdbc3/JDBC3PreparedStatement.java)의 기본 구조에는 별도 DB guard 구간이 있다.

```text
pointer.safeRunConsume(DB::reset)     : DB lock → reset → unlock
tryEnforceTransactionMode()          : DB lock → 상태/복구 검사 → unlock
withConnectionTimeout(executeUpdate) : DB lock → bind/step/후처리 → unlock
```

Reentrant 후보도 이 범위를 그대로 이식했다. 공유 작업자는 구간 사이마다 경쟁할 수 있다. **[INFERENCE]** 짧은 구간들의 반복 인계가 실패한 획득·enqueue·VT yield/재개를 반복시키는 것이 CPU 증가의 핵심 기전이라는 해석이, 아래 개입과 profile에 가장 잘 맞는다. 실제 모든 lock/unlock 횟수를 전수 계측한 것은 아니다.

Outer-op은 별도 락을 추가한 것이 아니라 **해당 구현의 기존 DB monitor 또는 connectionLock을 executeUpdate 전체 동안 보유**했다. 내부 guard와 SQL은 제거하지 않았다.

| shared4 개입 | 기본 CPU µs/op | outer-op CPU µs/op | CPU 변화 | 처리량 변화 |
|---|---:|---:|---:|---:|
| fork | 10.410 | 11.770 | +13.06% | −14.12% |
| ReentrantLock | 14.555 | 10.190 | **−29.99%** | **+27.01%** |

ReentrantLock의 별도 profile에서는:

- AQS post-unpark spin 위치 표본: **628 → 2,410 / 백만 operation**으로 증가.
- Scheduler task interrupt 확인·정리: **1,689.5 → 1,272.5**, idle scan: **757.5 → 336 / 백만 operation**으로 감소.
- AQS ExclusiveNode allocation 표본: **23 → 3개**. ForkJoinTask RunnableExecuteAction 표본: **114 → 75개**. Allocation은 512KiB sampling이며 전체 객체 수가 아니다.
- Stock뿐 아니라 profiled CPU/op도 **15.965 → 12.225µs**로 감소했다.

**Spin 표본이 늘었는데 전체 CPU/작업은 줄었다.** 따라서 spin 자체의 존재를 문제의 단일 원인으로 보는 것은 맞지 않는다. 더 넓은 guard 범위가 queue·재개 비용을 바꾸는 상호작용이 중요하다. 다만 outer-op은 scheduling뿐 아니라 locality 등도 바꿀 수 있다. 또 fork에는 역효과였으므로 모든 구현에 같은 wrapper를 적용하는 최적화로 일반화하지 않는다.

## 7. JFR와 profiler의 해석 한계

[LockSupport](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java#L178-L224)는 일반 VT park를 continuation yield 경로로 보낸다. [JFR ThreadPark는 Unsafe_Park](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/prims/unsafe.cpp#L755-L783) 경로의 이벤트여서 일반적인 unmounted VT 대기를 모두 세지 못한다.

실제 진단에서도 Reentrant shared4 VT의 ThreadPark는 주로 **carrier ForkJoinPool 27,080건**이고 NonfairSync park는 없었다. Platform 대조군에는 **NonfairSync 26,413건**이 기록됐다. Outer-op VT의 carrier ForkJoinPool park는 **7,014건**이었다. 이 차이를 “VT는 락을 기다리지 않는다”거나 park duration이 CPU라고 해석하지 않는다. 모든 값은 별도 100k-operation 진단 구간이다.

Profile overhead는 같지 않다. shared4 VT CPU/op는 stock 대비 fork **+21.76%**, Reentrant **+9.69%**였고, platform fork는 **+73.39%**였다. CPU event 수 / process CPU ms의 표본 밀도도 shared4 VT에서 약 **0.58 / 0.88**로 달랐다. 따라서 profile의 frame 비율을 원래 +17.24% CPU의 정확한 구성 비율로 환산할 수 없다. CPU profile은 경로 증거, stock 개입은 효과 크기의 근거로 구분했다. AP 로그에는 두 후보 모두 PERF_EVENT_IOC_REFRESH workaround 적용 DEBUG 메시지가 있었으며 backend/표본/recording 검증은 통과했다.

## 8. 요청한 JVM flag 명령

요청한 명령을 그대로 실행했고 exit 0이었다.

```sh
/Users/senghyunjo/.sdkman/candidates/java/25.0.2-tem/bin/java -XX:+PrintFlagsFinal -version
```

로컬 Temurin 25.0.2+10에서 `LockingMode=2`, `EliminateLocks=true`, `EliminateNestedLocks=true`를 확인했다. CI에서는 동일 heap 옵션과 함께 별도로 flags를 기록했으며 이 세 값은 같았다. 로컬 명령의 기본 heap/CPU ergonomics를 Linux 측정 설정으로 간주하지 않았다.

[HotSpot monitor 설정](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/globals.hpp#L1964-L1979)은 AQS library loop와 구분해야 한다. `LockingMode`나 diagnostic monitor spin 값을 바꾸는 것은 ReentrantLock의 AQS spin을 끄는 대조군이 아니다. `Thread.onSpinWait` hint를 없애는 것도 재시도 루프 자체를 없애는 것이 아니다. 이번 분석에서 임의의 JVM spin flag나 fair/custom lock 변경은 하지 않았다.

## 재현·보존 자료

- [causal runner](../../scripts/benchmark/run-lock-cause.py), [workload](../../scripts/benchmark/JdbcLockBenchmark.java), [CPU source-location reader](../../scripts/benchmark/ProfileSummary.java).
- [전용 workflow](../../.github/workflows/vt-lock-cause.yml), [모든 수치·조건·profile 표본·callback JSON](ci-lock-cause-results.json).
- Artifact `jdbc-lock-cause`, ID **11011578515**, 30일 보존.
- GitHub 제공 SHA-256: `ef7314f334d8f4b7a52929f57c17efed727a573e3970ec208d1d1819c17ac3e5`.
- Source/class/native hash, JVM flags, per-worker 시간, raw JFR, profiler overhead controls, 원본 argv/로그를 포함한다.

**판단:** “busy waiting인가?”에는 **AQS/scheduler의 짧은 spin은 맞지만 SQLite BUSY retry는 아니며, 핵심은 VT와 세분화된 락 인계의 조합**이라고 답할 수 있다. 단순 ReentrantLock 교체나 spin 억제보다 실행 경계의 락 범위를 함께 검토할 근거를 얻었다. Production 적용의 호환성·취소·callback 안전성 검증은 이 원인 분석과 별개이며 이번에 변경하지 않았다.
