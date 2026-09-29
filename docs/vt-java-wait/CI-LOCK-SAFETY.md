# Prepared UPDATE 락 범위 변경: 동작 등가성 검증

## 결론: 현재의 전체-method outer guard는 적용하지 않는다

**성능 실험에서 사용한 넓은 락 범위를 실제 prepared-update 메서드에 적용하자, 기존 범위에서는 완료되던 공개 확장 경로에서 새로운 DB/metadata 교착이 재현됐다.** 닫힌 statement·잘못된 실행 메서드의 오류 반환 시점과 확장 hook 내부 다른 스레드의 SQL 진행·관측값도 달라졌다.

기존 회귀 테스트는 통과했지만 이것만으로 안전성을 판단할 수 없었다. Production driver의 락은 변경하지 않았고, 생성된 실험 classpath에서만 검증했다. 이번 결론은 “어떤 락 범위 변경도 불가능하다”가 아니라 **현재의 무조건적인 전체-method wrapper가 동작 등가성을 만족하지 않는다**는 것이다.

## 검증 대상과 분리한 축

정확한 후보는 `JDBC3PreparedStatement.executeLargeUpdate()`의 **본문 진입부터 종료까지**, `checkOpen()`과 `columnCount` 검사까지 포함하여 기존 DB guard를 보유하는 것이다. `executeUpdate()`는 이 메서드로 위임한다. 다른 `execute`/`executeQuery`/batch/close entrypoint의 범위를 넓히지 않았다.

| 실험 classpath | 락 구현 | prepared update 범위 |
|---|---|---|
| fork | 기존 monitor | 기존 세분화된 범위 |
| fork-outer | 기존 monitor | 메서드 본문 전체 |
| reentrant | 기존 실험용 ReentrantLock 후보 | 기존 세분화된 범위 |
| reentrant-outer | 같은 ReentrantLock 후보 | 메서드 본문 전체 |

각 primitive 안에서 원래 범위와 outer를 비교했다. `fork → reentrant`의 primitive 변경과 `기존 → outer`의 범위 변경을 섞어 회귀로 분류하지 않았다. 별도 application lock이나 SQL mock은 없다. 네 후보는 같은 JNI를 사용하며, fault 단계만 별도로 빌드한 공통 fault JNI를 사용한다.

## 실행 증거

