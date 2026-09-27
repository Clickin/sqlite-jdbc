# Profile-first: 초회 timeout 중복 조회 제거

## 결론

요청한 **`openai-codex/gpt-6-luna`, thinking `max`** subagent로 먼저 profile을 수행했다. session header의 model/thinking 값과 `resolvedModelIsFallback=false`를 확인했다. 기본 herd launcher가 없는 `pi` 실행 파일을 호출해 실패했으므로, 설치된 `omp`의 별도 non-interactive process로 동일 모델을 실행했다.

**확인한 낭비는 제어문 첫 시도의 중복 live timeout 조회다.** 실제 Gateway HTTP 요청당 약 84회 제어문 실행에 timeout 조회가 약 168회 발생했다. 최소 변경 후 조회가 약 84회로 줄었다. 별도 계측 빌드에서 readback 누적 elapsed는 요청당 **48.4µs → 28.5µs**였다.

이는 원래 관찰된 HTTP 처리량 6–11% 손실의 주원인을 확정한 결과가 아니다. 정상 부하의 end-to-end 측정 편차가 커서 **전체 처리량 개선율이나 xerial 동등 성능 달성을 주장하지 않는다**. JNI 통합, connection-level timeout cache, handler 정책 변경, 일반 SQL retry, backup 알고리즘 변경은 하지 않았다.

## 1. Luna profile: 무엇이 입증되었나

원본 보고서: `target/vt-wait-evidence/profile-luna/luna-report.md`.

- 원래 HTTP-only fork JFR의 Java execution sample 553개 중 `DB.stepControl`을 포함한 것은 2개였다. Java guard 자체가 주 CPU 병목이라는 근거는 부족하다.
- `NativeDB.attemptNoWaitBusy → DB.stepControl → DB.execControl → SQLiteConnection.commit → Hikari/MyBatis → PlatformStore.write → GatewayExecutionService.execute`의 실제 애플리케이션 stack을 확인했다.
- native sample 약 94%가 `KQueue.poll`이었다. native sample count를 CPU 사용 시간이나 호출 횟수로 해석하지 않았다.
- xerial release JAR + locally built upstream native, parent Java classes + 같은 native를 각각 3회 실행했다. SQLite source ID 및 clang 16 compile option, 실제 library load path를 확인했다. 기존 공개 xerial native의 clang 11과 구분한다.
- 이 단계의 release JAR/local-native 처리량은 298.57–451.03 req/s로 크게 흔들렸다. 따라서 compiler 차이 또는 timeout 조회만으로 이전 회귀를 설명할 수 없었다.
- release Java와 local upstream native의 JNI 선언/exports 호환성을 확인했다. **fork native는 upstream native의 ABI superset이 아니므로 release JAR에 fork native를 끼워 넣지 않았다.**

Luna의 판단은 “production 최적화 확정 전 직접 비용 계측 필요”였다. 그 판단에 따라 parent가 별도 native 계측 빌드를 만들어 다음 단계를 수행했다.

## 2. 직접 계측

production source/resource를 계측용으로 덮어쓰지 않았다. `NativeDB.c`와 benchmark의 별도 복사본, 별도 `.dylib`, 별도 classes를 사용했다. 기존 `sqlite3.o`를 링크하므로 엔진 object와 compiler 조건은 동일하다.

`readBusyTimeout`의 prepare/step/finalize, control의 실제 `sqlite3_step`, handler 설치·복구를 `CLOCK_MONOTONIC`으로 계측하고 relaxed atomic counter로 합산했다. 120회 HTTP warmup 후 reset하며 20초 HTTP-only 구간만 센다. before/after 각 2회. 측정치는 **elapsed이며 CPU time이 아니고, clock/counter 계측 비용도 포함**한다. 계측 JVM의 처리량을 최종 speedup 수치로 쓰지 않는다.

| 지표 / 성공 HTTP 요청 | 변경 전, 2회 | 변경 후, 2회 |
|---|---:|---:|
| 제어문 attempt | 84.0085 / 84.0087 | 84.0084 / 84.0086 |
| live timeout 조회 | 168.0170 / 168.0174 | 84.0084 / 84.0086 |
| readback elapsed | 48.36 / 48.41µs | 28.52 / 28.50µs |
| 그중 PRAGMA prepare | 31.25 / 31.58µs | 18.39 / 18.37µs |
| handler 설치·복구 | 4.23 / 4.28µs | 4.45 / 4.46µs |
| 실제 control `sqlite3_step` | 983.84 / 1,031.35µs | 979.41 / 1,004.51µs |

