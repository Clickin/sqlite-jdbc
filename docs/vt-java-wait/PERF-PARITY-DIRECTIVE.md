# 작업 지시서: native-only(xerial) 대비 성능 동등 이상 달성

작성일: 2026-09-29 · 대상 브랜치: `vt-java-wait` · 대상: 구현 담당 agent

## 0. 목표와 현재 위치

**목표:** VT 친화 기능(Java-side busy wait, backup/restore Java 루프, 트랜잭션 재시작 보호)을 유지한 채, 비경합 및 일반 부하에서 upstream xerial(native wait only)과 **동급 이상**의 성능을 달성한다.

현재 측정값(모두 같은 toolchain CI, 기존 보고서 기준):

| 측정 | xerial | 현재 fork | 격차 | 출처 |
|---|---:|---:|---:|---|
| `single` prepared UPDATE CPU µs/op | 1.846 | 4.077 | **+121%** | CI-LOCK-COMPARISON.md |
| `private16` CPU µs/op | 3.251 | 7.281 | +124% | 同 |
| `shared16` ops/s | 367,325 | 105,601 | −71% | 同 |
| Gateway HTTP-only 처리량 | 기준 | −2.9% ~ −4.8% | | CI-COST-PROFILE.md, CI-BENCHMARK.md |

**중요한 해석:** 락 구조를 바꾼 후보(flat, ReentrantLock)는 `single`에서 4.077 → 4.076 / 3.608µs로 격차의 극히 일부만 줄였다. 즉 **2.2배 격차의 주원인은 락이 아니다.** 지금까지의 조사는 락에 집중했지만, 아래 코드 리뷰 결과 주원인은 autocommit 모든 문장 뒤에 도는 호환성 probe가 매번 wait 경계를 타는 것이다.

## 1. 근본 원인 (코드 리뷰 결과)

### 1.1 [주원인] autocommit probe가 매 문장마다 `PRAGMA busy_timeout`을 새로 compile한다

autocommit 모드에서 `DB.execute(CoreStatement, …)`가 `SQLITE_DONE`을 받으면 `ensureAutoCommit(true)`를 호출한다 (`src/main/java/org/sqlite/core/DB.java:1225`). upstream은 여기서 캐시된 `begin;`/`commit;` 문장을 **plain `step` 2회**만 실행한다.

fork는 `ensureAutocommit(beginPtr, commitPtr)` (`DB.java:1505` 부근)에서 두 번 모두 `stepControl()`을 거친다. `stepControl`의 호출마다 다음이 실행된다.

1. `busyWaitEligible()`: ThreadLocal 조회, volatile 읽기, `hasDriverBusyHandler`
2. `busyTimeoutReadback()` JNI 1회 → C의 `readBusyTimeout()`이 **`sqlite3_prepare_v2("PRAGMA busy_timeout")` + step + finalize**를 수행한다 (`NativeDB.c` `readBusyTimeout`). SQL parse/codegen/VDBE 할당이 매번 일어난다.
3. `attemptNoWaitBusy()` JNI 1회 → `sqlite3_busy_handler` 설치, step, `sqlite3_busy_timeout` 복구.

따라서 **UPDATE 한 건마다 PRAGMA prepare/finalize 2회, handler 교체 2쌍, 추가 JNI 2회**가 붙는다. CI-COST-PROFILE의 요청당 호출 수(readback 84.04 = no-wait attempt 84.04, ordinary step 495 → 411)가 정확히 이 구조와 일치한다.

**그런데 이 probe 두 문장은 대부분 busy handler를 호출할 수 없다.**

- `BEGIN`(DEFERRED)은 lock을 잡지 않는다. `sqlite3BeginTransaction`은 `type!=TK_DEFERRED`일 때만 `OP_Transaction`을 생성한다 (`target/sqlite-amalgamation-3530400/sqlite3.c:131788-131815`). `OP_AutoCommit`만 실행하므로 busy handler에 도달할 경로가 없다. `AUTOCOMMIT_PROBE_BEGIN`과 `BEGIN_DEFERRED`는 wait 경계가 필요 없다.
- probe `COMMIT`은 **write 트랜잭션이 남아 있을 때만** lock 승격(RESERVED→EXCLUSIVE)을 하며 busy handler를 부를 수 있다. 활성 SELECT 중 autocommit 쓰기가 남은 경우처럼 probe가 존재하는 이유가 바로 이 경우다. 이외(`sqlite3_txn_state(db, NULL)`이 `NONE`/`READ`)에는 lock 승격이 없다. WAL auto-checkpoint는 PASSIVE이므로 busy handler를 부르지 않는다. 구현 전에 반드시 재확인할 것.

