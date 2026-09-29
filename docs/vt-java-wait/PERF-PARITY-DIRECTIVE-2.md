# 작업 지시서 v2: P0-0 결과 반영

작성일: 2026-09-29 · 선행 문서: [PERF-PARITY-DIRECTIVE.md](PERF-PARITY-DIRECTIVE.md), [PERF-PARITY-RESULT.md](PERF-PARITY-RESULT.md)

## 0. P0-0 결과 평가

- 중단 판단은 지시서대로 올바르게 내려졌다. 측정 절차도 신뢰할 수 있다: 공유 `sqlite3.o`, 순서 회전, validate 통과, unstripped 재프로파일.
- 다만 70% gate는 **"probe가 격차의 대부분"이라는 가설을 검증하기 위한 값**이었지, P0 자체의 가치를 판정하는 기준이 아니었다. 격차의 54.4%를 단일 원인으로 제거한다는 결과는 P0를 구현할 충분한 근거다. **P0 구현을 재개한다.**
- 남은 45.6%의 1순위 후보는 결과 보고서가 지목한 JNI restore guard다. 원자료 재분석으로 아래 §1과 같이 범위를 더 좁혔다.

## 1. 원자료 재분석으로 새로 확인한 사실

`target/vt-wait-evidence/perf-parity/*-symbols.collapsed`에서 `pthread_jit_write_protect_np`를 포함한 stack을 **직전 Java/JNI 프레임별로** 집계했다(inclusive sample).

| 직전 JNI 호출 | xerial | fork | probe plain |
|---|---:|---:|---:|
| `NativeDB.reset` (각 호출 지점 합) | 0 | ≈1,600 | ≈1,840 |
| `NativeDB.bind_int` | 0 | 309 | 360 |
| `NativeDB.step` | 0 | 51+ | ≈680 |
| `NativeDB.changes` | 0 | 284 | (소수) |
| `NativeDB.busyTimeoutReadback` / `attemptNoWaitBusy` | — | 807 / 382 | 0 |

관찰:

1. xerial의 `reset/step/bind_int`는 native 안에서 JNI 함수를 전혀 호출하지 않는다. 그래서 JIT write-protect 전환 비용이 0이다. fork는 이 함수들에 `checkBackupAccess()` → `GetBooleanField`를 추가했고, **바로 그 호출 아래에서** 전환 비용이 발생한다.
2. **대조점:** xerial의 `changes`도 `gethandle()` → `GetLongField`를 호출하지만 전환 비용이 0이다. fork의 `changes`는 `GetBooleanField`가 추가되자 284 sample이 생겼다. 따라서 "JNI 호출 일반"의 비용이 아니라 **`GetBooleanField(restoreSessionActive)`가 HotSpot의 fast JNI field accessor를 타지 못하고 VM 진입(slow path, macOS/AArch64에서는 W^X 전환 포함)을 하는 것**이 가장 유력한 원인이다. **[추정]** 이 필드는 Java에서 `volatile`이다. volatile 여부나 boolean 타입이 fast accessor 적용 여부를 가를 가능성이 있다. §2 A 실험으로 확인한다.
3. 따라서 이 비용은 **macOS에서 특히 크고, Linux에서는 형태가 다르다**(W^X 없음, VM 상태 전환 비용만 남음). 기존 Linux CI 프로파일에서 `checkBackupAccess`가 1,000요청당 9.28 sample로 작게 나온 것과 모순되지 않는다. **최종 판정은 Linux CI 기준, macOS는 보조 지표로 둔다.**

추정 비중(probe plain 기준): 전환 비용 포함 sample 4,044 / 14,224 ≈ 28% ≈ 330 ns/op이다. 이는 probe 제거 후 남은 격차(≈500 ns/op)의 약 2/3다. **P0와 guard 수정을 합치면 macOS 격차의 약 85%가 설명된다.** 나머지는 monitor slow path와 `recoverTransactionRestart` 등이다.

## 2. 작업 순서

### A. guard 원인 분리 대조 실험 (커밋 금지, 먼저 수행)

P0-0과 같은 harness와 같은 크기(10M / 2M, 3회, 순서 회전)로 다음 5개 후보를 측정한다.

| 후보 | 변경 |
|---|---|
| fork | 현재 |
| probe-plain | P0-0과 동일 |
| guard-nonvolatile | `restoreSessionActive`의 `volatile`만 제거 (native 변경 없음) |
| guard-off | statement 단위 C 함수에서 `checkBackupAccess` 호출만 제거 |
| probe-plain + guard-off | 두 변경 동시 적용 |

- 각 후보의 CPU ns/op, ops/s, `pthread_jit_write_protect_np` inclusive sample을 기록한다.
- **Linux에서도 같은 5개 후보를 실행**한다. 기존 `vt-lock-comparison.yml`을 `workflow_dispatch`로 쓰거나 전용 branch를 만든다. Linux에서는 CPU profile의 `jni_GetBooleanField` / `ThreadInVMfromNative` 계열 frame을 확인한다.
- guard-nonvolatile만으로 전환 비용이 사라지면 "volatile이 fast accessor를 막는다"는 원인이 확정된다. 사라지지 않으면 boolean field 접근 자체가 원인이다. **어느 쪽이든 아래 C의 설계(statement 함수에서 JNI field read 제거)는 유효하다.** 이 실험은 원인을 문서화하고 Linux 비중을 확인하는 목적이다.

### B. P0-1 ~ P0-3 구현 (v1 지시서 그대로)