before는 각각 1,503,416 / 1,466,288회 timeout 조회였고, **모든 attempt당 정확히 2회**였다. after는 756,244 / 743,140회이며 **모든 attempt당 정확히 1회**였다. 이 HTTP-only workload에서는 Java busy retry가 없었다. 호출 수 감소는 직접 관찰한 결과이고, readback 전체가 원래부터 요청당 약 0.048ms뿐이라는 점도 함께 기록한다.

원시 계측 결과 및 counter 배열:

- `target/vt-wait-evidence/profile-native-counts/run-{1,2}/result.json`
- `target/vt-wait-evidence/profile-native-counts-after/run-{1,2}/result.json`
- 각 디렉터리의 `NativeDB.c`, `GatewayBenchmark.java`, `NativeProfile.java`, `run.py`는 재현용 계측 산출물이다. production에 포함되지 않는다.

## 3. 적용한 최소 변경과 보존 조건

수정 파일은 `DB.java`, `NativeDB.java`, `NativeDB.c`와 재빌드한 macOS ARM64 native resource다.

1. `DB.stepControl`은 이전처럼 **현재 SQLite의 live timeout**을 읽어 budget과 eligibility를 정한다.
2. DB monitor를 계속 보유한 첫 attempt에만 이 값을 JNI 인자로 전달한다. 둘 사이에 JDBC의 timeout 변경이나 다른 연결 작업이 끼어들 수 없다.
3. native는 첫 attempt의 동일 PRAGMA 재실행을 생략한다.
4. 다음 retry에는 `-1`을 전달하여 이전처럼 native에서 정책을 다시 읽는다. 호출 간 timeout cache는 만들지 않았다.

유지한 동작:

- SQL `PRAGMA busy_timeout` 및 prepare-time PRAGMA 변경을 반영한다.
- zero/unknown timeout, custom BusyHandler, callback reentry의 legacy fallback을 유지한다.
- interruption/cancellation 확인 순서, timeout budget, busy marker 및 handler 복구를 유지한다.
- user SQL 재실행, 완료된 COMMIT 재실행, transaction restart 우회가 없다.
- JNI 결과는 기존 packed `long` 그대로이며 새 per-attempt 객체/배열이 없다.
- backup lifetime/restore destination guard 및 native copy 경로는 수정하지 않았다.

첫 attempt 뒤에도 readback을 생략하는 cache나, readback과 attempt를 새 packed protocol로 완전히 합치는 변경은 하지 않았다. 관찰된 절약 범위에 비해 정책/취소 경계가 복잡해지기 때문이다.

## 4. 비계측 실제 애플리케이션 재검증

기존 Gateway workload, JDK 25, carrier 4개, client 12개, 실행당 warmup 120회 + 20초, 조건별 3회. 순서를 xerial→before→after / before→after→xerial / after→xerial→before로 순환했다. 모든 측정 JVM은 순차 실행했다.

- `xerial`: released Java JAR 3.53.4.0 + local upstream native(clang 16).
- `before`: 수정 직전 Java classes와 native를 함께 보존한 snapshot.
- `after`(`fork` label): 현재 최적화 classes + normal native. 계측 native를 사용하지 않는다.

| 부하 | 드라이버 | median req/s (min–max) | HTTP p99 ms | 독립 VT p99 ms |
|---|---|---:|---:|---:|
| HTTP만 | xerial/local native | 361.16 (300.22–487.92) | 73.44 | 0.954 |
| HTTP만 | before | 268.04 (197.76–427.40) | 140.02 | 1.560 |
| HTTP만 | after | 288.99 (288.77–299.26) | 89.30 | 0.842 |
| 백업 4개 | xerial/local native | 306.18 (207.73–308.46) | 839.64 | 805.58 |
| 백업 4개 | before | 342.39 (340.64–393.49) | 106.76 | 117.72 |
| 백업 4개 | after | 430.96 (420.41–432.44) | 81.75 | 85.99 |

**이 표에서 before/after median 비율을 최적화 효과라고 주장하지 않는다.** 정상 부하의 변동 폭은 요청당 약 20µs 절약보다 훨씬 크다. 시점이 다른 이전 benchmark 결과와도 직접 speedup 비율을 만들지 않는다. 120회 warmup은 완전한 JIT steady state를 보장하지 않고 외부 host 부하도 격리하지 않았다. 다음 정밀 throughput 비교는 충분한 시간 기반 warmup과 안정된 전용 실행 환경을 먼저 확보해야 한다.

확인한 것은 다음이다.

