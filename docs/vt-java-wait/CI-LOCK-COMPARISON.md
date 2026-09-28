# GitHub CI: monitor 정리와 ReentrantLock 비교

## 결론

**현재 증거로는 ReentrantLock 전면 전환을 권하지 않는다. 더 작은 후보는 바깥 DB 락으로 이미 보호되는 내부 `synchronized(conn)` 3개를 제거하는 것이다. 다만 이 후보의 처리량 개선까지 입증한 것은 아니다. Production driver는 변경하지 않았다.**

- 최종 flat 후보는 warmup과 측정 구간의 DB monitor inflation이 **8개 JVM 모두 0건**이었다. ReentrantLock 후보도 0건이었다.
- 최종 flat의 정상 부하 CPU/성공 요청은 현재 fork 대비 네 paired repetition 모두 감소했다: **−4.03%, −1.64%, −0.92%, −2.38%**. 각 구현 중앙값끼리 비교하면 **−2.01%**다.
- 변동이 작았던 첫 실행에서 ReentrantLock의 정상 처리량 차이는 현재 fork 대비 **+0.21%**, CPU/성공은 **−1.38%**였다. 광범위한 락 교체를 정당화할 뚜렷한 처리량 우위는 관측하지 못했다.
- 최종 flat을 측정한 두 번째 실행은 정상 처리량의 구현별 CV가 **14.15–21.58%**였다. 표의 ReentrantLock **+20.79%**, flat **−7.20%**를 확정된 구현 효과로 해석하지 않는다.
- 두 workflow 모두 `failure`다. 실행 누락이 아니라 **실제 애플리케이션 실패를 숨기지 않고 최종 exit에 반영**한 결과다. 첫 실행은 xerial 경합 부하만 실패했고, 두 번째는 fork 후보에도 경합 부하 실패가 있었다.

## 실행과 완료 통지

