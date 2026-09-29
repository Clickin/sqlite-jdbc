# 동일 작업량 JDBC 락 전략 비교

## 목적과 제외 범위

**HTTP/admission/pool/disk/backup을 제거하고, 같은 SQLite 작업을 같은 횟수만큼 완료하는 비용을 비교한다.** 비교 대상은 현재 fork, 내부 conn monitor 3개를 제거한 flat, DB당 non-fair ReentrantLock 하나를 쓰는 후보이며 xerial을 대조군으로 둔다. Production driver는 변경하지 않는다.

이것은 실제 JDBC 호출을 통한 **드라이버 락 전략의 차등 비교**다. SQL·JNI·statement bookkeeping까지 포함하므로 lock/unlock primitive만의 나노초 비용이라고 주장하지 않는다. 메모리 UPDATE 결과를 Gateway 처리량, 디스크 트랜잭션 또는 모든 SQL로 일반화하지 않는다.

이전 Gateway 실험은 [과부하/admission 관측](CI-GATEWAY-OVERLOAD.md)으로 옮겼다. 기존 64개 run 수치와 GitHub conclusion은 [역사적 JSON](ci-gateway-overload-results.json)에 그대로 보존한다. 그 CPU/성공 요청·전체 Gateway 처리량을 락 비용으로 해석한 결론은 철회한다.

## 독립 변수와 고정 조건

| 시나리오 | VT worker | 연결 | 비교 목적 |
|---|---:|---:|---|
| `single` | 1 | 1 | 비경합 실행 비용 |
| `private16` | 16 | worker당 1개 | 연결 간 병렬 실행 대조군 |
| `shared4` | 4 | 공유 1개 | carrier 수와 같은 수의 작업자 경합 |
| `shared16` | 16 | 공유 1개 | carrier 수보다 많은 작업자 경합 |

모든 연결은 독립된 `jdbc:sqlite::memory:`다. shared 시나리오는 Java 연결 하나를 공유한다. worker마다 별도 PreparedStatement와 counter row를 사용한다.

```sql
UPDATE counters SET n=n+1 WHERE id=?
```

- 모든 구현/시나리오에서 warmup **총 1,000,000회**, 측정 **총 5,000,000회**. worker 수만큼 곱하지 않는다. 나머지는 낮은 worker ID부터 한 번씩 배분한다.
- 같은 연결·statement를 warmup과 측정에서 재사용한다. DB 생성·prepare·parameter 설정은 측정 밖이다. Warmup/측정 worker는 각각 VT이며 같은 명시적 barrier 규칙을 쓴다.
- 모든 worker가 ready 상태에 도달한 뒤 함께 start gate를 연다. wall-time 종료는 **모든 작업과 worker join이 끝난 시점**이다. 각 worker의 시작 offset·작업 수·수행 시간과 startup spread도 남긴다.
- CPU 분모는 계획한 전체 작업 수다. 일부 성공만으로 나누지 않는다. 자동 retry, 일정 시간 뒤 미완료 작업 버리기, 요청 거절은 없다.
- carrier parallelism/maxPoolSize **4/4**, heap **512 MiB**, JDK **Temurin 25.0.2**, Java driver bytecode **release 8**, native **GCC 13**. 기존 build identity/source·compiler 기록을 유지한다.
- fork 계열 세 후보는 **같은 JNI 바이너리**를 사용한다. xerial도 같은 shared SQLite object로 빌드한다. 각 JVM의 SQLite source ID/compile options와 JDK identity를 대조한다.
- 4회 반복 × 4개 구현 × 4개 시나리오 = **64개 성능 JVM**. 구현 순서를 회전해 모든 구현이 각 위치에 한 번씩 오며 JVM들은 순차 실행한다.

## 유효성 조건

작업을 많이 거절한 구현이 유리해지는 경로를 없앴다. 아래 중 하나라도 위반하면 해당 cell은 invalid이고 집계 CPU/op·ops/s를 내지 않으며 CI는 nonzero exit다.

1. 각 UPDATE의 affected row가 정확히 1이다.
2. 각 worker의 completed가 사전 할당량과 같고, 합계가 계획한 총량과 같다.
3. 최종 counter 값이 worker별 `warmup 할당량 + 측정 할당량`과 정확히 같다.
4. 모든 연결의 `PRAGMA integrity_check`가 `ok`다.
5. readiness·작업·join이 제한 시간 안에 끝난다. SQLException/BUSY/timeout을 숨기거나 재시도하지 않는다.
6. source/native/class provenance, Java/SQLite identity와 결과 분모가 맞는다.