v1 §2의 P0-1, P0-2, P0-3과 §3의 회귀 금지 계약을 그대로 따른다. 변경 사항은 다음과 같다.

- P0-2의 새 `autocommitProbe` native 함수에는 **JNI field 읽기를 넣지 않는다.** db handle이 필요하면 `sqlite3_db_handle(beginStmt)`를 사용한다.
- P0-3의 결합 `attemptNoWaitBusy`도 `gethandle()`(= GetBooleanField + GetLongField)을 호출하지 않도록 `sqlite3_db_handle(stmt)`를 쓴다.

### C. P2 재설계: statement 단위 JNI에서 VM 진입 제거 (우선순위를 P1보다 올림)

목표: `step/reset/clear_bindings/bind_*/column_*/finalize/changes/total_changes` 등 hot JNI 함수가 **xerial과 같은 수 이하의 JNI VM 호출**만 하게 한다.

1. **권장안: Java 단일 검사.** restore 중 접근 거부는 Java `SafeStmtPtr` 진입(`ensureOpen`, `close`)과 DB 단위 진입(`prepare`, `_exec`, UDF/collation 등록, `NativeDB` public 메서드)에서만 한다. statement 단위 C 함수에서는 `checkBackupAccess`를 제거한다.
   - **필수 감사:** statement 단위 native 메서드의 모든 Java 호출자를 나열한다(`grep -rn "\.step(\|\.reset(\|column_\|bind_\|clear_bindings\|finalize(" src/main/java`). 각 호출자가 이미 guard를 통과한 경로인지 표로 증명한다. 표는 커밋 메시지나 결과 문서에 남긴다.
   - 감사에서 guard를 거치지 않는 경로가 발견되면, 그 경로 앞에 Java 검사를 추가한다. C로 되돌리지 않는다.
2. **대안(감사로 커버리지를 보장할 수 없을 때만):** C에서 검사하되 JNI를 쓰지 않는다. open 시 연결별 native context를 할당하고, restore 시작/종료 시 그 안의 flag를 설정한다. statement 함수에서는 `sqlite3_db_handle(stmt)`로 context를 찾는다. 연결별 context 조회에 `sqlite3_get_clientdata`를 쓸 수 있다(SQLite ≥ 3.44). 조회 비용을 측정하고 기록한다.
3. `gethandle()`에서도 `checkBackupAccess`를 분리한다. DB 단위 함수가 JNI field read를 2회(boolean + long) 하지 않게 한다. 필요한 곳만 명시적으로 검사한다.
4. `restoreSessionActive`는 Java 검사만 남는다면 DB monitor 아래에서만 읽고 쓰는지 확인한다. 그렇다면 plain field로 둔다.
5. 검증: `BackupSessionLifecycleTest`, `BackupFaultInjectionTest`(`make test-faults`) 전부 통과. restore 중 기존 prepared statement의 `step/column/bind/close` 거부 테스트가 없으면 추가한다.

### D. P1 (v1 그대로) + 작은 hot path 정리

- v1 P1(monitor 재진입 정리, 범위 확장 금지)을 그대로 수행한다.
- `recoverTransactionRestart()`(probe plain에서 249 sample)에 fast path를 추가한다. 호출자가 이미 DB monitor를 보유한 경로에서는 `pendingTransactionRestart == null`이면 `synchronized` 재진입 없이 즉시 반환한다. monitor 밖에서 호출되는 경로는 기존 동기화를 유지한다. `t07ConcurrentStatementCannotBypassAFailedTransactionRestart`가 통과해야 한다.

### E. (선택, parity 이후) 동급 초과 후보

- DB 단위 native 메서드가 `GetLongField(pointer)` 대신 Java에서 handle을 인자로 받게 바꾼다. 이렇게 하면 xerial보다 JNI VM 호출이 적어진다. private native 시그니처 변경이므로 JNI header 재생성과 fault build를 확인한다. A~D 합격 후에만 시도한다.

## 3. 중단/보고 규칙 (v1 대체)

- 각 단계 후 macOS 로컬 수치를 기록하고 다음 단계로 **계속 진행한다.** 단일 단계의 gate로 전체를 중단하지 않는다.
- 다음 경우에만 멈추고 보고한다.
  - 회귀 금지 계약(v1 §3) 위반이 불가피한 경우
  - 기존 테스트가 실패하는데 원인이 설계 수준인 경우
  - A 실험 결과가 §1 해석과 정반대인 경우(guard-off가 macOS에서 효과 없음)
- 최종 합격 판정은 v1 §4 CI 기준(Linux, 같은 job, xerial 대비)으로 한다. macOS 기준표도 함께 보고한다.

## 4. 기대치 (검증 전 추정)

| 단계 | macOS `single` CPU ns/op 기대 | 근거 |
|---|---:|---|
| 현재 fork | 1,768 | 측정 |
| + P0 | ≈1,170 | probe-plain 측정 |
| + C (guard) | ≈840 | 전환 비용 ≈330 ns 제거 [추정] |
| + D | ≈700 ± α | monitor/restart fast path [추정] |
| xerial | 676 | 측정 |

## 5. 산출물

- `PERF-PARITY-RESULT.md`에 **새 절을 추가**한다(기존 P0-0 절은 수정 금지): A 대조 실험 표(macOS/Linux), 단계별 before/after, CI run URL, 합격 기준 표.
- 항목별 커밋: B(P0-1, P0-2, P0-3), C, D. A는 문서만 남긴다.
- `VIRTUAL-THREADS.md`: P0-1/P0-2 근거, restore guard 위치 변경(Java 단일 검사)과 감사 결과를 요약한다.