### 1.2 [부원인] `db → conn → db` monitor 재진입으로 인한 monitor inflation

`JDBC3Statement.withConnectionTimeout`(`JDBC3Statement.java:472`)이 `synchronized(db)`를 추가했다. 그 안의 lambda는 `synchronized(conn)`을 잡고, 다시 `synchronized` 메서드인 `DB.prepare/execute/executeUpdate`에 진입한다. JDK 25 lightweight locking은 **lock stack 맨 위가 같은 객체일 때만** fast recursive enter를 허용하므로 NativeDB monitor가 inflate된다. CI-COST-PROFILE에서 `complete_monitor_locking_C`가 1,000건당 57 sample 증가했고, JFR `JavaMonitorInflate`가 fork에서만 관측됐다.

`PreparedStatement.executeUpdate` 한 번에 DB monitor 구간도 최소 3개로 나뉜다: `safeRunConsume(reset)`, `tryEnforceTransactionMode`, `withConnectionTimeout`. 그 내부에서도 `DB.executeUpdate → execute → safeRunInt → execute(ptr)` 등이 여러 번 재진입한다. `shared*` 시나리오에서 락 인계 비용의 원인이다 (CI-LOCK-CAUSE.md).

### 1.3 [소원인] hot path의 이중 restore-guard 검사

- C: `checkBackupAccess()` (`GetBooleanField`)가 `step/reset/bind_*/column_*` 등 **모든 statement 단위 JNI 함수**에 추가됐다. Gateway 요청당 6,845회다.
- Java: `SafeStmtPtr.ensureOpen()`이 `db.checkBackupAccess()`를 호출하므로 같은 검사가 중복된다. `restoreSessionActive`는 volatile이다.

### 1.4 [소원인] 기타

- `detectSetlkTimeoutCapability()`가 **연결마다** `PRAGMA compile_options`를 prepare하고 순회한다. 엔진은 프로세스 단위로 고정이므로 `sqlite3_compileoption_used("ENABLE_SETLK_TIMEOUT")`를 static으로 1회만 호출하면 된다. pool churn이 있는 workload에 영향이 있다.
- UDF/aggregate/collation/listener 호출마다 `DB.enterNativeCallback()/exitNativeCallback()`이 `ThreadLocal.get()`을 2회 호출한다. UDF-heavy workload에서만 의미가 있다.

## 2. 작업 항목 (우선순위 순)

각 항목은 **별도 커밋**으로 한다. 각 커밋 뒤 §4의 로컬 측정을 실행해 before/after를 기록한다.

### P0-0. 가설 확인 (코드 변경 전, 필수)

코드를 고치기 전에 §1.1이 주원인인지 수치로 확인한다.

1. 로컬(macOS arm64, JDK 25)에서 `JdbcLockBenchmark single`을 xerial / fork로 실행해 baseline을 확보한다.
2. 실험용 패치(커밋하지 않음): `ensureAutocommit` 안의 `stepControl(...)` 두 곳을 `step(ptr)`으로 바꾼 fork를 같은 조건으로 측정한다.
3. 이 패치만으로 `single` CPU/op 격차의 대부분(목표: 70% 이상)이 사라지는지 확인한다. 사라지지 않으면 **멈추고** async-profiler CPU 프로파일로 `single`의 fork-only frame을 다시 분석해 보고한다.

### P0-1. DEFERRED BEGIN과 probe BEGIN을 wait 경계에서 제외