| 실행 | 측정 commit | flat의 정확한 의미 | 결과 |
|---|---|---|---|
| [36451753558](https://github.com/Clickin/sqlite-jdbc/actions/runs/36451753558) | `07bd0dabe97d8cb291f383519e029af6aca9093d` | conn monitor 3개를 DB monitor로 교체; 중첩 깊이는 유지 | 32/32 측정 완료, 요청 실패 192건 |
| [36457676492](https://github.com/Clickin/sqlite-jdbc/actions/runs/36457676492) | `011127b1a4e035624ffd6256e27f8eb83001e1b6` | 이미 보호된 내부 conn monitor 3개 제거; 바깥 DB guard 유지 | 32/32 측정 완료, 요청 실패 104건 |

첫 실행을 진행하던 중 로컬 JFR에서 단순 monitor 대상 교체만으로 inflation이 남는 것을 확인했다. 재진입 깊이까지 줄이는 더 작은 후보를 로컬 검증한 뒤, **첫 실행 종료 callback을 받은 후** 두 번째 전체 비교를 시작했다. 첫 실행을 취소하거나 불리한 표본을 버리지 않았다.

`gh run watch`는 사용하지 않았다. [기존 observer](../../scripts/benchmark/wait-actions.py)가 별도 백그라운드 프로세스에서 45초 간격 REST 조회를 수행하고 **최종 결과 JSON 한 번만 callback**으로 전달했다. GitHub push webhook은 아니다. 각각 REST 66회/72회, 대기 2,958.8초/3,235.2초였으며, 각 종료 통지를 받은 뒤 run 정보와 artifact를 다시 조회·다운로드했다.

전체: **64개 측정 JVM**, warmup **384,000건**, 측정 성공 **200,992건**, 실패 **296건**, 성공한 backup **1,792건**. DB integrity 검사 64/64, 양성 pin control 64/64, offline JFR 분석 192/192 완료. 정확성 검사는 7개 시나리오 × 3개 fork 후보 × 2개 CI 실행 = **42개 통과**다.

## 공통 조건과 실험 범위

- 두 job 모두 Ubuntu 24.04.5, guest가 보고한 AMD EPYC 9V74, 4 vCPU. **같은 CPU 모델은 같은 물리 host나 일정한 I/O 성능을 보장하지 않는다.** 서로 다른 job의 절대 처리량/CPU 시간을 합치거나 직접 빼지 않는다.
- Temurin **25.0.2+10**, GCC **13.3.0**, Java `--release 8`. 기준 구현은 기존 Maven compiler-plugin 3.16.0, 후보는 같은 JDK의 `javac --release 8 -g -encoding UTF-8`로 빌드했다. 최종 flat의 128개 class 중 125개는 기준 fork와 byte-identical하다. 나머지는 실행 코드가 바뀐 두 statement class와 주석 줄 수 변화로 debug line 정보가 바뀐 `CoreStatement`다.
- pinned upstream: `cab7981c19ce04d691f0675f0b2586afc2bbf803`. 기존 공개 Gateway snapshot을 그대로 사용했다.
- 한 번 빌드한 SQLite object를 공유. 두 job의 shared `sqlite3.o` SHA-256: `52c0c3e3fcdf73822de1808b2b87dd209d52acc483ced083b911190bf34c43de`.
- **fork/flat/reentrant의 JNI 바이너리는 완전히 동일**하다. SHA-256: `059c6b70bf8ffefdbb865144ba2669ef7b9e9fdddc7e824dde46aed1a6df55cd`. native 함수 이름·signature·선언 class를 바꾸거나 별도 최적화 수준으로 재빌드하지 않았다.
- Java source tree SHA-256: `00b72196db18dcb58e77febd2273153907ac4baf3e110a0260e60536f8ff56e6`. Builder는 다른 source tree나 검토하지 않은 lock 구문을 거부한다.
- 12 HTTP clients, VT carrier parallelism/maxPoolSize 모두 4, heap 512 MiB. 각 JVM warmup 6,000건 후 30초 측정.
- 일반 부하와 4개 동시 backup 경합을 각각 4회. 각 repetition에서 구현 순서를 회전해 모든 구현이 각 순서 위치에 한 번씩 오도록 했다. 모든 JVM은 순차 실행했다.
- 경합 조건: 별도 platform thread가 backup destination을 2초마다 750ms 잠금. whole-snapshot backup, 각 JVM 14회 × 4개 = 56개 완료를 검사했다.
- async-profiler/native probe 없음. warmup/측정의 `jdk.JavaMonitorInflate`, 기존 JFR pin/native sample, CPU, latency, VT heartbeat를 기록했다. Offline JFR 분석은 모든 측정 JVM 종료 뒤 수행했다.

### 구현 차이

**최종 flat:** `JDBC3Statement.execute`, `JDBC3PreparedStatement.execute`, `executeLargeUpdate`의 내부 conn monitor만 제거한다. 세 구간은 모두 `withConnectionTimeout`의 DB monitor 안에서 실행된다. SQL 실행과 generated key 저장을 함께 보호하는 바깥 DB guard는 유지한다.

**ReentrantLock:** DB당 non-fair lock 하나로 29개 synchronized block과 39개 Java synchronized method를 옮겼다. 59개 synchronized native method에는 Java guard가 필요하다. 같은 JNI 바이너리를 쓰기 위해 생성된 `NativeDB`를 abstract로 바꾸고, final `LockedNativeDB`가 해당 native method를 override하여 같은 lock 아래 `super`를 호출한다. `interrupt()`는 실행 락 밖에 그대로 두었다. Function/metadata/loader/cache의 독립 monitor는 유지했다.

이 subclass는 JNI를 동일하게 유지하기 위한 **실험용 장치**다. 직접 `new NativeDB(...)`하는 호환성과 외부 `synchronized(DB/Connection)`의 조정 의미가 바뀌며, JIT devirtualization/inlining에도 영향을 줄 수 있다. 따라서 결과는 이 **전체 후보 구현**의 비교이지 ReentrantLock primitive만의 순수 비용 비교가 아니다. flat도 외부 conn monitor에 의존한 실행 조정 의미는 바뀐다.

## 1. 정상 부하

단위는 req/s, CPU ms/성공 요청, latency ms. 모든 정상 부하 실행은 HTTP 실패 0건이다. 아래 중앙값은 **4개 JVM별 값의 중앙값**이며 p99도 JVM별 p99의 중앙값이지 요청을 합친 pooled p99가 아니다.

### 첫 실행: monitor 대상 병합, 낮은 반복 변동

| 구현 | req/s 중앙값 [최소–최대] | CPU ms/성공 | HTTP p99 ms | req/s CV |
|---|---:|---:|---:|---:|
| xerial | 155.87 [153.47–156.67] | 12.538 | 140.44 | 0.98% |
| 현재 fork | 151.68 [147.68–152.83] | 12.993 | 144.74 | 1.60% |
| flat | 150.63 [149.71–152.59] | 12.770 | 142.98 | 0.82% |
| ReentrantLock | 151.99 [150.42–153.23] | 12.814 | 145.21 | 0.94% |

이 실행의 ReentrantLock 처리량 범위는 현재 fork와 겹친다. 네 paired 처리량 차이는 **+3.62%, +0.34%, −1.22%, −0.14%**다. +0.21% 중앙값 차이를 일관된 성능 개선으로 주장하지 않는다. 이 표의 flat은 **내부 monitor 삭제 후보가 아니라 DB monitor로 교체한 후보**다.

### 두 번째 실행: 내부 monitor 삭제, 높은 반복 변동

| 구현 | req/s 중앙값 [최소–최대] | CPU ms/성공 | HTTP p99 ms | req/s CV |
|---|---:|---:|---:|---:|
| xerial | 112.43 [96.64–148.72] | 9.248 | 425.31 | 21.58% |
| 현재 fork | 120.18 [98.44–138.89] | 9.611 | 399.48 | 17.51% |
| flat | 111.53 [83.77–121.58] | 9.418 | 483.08 | 15.27% |
| ReentrantLock | 145.16 [114.31–157.40] | 9.479 | 288.37 | 14.15% |

최종 flat CPU 감소 방향은 네 repetition에서 일치하지만, 처리량 paired 차이는 **+23.51%, −16.34%, +4.68%, −39.68%**로 뒤집힌다. 변하지 않은 xerial도 **148.72 → 127.74 → 97.12 → 96.64 req/s**로 크게 흔들렸다. 따라서 단일 중앙값으로 flat의 회귀나 ReentrantLock의 대폭 개선을 확정할 수 없다.

**[INFERENCE]** 환경 또는 시간대별 wall-time 변동이 영향을 주었을 가능성이 있다. 다만 host steal time/스토리지 latency를 직접 계측하지 않았으므로 원인을 noisy neighbor, disk, fsync 중 하나로 특정하지 않는다. timer jitter p99는 약 1ms 수준이어서 모든 긴 지연을 단순 timer 지연으로 설명할 수도 없다. 표본을 사후 제외하지 않았고, 작은 표본에 통계적 유의성을 주장하지 않는다.

## 2. DB monitor inflation

정상 부하의 `NativeDB`/`LockedNativeDB` inflation 건수, repetition 1–4 순서다. warmup에 connection 생성이 포함되며 positive UDF pin control은 별도 recording이다.

| 실행 / 구현 | warmup | 측정 구간 |
|---|---|---|
| 첫 실행 / xerial | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 첫 실행 / fork | 8 / 8 / 8 / 8 | 3 / 4 / 4 / 4 |
| 첫 실행 / monitor 대상 병합 | 7 / 6 / 7 / 7 | 0 / 0 / 0 / 0 |
| 첫 실행 / ReentrantLock | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 두 번째 / xerial | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 두 번째 / fork | 8 / 8 / 12 / 8 | 4 / 4 / 0 / 2 |
| 두 번째 / 내부 monitor 삭제 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 두 번째 / ReentrantLock | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |

최종 flat과 ReentrantLock은 backup 경합 4회에서도 warmup/측정 모두 DB inflation 0건이었다. 이는 **이 workload에서 관측된 경로**의 결과이며, 실제 같은 연결 monitor 경합까지 모든 상황에서 없어졌다는 주장은 아니다.

### 왜 conn을 db로 바꾸기만 해서는 부족했나

첫 flat의 warmup stack에는 `ensureAutoCommit → SafeStmtPtr → SafeStmtPtr → detectSetlkTimeoutCapability → SafeStmtPtr → column_text → column_text_utf8` 재진입이 남았다. 바깥 `withConnectionTimeout`, statement lambda, `DB.execute`까지 포함하면 같은 monitor의 깊은 중첩이다.

[JDK 25.0.2 LockStack](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/lockStack.hpp)의 capacity는 **8**이고, [recursive enter](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/lockStack.inline.hpp)는 같은 monitor여도 stack entry를 추가한다. [LightweightSynchronizer::enter](https://github.com/openjdk/jdk25u/blob/jdk-25.0.2%2B10/src/hotspot/share/runtime/lightweightSynchronizer.cpp)는 stack이 꽉 찼으면 fast recursive enter를 건너뛰고 이미 소유한 객체를 inflate한다.

**[INFERENCE]** 남은 깊은 재진입 stack은 이 capacity 경로와 일치한다. JVM 내부 lock-stack contents 자체를 직접 덤프한 것은 아니다. **실측으로 확인한 부분**은 monitor 대상을 병합하면 inflation이 남고, 불필요한 내부 acquisition을 없앤 후보에서는 관측 inflation이 0이 됐다는 점이다. Inflation 건수를 CPU 시간이나 전체 처리량 차이의 비율로 환산하지 않는다.

## 3. 백업 경합과 VT carrier 가용성

### 첫 실행

| 구현 | req/s 중앙값 | CPU ms/성공 | HTTP p99 ms | VT heartbeat p99 ms | 실패 요청 / 실패 실행 |
|---|---:|---:|---:|---:|---:|
| xerial | 65.97 | 19.554 | 1385.33 | 1238.49 | 192 / 4 |
| fork | 109.53 | 17.491 | 740.39 | 570.43 | 0 / 0 |
| flat | 110.96 | 16.983 | 726.55 | 544.12 | 0 / 0 |
| reentrant | 110.82 | 17.181 | 732.80 | 573.28 | 0 / 0 |

### 두 번째 실행

| 구현 | req/s 중앙값 | CPU ms/성공 | HTTP p99 ms | VT heartbeat p99 ms | 실패 요청 / 실패 실행 |
|---|---:|---:|---:|---:|---:|
| xerial | 17.41 | 25.374 | 3963.97 | 1859.18 | 93 / 4 |
| fork | 50.69 | 15.929 | 1482.23 | 1163.88 | 5 / 3 |
| flat | 58.96 | 14.932 | 1445.78 | 1192.92 | 2 / 2 |
| reentrant | 51.83 | 15.615 | 1506.63 | 1246.93 | 4 / 3 |

모든 실패는 `HTTP_CONCURRENCY_LIMIT`였다. 첫 실행의 fork 세 후보는 실패 0건이지만, 두 번째에는 fork 5건/flat 2건/ReentrantLock 4건이 있었다. 실패가 적다고 모두 정상이라고 간주하지 않았다. **모든 구현의 backup 자체는 각 job에서 224/224 성공**했고 integrity 검사는 통과했다.

두 job 모두 fork 계열은 xerial보다 VT heartbeat 지연이 작았지만, 여전히 수백 ms–1초 이상의 지연이 남는다. ReentrantLock이 현재 fork보다 carrier starvation을 추가로 없앴다는 증거는 없다. **측정 구간 JFR pin event는 64개 JVM 모두 0건**이지만, 양성 UDF pin control은 모두 1건씩 기록했다. `sqlite3` native frame 안에서 carrier가 점유되는 현상은 pin event 0만으로 배제할 수 없다. NativeMethodSample은 CPU 시간으로 집계하지 않았다.

경합 표의 CPU/성공 요청은 전체 process CPU를 성공 HTTP 수로 나눈 값이다. numerator에는 backup과 실패 요청의 CPU도 포함되므로 순수 성공 요청 한 건의 실행 비용으로 해석하지 않는다.

## 4. 정확성 검사와 검증 범위

[LockCorrectness.java](../../scripts/benchmark/LockCorrectness.java)를 CI의 fork/flat/reentrant 각각에서 실제 실행했다.

1. SQL이 JNI Function callback에서 실행 guard를 보유한 상태에서도 `Statement.cancel()`이 먼저 완료되고, 실행은 SQLITE_INTERRUPT로 끝나며 다음 SQL이 성공한다.
2. 같은 연결의 concurrent close는 실행 중인 callback이 끝날 때까지 대기하고, 종료 뒤 새 statement를 거부한다.
3. JDBC가 반환한 public `NativeDB`의 timeout 변경도 실행과 직렬화되며 live readback 값이 맞는다.
4. Function value/result 재진입, update/commit listener와 제거, serialize/deserialize 데이터가 맞는다.
5. 공유 연결의 VT writer 8개가 60건씩 삽입한다. 480개 row와 각 statement의 generated key 소유 관계가 정확하다.
6. COMMIT 성공 후 자동 BEGIN 실패 시 user SQL을 잘못 auto-commit하지 않고 복구한다.
7. ROLLBACK 뒤 같은 재시작 실패·복구 의미를 보존한다.

로컬에서도 세 후보의 21개 검사와 Gateway 일반/경합 6회, 이후 내부 scope 삭제 후보의 7개 검사와 Gateway 2회를 실행했다. 로컬 숫자는 CI 성능 표에 섞지 않았다. 로컬 사전 빌드에서 누락된 slf4j compile dependency를 수정했고, 기존 로컬 multi-release 산출물은 CI의 main-Java8-only 빌드와 맞춰 smoke 입력에서 제외했다.

이번 검증은 전체 JDBC test suite, JDK 8/21 runtime, Android, 외부 DB/Connection monitor를 사용하는 타사 코드의 호환성을 보증하지 않는다. Java 8은 bytecode target이며 실제 실행 JDK는 25다. 실험 결과만으로 production cutover를 수행하지 않았다.

## 재현과 원본

- [workflow](../../.github/workflows/vt-lock-comparison.yml)
- [고정 source variant builder](../../scripts/benchmark/build-lock-variants.py)
- [순차 matrix runner](../../scripts/benchmark/run-lock-comparison.py)
- [전체 수치·paired repetition·callback·artifact identity JSON](ci-lock-comparison-results.json)
- 공개 branch: `benchmarks/vt-lock/20260928-comparison`. 첫/두 번째 측정 source는 위 commit으로 고정되어 있다. 보고서 commit과 측정 commit을 혼동하지 않는다.

Pinned JDK/GCC와 upstream checkout을 준비한 새 checkout에서 workflow와 같은 순서로 실행한다. 기존 산출물 덮어쓰기를 거부한다.

```sh
bash scripts/benchmark/build-ci.sh
python3 scripts/benchmark/build-lock-variants.py
python3 scripts/benchmark/run-lock-comparison.py --seconds 30 --warmup-requests 6000
```

Artifact에는 compiler/host 정보, source diff·lock inventory·class/native hash, 실행 argv, 원본 JFR, CSV latency, 실패 로그와 per-run JSON을 보존했다. DB 파일은 업로드하지 않는다. GitHub 보존 기간은 30일이다. 압축 artifact digest는 GitHub API가 제공한 값이다.

| 실행 | artifact ID | GitHub artifact SHA-256 |
|---|---:|---|
| 첫 실행 | 10986361257 | `77deb5f78edae6ddba6af5d427bc443d54ec8a585200874013aa5638f282743d` |
| 두 번째 | 10989420572 | `6e4dfd4b0a6b2db8555f140c8882a710503de596dce110efa83bdc850721742a` |

**판단:** 비연속 재진입뿐 아니라 불필요한 재진입 깊이도 줄여야 한다. 그 문제는 내부 monitor 3개 삭제로 해결되는 관측 결과를 얻었다. CPU 절감 신호는 있지만, **xerial과 동등 이상 처리량 달성 또는 ReentrantLock의 우월성은 아직 입증하지 못했다.**