- [Linux CI 36526677256](https://github.com/Clickin/sqlite-jdbc/actions/runs/36526677256), source `bed4caad757168b6f0295b8980a87fe35c8908a0`.
- **29개 시나리오 × 4개 후보 × 2회 = 232개 독립 JVM 관측**, 모두 완료·유효. 같은 조건의 두 반복 관측은 모두 일치했다.
- 락 범위에 따른 **4개 시나리오의 동작 변화**가 두 primitive에서 각각 나타났다. 따라서 JSON의 `scope_changes=8`은 서로 다른 버그 8개가 아니라 4개 시나리오 × 2쌍이다.
- 확정적인 교착 검출은 outer 두 후보에서 각 2회, **4회**다. 기존 범위 두 후보에서는 같은 시나리오가 완료됐다.
- 기존 회귀: 각 후보 정상 JNI **324개 수집, 315개 통과, 9개 skip**, 별도 fault JNI **5개 통과, skip 0**. 네 후보 합계 **1,280회 통과, 실패/오류 0**이다.
- macOS arm64/Temurin25.0.2에서도 동일한 232개 관측과 회귀 검증을 수행했고 같은 차이를 확인했다. 로컬은 기존 28개 시나리오 224회와 추가 timeout race 8회로 나눠 실행했다.
- GitHub conclusion은 **failure**다. 빌드나 기존 회귀의 실패가 아니라 **관측된 차이·교착 때문에 채택 gate가 차단된 결과**다. `observations_complete=true`, `regressions_passed=true`, `adoption_gate_passed=false`를 구분했다.
- 완료 observer는 REST 9회 조회 후 364.7초에 최종 callback을 전달했다. `gh watch` 없이 종료 뒤 artifact를 조회했다.

## 1. 새 교착: 공개 override가 이전에는 DB 락 밖에서 실행되던 구간

### 조건

`JDBC4Connection`을 상속하여 공개·non-final `tryEnforceTransactionMode()`를 override하고, `super` 호출 뒤 metadata를 조회하는 연결을 사용했다. 동시에 다른 스레드가 동일 metadata의 `getTables()`를 호출한다.

이것은 SQLite가 금지하는 commit/update hook 내부 SQL 호출을 실행한 것이 아니다. **Java 연결 클래스의 공개 override 후처리**다. 따라서 기본 DriverManager 연결에서 항상 교착한다는 주장이 아니라, 이 확장/동시 접근 조건의 호환성 회귀다.

### 기존 범위

`super.tryEnforceTransactionMode()`가 반환하면 내부 DB guard는 풀려 있다. Override의 metadata 조회는 DB를 보유하지 않은 상태에서 metadata monitor를 얻는다. 두 작업이 모두 완료된다.

### 넓어진 범위

```text
스레드 A: executeLargeUpdate
  DB guard 보유
  → override 후처리
  → metadata.getTables의 metadata monitor 대기

스레드 B: metadata.getTables
  metadata monitor 보유
  → 내부 statement 실행
  → A가 가진 DB guard 대기
```

**A는 DB → metadata, B는 metadata → DB 순서로 기다린다.** Linux ThreadMXBean이 실제 cycle을 검출했고 dump에도 다음 소유 관계가 기록됐다.

```text
scope-update:
  JDBC4DatabaseMetaData 대기, 소유자 scope-metadata
  ReentrantLock$NonfairSync 보유

scope-metadata:
  같은 ReentrantLock$NonfairSync 대기, 소유자 scope-update
```

Monitor outer에서도 동일한 cycle이 재현됐다. 즉 이 문제는 ReentrantLock 특유가 아니라 **범위를 넓히면서 외부 확장 코드가 새 DB critical section 안으로 들어온 결과**다.

검출은 ThreadMXBean의 monitor/ownable-lock cycle 관측을 위해 platform thread로 실행했다. 테스트는 metadata가 이미 사용하던 동일 monitor를 외부에서 한 번 더 잡아 획득 순서를 고정했으며, 새로운 락 객체를 도입하지 않았다. B는 실제 `getTables()` 내부에서 DB를 기다린다. 각 case는 별도 JVM이며, 검출 후 daemon actor를 억지로 unlock하지 않고 dump를 남긴 뒤 해당 JVM을 종료한다.

## 2. 확장 hook 내부 다른 스레드 SQL의 진행·snapshot 변경

같은 공개 override에서 다른 스레드에 `SELECT n FROM t`를 요청하고 bounded Future로 기다렸다. 본래 UPDATE는 `n=0 → 1`이다.

| 범위 | hook 안에서 peer SQL 완료 | peer 관측값 | 최종 값 |
|---|---|---:|---:|
| 기존 | 완료 | 0 | 1 |
| outer | DB guard 때문에 완료하지 못함 | guard 해제 뒤 1 | 1 |

데이터 유실은 없지만 **기존에 진행하던 호출이 새 critical section에 의해 막히고 snapshot 순서도 바뀌었다.** 실험은 timeout으로 hook을 빠져나와 관측을 끝냈다. `[INFERENCE]` 실제 override가 무제한 Future 대기를 한다면 자기 자신이 보유한 DB guard에 의존하는 작업을 기다리는 cycle이 된다. 위 metadata 사례와 달리 이 Future 의존성은 timeout 관측이지 ThreadMXBean으로 검출한 owned-lock cycle이라고 주장하지 않는다.

## 3. 오류 반환 경로도 새로 잠긴다

다른 statement가 같은 연결의 gated UDF에서 DB를 보유하는 동안 두 preflight 오류를 호출했다.

| 호출 | 기존 범위 | outer | 최종 오류·데이터 |
|---|---|---|---|
| 이미 닫힌 PreparedStatement의 executeUpdate | gate 해제 전에 SQLException 반환 | DB 락을 얻을 때까지 오류도 반환하지 못함 | 동일 SQLException/code0, 데이터 동일 |
| SELECT용 PreparedStatement에 executeUpdate | gate 해제 전에 SQLException 반환 | DB 락을 얻을 때까지 오류도 반환하지 못함 | 동일 SQLException/code0, 데이터 동일 |

기존 `checkOpen()`과 `columnCount` 검사는 DB guard 앞에 있다. 전체 wrapper는 이 빠른 실패 경로까지 잠근다. **외부에서 보이는 진행/latency 변화**이며, JDBC가 특정 응답 시간을 보장한다는 뜻은 아니다. 이 차이만으로 일반적인 JDBC 명세 위반이라고 단정하지 않는다.

검사를 guard 밖에 두는 것만으로 앞의 metadata cycle까지 해결되는 것은 아니다. 공개 override 호출 경계도 별도로 검토해야 한다.

## 4. 이번 범위 변경이 만들지 않은 차이

### Primitive 교체의 별도 호환성 차이

다른 스레드가 외부 `synchronized(db)` 또는 `synchronized(connection)`을 보유했을 때:

- fork/fork-outer는 SQL이 대기했다.
- reentrant/reentrant-outer는 SQL이 완료됐다.

이것은 **monitor에서 ReentrantLock으로 옮긴 효과**이며 outer 범위가 새로 만든 차이가 아니다. 기존 실험용 NativeDB subclass/constructor 호환성 한계도 그대로 있다.

### 기존 timeout 설정 race

네 후보 모두에서 다음 순서를 고정해 관측했다.

1. 설정 timeout 200ms에서 queryTimeout=1인 UPDATE가 UDF gate 안에 들어간다.
2. 다른 스레드의 `setBusyTimeout(777)`이 Java configuration을 먼저 바꾸고 native guard 획득을 기다린다.
3. UPDATE가 종료하면서 기존 timeout 200을 복원한다.
4. setter가 native timeout 777을 설치하고 반환한다.

두 호출이 끝난 뒤 **Java getter=200, native `PRAGMA busy_timeout`=777**이었다. [SQLiteConnection.setBusyTimeout](../../src/main/java/org/sqlite/SQLiteConnection.java)의 configuration 갱신이 native guard 밖에 있고, 임시 query timeout 복원과 교차하는 기존 현상이다. Native readback과 Java configuration은 원래 직접 PRAGMA 사용 등으로도 달라질 수 있지만, 여기서는 driver API 두 호출만으로 차이가 발생했다. **새 outer-scope 회귀로 분류하거나 이번 작업에서 수정하지 않았다.**

### 그 밖의 기존 error/lifecycle 관측

- UDF `SQLITE_ERROR`와 `SQLITE_READONLY` 뒤에는 내부 native statement가 finalize되어 같은 prepared statement 재사용이 거부될 수 있었다. `isClosed()`는 false인 상태였다. 새 statement를 만들어 연결 복구·rollback/commit을 검증했다. 처음 작성한 probe의 “기존 statement는 항상 재사용 가능” 가정은 잘못된 것이어서, 최종 probe는 이 동작 자체를 기록하고 새 statement 복구를 별도로 검사했다.
- constraint 실패는 같은 statement/parameter로 복구되는 경로를 검증했다. Fatal error와 같은 처리를 가정하지 않았다.
- generated-key ResultSet은 내부 Statement가 소유했다. 실패한 다음 실행 뒤 이전 key cursor가 남는 경우와, key cursor를 닫아도 caller PreparedStatement의 closeOnCompletion이 발동하지 않는 경우를 기록했다.
- commit listener의 Java 예외가 발생해도 데이터는 이미 commit될 수 있었다. Listener 예외를 commit veto로 간주하지 않았다.
- `SQLITE_INTERRUPT`로 native transaction이 이미 rollback된 뒤 JDBC autoCommit=false가 남아 후속 rollback이 실패하는 경우를 기록했다. 원래 row와 외부 관측 데이터의 복원은 검증했다.

**관측이 두 구현에서 같다는 것은 해당 기존 동작이 모든 JDBC 요구를 만족한다는 뜻이 아니다.** 범위 회귀, primitive 호환성, 기존 동작/문제를 구분해서 남겼다.

## 5. 커버리지와 회귀 테스트

29개 시나리오는 [원본 수치 JSON](ci-lock-safety-results.json)에 case별 관측으로 보존했다.

| 영역 | 시나리오 수 | 검증한 관측 |
|---|---:|---|
| lifecycle/callback | 12 | preflight, 실행 중 cancel, connection/statement close, UDF 읽기 재진입, listener getter 재진입·예외, 오류 복구, generated keys와 closeOnCompletion |
| transaction/policy | 13 | rollback, savepoint rollback/release, constraint 복구, readonly upgrade·BUSY, COMMIT/ROLLBACK 뒤 pending restart, query timeout 성공/실패 복원, setter 경합, custom busy callback 취소/interrupt |
| public extension/coordination | 4 | peer query 의존성, metadata lock cycle, 외부 DB/connection monitor 조정 |

SQLite는 update/commit hook 안에서 같은 연결의 prepare/step을 제한한다. [update hook 제한](https://sqlite.org/c3ref/update_hook.html), [commit hook 제한](https://sqlite.org/c3ref/commit_hook.html)을 따라 listener 재진입은 Java 상태 getter만 호출했다. UDF에서는 별도로 허용되는 읽기 재진입을 검사했다. Custom busy handler의 gate는 queryTimeout=0 조건이며, 기본 native timed-busy handler의 취소 semantics와 동일하다고 주장하지 않는다.

기존 suite는 prepared statements, shared statements, connections, transactions, savepoints, busy policies/handlers, UDF, listeners, progress handlers, backup lifecycle, result sets, metadata, pooled connections, serialization/configuration 등 **24개 suite**를 선택했다. 정상 JNI stage에서 후보별 9개 skip 중 2개는 fault-only t12이며 별도 fault stage에서 실행했다. 나머지 7개는 저장소에 이미 disabled되어 있던 테스트다. 새로 조용히 제외한 것은 없다.

기존 `t07ConcurrentStatementCannotBypassAFailedTransactionRestart`는 `Thread.State.BLOCKED`를 기다리고 단정하고 있었다. 이는 ReentrantLock의 WAITING과 다른 구현 상태이므로, **시작 latch로 바꾸고 최종 rollback/data 불변식은 유지**했다. WAITING으로 기대값만 바꾼 것이 아니다. Production 코드 변경은 없다.

## 6. 채택 조건과 검증 한계

현재 후보는 차단한다. 새 후보를 검토하려면 최소한:

- 기존의 validation/오류 경계와 공개 override·callback 경계를 명시적으로 보존하거나, 변경할 계약을 별도로 결정해야 한다.
- DB→metadata와 metadata→DB 같은 lock-order cycle을 만들지 않아야 한다. 같은 스레드 재진입이 된다는 사실만으로 다른 스레드의 의존성도 안전한 것은 아니다.
- 범위를 바꾼 실제 entrypoint를 통해 취소·close·timeout·트랜잭션 복구를 다시 검증해야 한다. 성공 UPDATE benchmark만으로 판단하지 않는다.

이번 검증은 모든 SQL/API, 모든 interleaving, JNI 객체 수명 전체, JDK8/21 또는 Android까지 증명한 것이 아니다. Scope 변경 대상이 아니었던 batch/query 메서드를 향후 넓혀도 안전하다는 승인도 아니다. **조건부 새 교착을 이미 발견했으므로, 이 한계를 무시한 production 적용은 하지 않는다.**

## 재현과 증거

- [scope source builder](../../scripts/benchmark/build-lock-scope-variants.py): 고정 source의 prepared update 본문만 감싼다.
- [fault JNI builder](../../scripts/benchmark/build-lock-safety-faults.py): 기존 `native-faults` target을 쓰되 정상 native resource와 공통 SQLite object가 변하지 않았는지 검증한다.
- [differential/regression runner](../../scripts/benchmark/run-lock-safety.py), [case launcher](../../scripts/benchmark/LockScopeSafety.java), [lifecycle](../../scripts/benchmark/ScopeLifecycleCases.java), [transactions](../../scripts/benchmark/ScopeTransactionCases.java), [extension](../../scripts/benchmark/ScopeExtensionCases.java).
- [전용 workflow](../../.github/workflows/vt-lock-safety.yml), [전체 결과 JSON](ci-lock-safety-results.json).
- Artifact `jdbc-lock-safety-36526677256-1`, ID `11015276462`, 30일 보존.
- GitHub 제공 artifact SHA-256: `a6827018fa310bc1019d45e98f13d2f9729efd1a4361f1904d9400fd159bb1b3`.

원본 thread dump, JVM별 관측·argv·로그, 정상/fault JUnit XML, class/source/native hash와 정확한 source diff를 보존했다. 차이를 감추려고 기존 GitHub 상태나 과거 성능 수치를 변경하지 않았다.