- `stepControl`에서 `BEGIN_DEFERRED`, `AUTOCOMMIT_PROBE_BEGIN`은 곧바로 `step(ptr)`을 호출한다. `ControlStatement`에 `mayInvokeBusyHandler()` 같은 속성을 두고, 근거로 amalgamation 라인을 주석에 적는다.
- **증명 테스트 추가:** 다른 연결이 RESERVED/EXCLUSIVE lock을 보유한 상태에서(rollback journal, WAL 둘 다), 드라이버 `BusyHandler`를 등록하고 DEFERRED BEGIN을 실행한다. callback 호출이 0회임을 단정한다. SQLite 버전이 올라가 이 전제가 깨지면 테스트가 실패해야 한다.
- 확인됨: `BusyPolicyTest`의 경합 BEGIN은 IMMEDIATE 모드(`BusyPolicyTest.java:31`)를 사용하고, `ControlTransactionTest`의 BEGIN 경합 waiter도 IMMEDIATE/EXCLUSIVE다. 그래도 DEFERRED BEGIN의 Java wait를 단정하는 테스트가 남아 있는지 전수 확인한다. 있으면 기대값을 정정하되 **테스트를 삭제하지 말고, 이유를 커밋 메시지에 남긴다.**

### P0-2. probe COMMIT은 write 트랜잭션이 있을 때만 wait 경계 사용

- 새 native 함수 `autocommitProbe(beginPtr, commitPtr)` 1개로 probe 전체를 JNI 1회에 처리하는 것을 권장한다.
  - begin step → `SQLITE_DONE`이 아니면 reset 후 "트랜잭션 중" 반환 (기존 semantics 유지).
  - `sqlite3_txn_state(db, NULL) != SQLITE_TXN_WRITE`이면 commit을 plain step한다.
  - write 트랜잭션이면 "wait 경계 필요" 결과를 반환하고, Java가 기존 `stepControl(AUTOCOMMIT_PROBE_COMMIT, …)` 경로로 처리한다.
  - 반환값은 packed long(rc + 상태 비트)으로, 배열 할당 없이 처리한다.
- **이 경로의 정확성 요구사항:** 기존 T12(fault-injected probe COMMIT BUSY → rollback, DML 1회 실행, autoCommit 유지), exactly-once side-effect 테스트가 그대로 통과해야 한다. fault 주입 지점(`gTestFailNextAutocommitProbeCommit`)이 새 함수에서도 동작하도록 옮긴다.
- **추가 테스트:** "활성 SELECT + autocommit INSERT + 다른 연결이 SHARED lock 보유" 조건에서 probe COMMIT이 실제로 Java wait에 들어가는지(`javaWaitObservations` 증가) 확인한다. 동시에 비-write 경우에는 readback/attempt 호출이 0회인지 NativeDBHelper 카운터 등으로 단정한다.

### P0-3. 남은 eligible 경로의 readback 비용 제거

P0-1과 P0-2 이후에도 BEGIN IMMEDIATE/EXCLUSIVE와 명시적 COMMIT(`commit()`, `setAutoCommit(true)`, read-only upgrade)은 wait 경계를 탄다. Gateway처럼 명시적 트랜잭션이 많은 workload에서는 이 비용이 남는다.

1. **readback을 attempt에 합친다.** `busyTimeoutReadback()` JNI를 없애고 `attemptNoWaitBusy`가 timeout을 직접 읽는다. timeout이 0 이하이면 legacy plain step을 수행하고 "legacy" 비트를 반환한다. 결과는 packed long(`rc`, busyObserved 비트, legacy 비트, 읽은 timeout 31bit)으로 반환한다. 첫 시도의 JNI 횟수가 2 → 1로 줄어든다.
2. **PRAGMA를 매번 compile하지 않는다.** 권장안(A): C 측 연결별 캐시 `sqlite3_stmt*`(`PRAGMA busy_timeout`)를 사용하고 step+reset만 한다. close 시 finalize한다. `DB.close()`의 statement 정리, restore 중 access guard와의 상호작용을 확인한다. 대안(B): amalgamation 빌드에 `db->busyTimeout`과 `db->busyHandler.xBusyHandler == sqliteDefaultBusyCallback`을 읽는 accessor를 추가한다. 이 방식은 "untracked native handler" 한계까지 해결하지만 Makefile과 외부 SQLite 링크 시나리오에 영향이 크므로, A를 먼저 하고 B는 별도 제안서로 남긴다.
3. P10/P11(SQL `PRAGMA busy_timeout` 변경이 다음 제어문에 즉시 반영) 테스트가 그대로 통과해야 한다. **Java 측 timeout 캐시를 도입하지 말 것.** 정책 정확성의 근거가 live native 값이다.

### P1. monitor 재진입 구조 정리 (범위는 넓히지 말 것)