[기존 public-interface correctness 검사](../../scripts/benchmark/LockCorrectness.java)도 fork 세 후보에서 먼저 실행한다. 취소, concurrent close, public native 직렬화, JNI callback, shared generated key, COMMIT/ROLLBACK 재시작 복구의 7개 시나리오다. xerial에는 fork-only 복구 semantics 검사를 강요하지 않지만 실제 작업 수와 결과 검증은 동일하게 적용한다.

## 지표와 계측 분리

Headline은 **CPU ns/operation**, **operations/second**의 최소·중앙값·최대·CV다. CPU는 JVM 전체 process delta이며 해당 구간의 GC/JIT 등도 포함한다. Per-worker duration은 작업 묶음의 시간이지 개별 SQL p99가 아니다. per-operation timer/sampler는 hot loop에 넣지 않는다.

성능 JVM에는 **JFR를 켜지 않는다.** 별도 diagnostic JVM 8개(single/shared16 × 4개 구현)에서만 monitor inflation/enter와 park를 기록한다. 기본 diagnostic은 warmup 20,000회, 측정 100,000회다. Warmup recording에는 연결 초기화가 포함된다. 진단 CPU/wall은 성능 표에 넣지 않으며, recording 분석은 모든 workload JVM이 종료한 뒤 실행한다.

CV는 각 cell 반복 값의 population standard deviation / mean이다. **CV > 5%, 측정 구간 < 5초, 또는 반복 < 2회**면 `noisy`로 표시한다. 이는 사전에 정한 품질 경고이지 통계적 유의성 검사나 불리한 표본을 버리는 기준이 아니다. 원본을 모두 보존하며 자동으로 우승자를 선언하지 않는다.

Shared connection의 실제 monitor 경합에서는 flat에도 inflation이 발생할 수 있다. ReentrantLock의 DB monitor inflation 0건만으로 더 빠르다고 판단하지 않는다. ReentrantLock 후보는 동일 JNI를 유지하기 위한 `LockedNativeDB` subclass를 포함하므로 devirtualization/inlining과 public constructor/외부 monitor 호환성의 한계도 남아 있다.

## 로컬 검증

- fork/flat/reentrant의 4개 시나리오: 각 **32,003 measured + 16,003 warmup**으로 실행하여 worker 간 나머지 배분까지 검증했다. 성능 smoke 12/12, 별도 진단 6/6, public correctness 21개가 통과했다. 짧은 smoke 값은 성능 결론에 사용하지 않는다.
- 별도 Gateway admission 검증은 실제 HTTP source를 2.5초 지연시켜 12개 동시 요청 중 **4개 성공·8개 admission 거절·기타 오류 0개**를 관측했다. 성공 4개와 실제 source 호출 4개가 일치했다.
- 기존 Gateway strict 경로와 opt-in overload 경로를 실제 실행했다. strict 1,127건, overload 1,082건 성공, 요청 실패 0건, integrity 정상이다. Structured error 분류 self-check도 통과했다.

## 실행과 결과 경로

[락 workflow](../../.github/workflows/vt-lock-comparison.yml)는 `benchmarks/vt-lock/**` 또는 workflow_dispatch로 실행한다. [과부하 workflow](../../.github/workflows/vt-gateway-overload.yml)는 `benchmarks/vt-overload/**`로 분리했다.

Pinned JDK/GCC와 upstream checkout을 준비한 새 checkout에서:

```sh
bash scripts/benchmark/build-ci.sh --drivers-only
python3 scripts/benchmark/build-lock-variants.py
python3 scripts/benchmark/run-lock-comparison.py \
  --operations 5000000 --warmup-operations 1000000 --repetitions 4
```

`--drivers-only`는 Gateway/Gradle을 빌드하지 않는다. 기존 source fixture identity 검증과 native build 경로는 재사용한다. 이미 있는 결과 디렉터리는 덮어쓰지 않는다.

- [JDBC workload](../../scripts/benchmark/JdbcLockBenchmark.java)
- [고정 작업량 runner](../../scripts/benchmark/run-lock-comparison.py)
- [source variant builder](../../scripts/benchmark/build-lock-variants.py)
- 결과: `target/vt-wait-evidence/ci-lock-comparison/`
- `results.json`/CSV/`summary.json`/`summary.md`: 성능 JVM만 포함.
- `diagnostics.json`/JFR/`analysis.json`: 별도 계측 결과.
- `environment.json`, source/class/native manifests, correctness와 각 JVM의 raw `result.json`·runner 판정·argv·로그를 보존.
- Artifact: `jdbc-isolated-lock-*`, 30일 보존. Gateway의 `ci-gateway-overload-*`와 섞지 않는다.

Hosted 실행은 기존 completion-only observer를 사용한다. 진행 로그를 스트리밍하지 않고 종료 callback 뒤 artifact를 조회·분석한다.