- 18회에서 **HTTP 122,215건, backup 324건 성공**, HTTP 실패 0, source 호출 수 일치, 모든 DB integrity 정상.
- 별도 JFR pin 양성 대조군 18/18 통과. application의 SQLite-attributed pin event는 양쪽 모두 0건이었다.
- after에서도 backup stress의 독립 VT 지연은 native-only 대조군보다 훨씬 작다. **기존 carrier 진행성 이득은 유지됐다.** backup 알고리즘을 건드리지 않았으므로 그 이득을 이번 중복 조회 제거의 효과로 돌리지 않는다.
- **일반 부하에서 xerial 동등 이상 성능은 아직 입증되지 않았다.**

Native SHA-256:

- upstream/local: `411321ab791efd181101a5fa42c74ebefd0f9bb60a3502fb9809b22394e5277e`
- before: `3c762b0bea1989419eb65224a944145f66ba2fc6e1608d8f59aef72c29e396a8`
- after: `77f77619507a83533f6f6b5178a5d9ef1164343fcbedb4394374aeab2b2b940e`

세 variant는 동일 SQLite source ID와 clang 16 compile option을 보고했다. 정확한 argv, library override, hash와 source snapshot은 comparison 디렉터리의 metadata에 있다.

## 5. 정확성 검증

- `mvn -q test`: **521 tests, failures 0, errors 0, skipped 13**. 기존 BusyPolicyTest, ControlTransactionTest, BackupSessionLifecycleTest 및 single-carrier/JFR 검증 포함.
- `make test-faults`: backup fault 3건 + autocommit probe fault 2건 통과. 종료 시 normal native 복원.
- JDK 17 `mvn -q spotless:apply spotless:check`: 통과.
- JDK 8 `bash scripts/vt-java8-smoke.sh`: `JAVA8-SMOKE-PASS`.
- 새로운 source-text/호출 횟수 영구 테스트는 추가하지 않았다. 정책·취소·재시도 결과는 기존 실제 DB 테스트로 검증하고, 비용/호출 수는 계측 산출물로 검증했다.

## 6. 재현과 산출물

재사용 가능한 benchmark runner에 `--before-classes`, `--xerial-native-dir`를 추가했다. ABI 호환성을 확인한 upstream library만 override해야 한다.

```sh
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.2-tem"
python3 scripts/benchmark/run-gateway.py --prepare \
  --output target/vt-wait-evidence/profile-optimization/comparison \
  --before-classes target/vt-wait-evidence/profile-optimization/before/classes \
  --xerial-native-dir ../sqlite-jdbc-parent-wt/target/sqlite-3.53.4-Mac-aarch64 \
  --seconds 20 --carriers 4 --repetitions 3 --scenarios 0 4
```

위 before snapshot은 **코드 변경 전**에 보존한 것이다. 현재 classes를 복사해 before라고 이름 붙이면 재현이 아니다. 기존 결과를 덮어쓰지 않도록 새 output 디렉터리를 사용한다.

계측 native 재현은 다음 명령 형태로 각 계측 디렉터리의 C를 컴파일한 뒤 그 디렉터리의 `run.py`를 실행한다. 정상 resource를 바꾸지 않는다.

```sh
P=target/vt-wait-evidence/profile-native-counts-after
clang -Itarget/sqlite-3.53.4-Mac-aarch64 -Itarget/sqlite-amalgamation-3530400 \
  -Ilib/inc_mac -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  -Itarget/common-lib -Os -fPIC -mmacosx-version-min=10.9 \
  -fvisibility=hidden -Wno-implicit-function-declaration \
  -c "$P/NativeDB.c" -o "$P/NativeDB.o"
clang -dynamiclib -o "$P/libsqlitejdbc.dylib" "$P/NativeDB.o" \
  target/sqlite-3.53.4-Mac-aarch64/sqlite3.o
python3 "$P/run.py"
```

before 계측은 기존 JNI signature의 header/classes와 짝을 맞춰야 한다. 새 header와 옛 C를 섞으면 같은 실험이 아니다. 원시 측정용 C/classes/library와 결과를 각 before/after artifact에 그대로 보존했다.

- [기계 판독 결과](profile-optimization-results.json)
- 기존 [애플리케이션 benchmark](APPLICATION-BENCHMARK.md)
- Luna 분석/JFR view: `target/vt-wait-evidence/profile-luna/`
- direct counter/timer: `target/vt-wait-evidence/profile-native-counts{,-after}/`
- immutable before 및 최종 비교: `target/vt-wait-evidence/profile-optimization/`