CI-LOCK-SAFETY.md에서 **범위 확장(outer guard)은 metadata 교착 때문에 기각**됐다. 이 항목은 범위를 넓히지 않고, **이미 DB monitor를 보유한 구간 안에서 다른 monitor를 사이에 두고 DB를 다시 잡는 것**을 없애는 작업이다.

1. `synchronized(conn)` 사용처 전체와 `conn → db` 순서로 잡는 경로가 있는지 감사한다(`grep -rn "synchronized (conn\|synchronized(conn\|synchronized (this)" src/main/java/org/sqlite/jdbc3 src/main/java/org/sqlite/jdbc4 src/main/java/org/sqlite/core`). **fork가 추가한 `db → conn` 순서와 기존 `conn → db` 경로가 공존하면 이미 lock-order inversion(잠재 교착)이다.** 발견하면 P1보다 먼저 보고하고 수정한다.
2. conn monitor의 실제 보호 대상(generated keys 갱신 등, `CoreStatement.java:180` 주석)이 `withConnectionTimeout`의 DB monitor 구간 안에서 이미 배타적으로 보호되는지 확인한다. 그렇다면 lambda 내부 `synchronized(conn)`을 제거하거나 DB monitor보다 바깥으로 옮겨 순서를 `conn → db`로 통일한다. 외부 코드가 `synchronized(connection)`으로 드라이버와 조정하는 호환성(CI-LOCK-SAFETY §4)도 확인한다.
3. DB monitor를 보유한 호출자 전용 **비동기화 내부 메서드**(`executeLocked`, `prepareLocked` 등, 진입 시 `assert Thread.holdsLock(this)`)를 도입해 statement 경로에서 재진입 횟수를 줄인다. public/protected synchronized 시그니처는 호환성을 위해 유지한다.
4. `checkOpen()`/`columnCount` 같은 fast-fail 검사와 public override hook인 `tryEnforceTransactionMode()` 호출은 **DB monitor 밖에 그대로 둔다** (CI-LOCK-SAFETY §1, §3).
5. 검증: `JavaMonitorInflate` JFR에서 NativeDB inflation이 warmup/측정 모두 0건(또는 xerial과 같은 수준)이어야 한다. `scripts/benchmark/run-lock-safety.py`의 29개 시나리오 차등 검증을 fork vs 변경본으로 다시 실행하고 "scope_changes=0"을 확인한다.

### P2. restore guard 검사 단일화

- statement 단위 C 함수(`step/reset/clear_bindings/bind_*/column_*/finalize` 등)에서 `checkBackupAccess`를 제거한다. 검사는 Java `SafeStmtPtr` 진입(`ensureOpen`, `close`)과 DB 단위 JNI(`gethandle`, `_exec`, `prepare`, UDF/collation 등록)에만 둔다.
- 전제: statement 단위 JNI 호출이 모두 `SafeStmtPtr.safeRun*` 또는 이미 검사를 거친 DB 내부 경로에서만 일어나야 한다. **직접 호출자를 전수 감사하고 목록을 커밋 메시지에 남긴다.** `BackupSessionLifecycleTest`의 restore 중 거부 테스트가 모두 통과해야 한다.
- C 검사를 제거하면 `restoreSessionActive`는 DB monitor 아래에서만 읽고 쓴다. 그렇다면 volatile을 해제할 수 있는지 검토한다. 확신이 없으면 유지한다.

### P3. 연결별 고정 비용 정리

- setlk 감지를 `sqlite3_compileoption_used("ENABLE_SETLK_TIMEOUT")` native static 호출로 교체하고, 결과를 프로세스 전역 static에 1회만 캐시한다. 실패 시 fail-closed(legacy) semantics를 유지한다.
- callback depth ThreadLocal은 측정상 의미가 있을 때만 손댄다(UDF microbenchmark로 먼저 비용 확인).

## 3. 절대 바꾸지 말 것 (회귀 금지 계약)

- user SQL, DML, batch, multi-statement `_exec`에 retry를 추가하지 않는다. retry는 드라이버 생성 제어문의 exact `SQLITE_BUSY` + marker 관측 조건에서만 한다.
- live native `PRAGMA busy_timeout` 기반 정책 판정을 유지한다. connection-level Java 캐시로 대체하지 않는다. custom BusyHandler, timeout 0, setlk, callback 재진입은 모두 legacy fallback으로 처리한다.
- COMMIT/ROLLBACK 이후 BEGIN 실패 시 pending restart 보호와 user SQL 차단을 유지한다.
- backup/restore: DB monitor 보유 중 Java `Thread.sleep`, 세션 소유권, restore destination 접근 거부를 유지한다.
- DB monitor의 **보유 범위를 넓히지 않는다** (CI-LOCK-SAFETY 결론). ReentrantLock 전면 교체는 이번 범위가 아니다.
- `VtCarrierProgressTest`(단일 carrier child JVM에서 독립 VT 진행)가 계속 통과해야 한다. 성능을 위해 VT 이점을 포기하면 목표 실패다.

## 4. 측정 프로토콜과 합격 기준

### 로컬 반복 루프 (각 커밋마다)

```sh
make native            # 또는 NativeDB.c 변경 시 필수
mvn -q -DskipTests package
# JdbcLockBenchmark: output-dir scenario operations warmup timeout-seconds performance
# 기존 build-lock-variants.py / run-lock-comparison.py 의 classpath 구성을 재사용할 것
python3 scripts/benchmark/run-lock-comparison.py --operations 1000000 --warmup-operations 200000 --repetitions 3
```

로컬 수치는 방향 확인용이다. 합격 판정은 CI로 한다.

### 계측 기대값 (counts mode, `scripts/benchmark/run-cost-profile.py`)

| 지표 | 현재 fork | P0 후 기대 |
|---|---:|---:|
| autocommit UPDATE 1건당 readback | 2 | 0 |
| autocommit UPDATE 1건당 no-wait attempt | 2 | 0 (write 잔존 시만 1) |
| Gateway 요청당 readback | 84.04 | 명시적 IMMEDIATE/COMMIT 수 이하, PRAGMA compile 0 |
| NativeDB `JavaMonitorInflate` (P1 후) | 8 / 3-5 | 0 |

### CI 합격 기준 (같은 job, 같은 toolchain, xerial 대비 중앙값)

1. `JdbcLockBenchmark` `single`, `private16`: CPU/op **≤ xerial × 1.03**, ops/s **≥ xerial × 0.97**.
2. `shared4`, `shared16`: ops/s **≥ xerial × 0.97**. 목표는 동등 이상이다.
3. Gateway HTTP-only(`vt-benchmark.yml`): 처리량 **≥ xerial × 0.98**.
4. Gateway backup stress: 현재 우위(성공 처리량 +65%, 독립 VT p99 −56%, 요청 실패 0)를 유지한다.
5. 전체 테스트(`mvn -q test`), `make test-faults`, `BusyPolicyTest`, `ControlTransactionTest`, `VtCarrierProgressTest`, JDK 8 smoke, `mvn spotless:check`(JDK 17)가 모두 통과한다.

기준을 못 맞추면 수치를 조정하지 말고 **남은 격차를 profile로 다시 귀속해 보고**한다. 부분 달성도 그대로 기록한다.

## 5. 산출물

- 항목별 커밋 (P0-0 결과는 문서로만, 실험 패치는 커밋하지 않음).
- `docs/vt-java-wait/PERF-PARITY-RESULT.md`: 각 단계의 before/after 표, CI run URL, counts mode 호출 수, 합격 기준 충족 여부, 남은 격차와 귀속.
- `VIRTUAL-THREADS.md` "Generated transaction controls" 절 갱신: DEFERRED BEGIN과 비-write probe COMMIT이 wait 경계를 타지 않는 이유(amalgamation 근거 포함).
- 기존 보고서 수치는 수정하지 않는다. 새 결과는 새 문서에 추가한다.

## 6. 예상 효과 (검증 전 추정)

- P0 (1.1 해소): `single` 격차의 대부분. autocommit UPDATE당 SQL compile 2회와 JNI 2회가 사라지므로 4.08µs → 2µs 안팎을 기대한다. **[추정: P0-0에서 반드시 확인]**
- P1: `shared*`와 Gateway의 monitor slow-path 비용.
- P2, P3: 수 % 이하의 잔여 격차와 연결 생성 비용.
- P0-2 이후 fork는 write가 없는 경우 upstream보다 JNI 호출이 적다(probe 2회 → 1회). 따라서 **동급 이상**이 현실적인 목표다.
