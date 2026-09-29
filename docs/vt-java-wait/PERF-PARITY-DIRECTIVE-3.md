# 작업 지시서 v3: guard 가설 기각 이후

작성일: 2026-09-29 · 선행: [v1](PERF-PARITY-DIRECTIVE.md), [v2](PERF-PARITY-DIRECTIVE-2.md), [결과](PERF-PARITY-RESULT.md) §A

## 0. §A 결과 평가

- 중단 판단은 올바르다. v2의 "`GetBooleanField`/volatile이 JIT write-protect 비용의 원인"이라는 가설은 **기각**한다. v2 §1의 해석이 틀렸다.
- 다만 v2가 B(P0 구현)까지 A의 결과에 묶어 둔 것도 잘못이었다. P0 효과(격차의 54%, macOS와 Linux arm64 모두 재현)는 guard와 무관하다. **B는 이번 v3에서 A′와 병렬로 즉시 진행한다.**
- v2 P2(C guard 제거)는 **보류한다.** macOS에서 효과가 없고 Linux에서도 변동 범위 안이다. 안전성 감사 비용에 비해 이득이 없다.

## 1. 새 가설: inflate된 NativeDB monitor에서 `synchronized native` wrapper가 매번 slow path를 탄다

### 근거 (기존 원자료)

1. `perf-parity-guard-isolation/macos/profile-guard-off/cpu.collapsed`: `pthread_jit_write_protect_np`가 **`NativeDB.reset`(Java native 메서드 프레임) 바로 아래**에 있다. 그 사이에 C 함수 `Java_org_sqlite_core_NativeDB_reset` 프레임이 없다. 비용은 C 본문이 아니라 **JVM이 생성한 native wrapper**에서 발생한다. 그래서 C guard를 제거해도 변화가 없었다.
2. 해당 메서드는 모두 `public synchronized native`다 (`NativeDB.java:202 changes`, `:214 step`, `:218 reset`, `:222 clear_bindings`, `:282 column_int`, `:290 bind_int`). xerial도 같은 선언이다.
3. fork에서만 NativeDB monitor가 **연결 초기화 중에 inflate된다**: JFR warmup 1건, cause `Monitor Enter`, stack `SQLiteConnection.<init> → SQLiteConfig.apply → JDBC3Statement.execute → withConnectionTimeout: synchronized(db) → synchronized(conn) → DB.prepare: synchronized(db)`. xerial은 0건이다 (CI-COST-PROFILE §2, RESULT P0-0 JFR 표). probe-plain과 guard-off는 이 경로를 바꾸지 않으므로 inflation이 그대로 남는다.
4. Linux x86_64 CI 프로파일에서 fork에만 `SharedRuntime::complete_monitor_locking_C` 57.16, `complete_monitor_unlocking_C` 20.20 sample(1,000요청당)이 있었다. xerial은 0이다 (CI-COST-PROFILE §2). macOS P0-0 프로파일의 `ObjectMonitor::*`, `SharedRuntime::monitor_exit_helper`도 fork에만 있었다.

### 추정 메커니즘 [검증 필요]

JDK 25의 lightweight locking에서 **컴파일된 Java 코드(C2)의 monitor fast path는 inflate된 monitor도 inline으로 처리**한다(owner CAS, recursion 증가). 그러나 `synchronized native` 메서드의 **native wrapper**는 `MacroAssembler::lightweight_lock`을 쓴다. mark word가 monitor(0b10)이면 곧바로 slow path(`SharedRuntime::complete_monitor_locking_C`/`complete_monitor_unlocking_C`, VM 진입)로 간다. macOS/AArch64에서는 VM 진입마다 W^X 전환(`pthread_jit_write_protect_np`)이 붙는다.

결과적으로 **fork의 NativeDB는 태어날 때부터 inflate되어 있고, statement 한 건의 모든 `synchronized native` 호출(reset, bind, step, changes, …)이 VM 왕복을 2회씩 한다.** xerial은 monitor가 inflate되지 않아 wrapper의 lock-stack fast path(caller가 같은 객체를 top에 보유 → recursive fast enter)만 탄다.

확인할 JDK 소스(jdk25u, tag `jdk-25.0.2+10`):
- `src/hotspot/cpu/aarch64/sharedRuntime_aarch64.cpp`, `generate_native_wrapper`의 lock/unlock 부분
- `src/hotspot/cpu/aarch64/macroAssembler_aarch64.cpp`, `lightweight_lock`/`lightweight_unlock`의 monitor bit 검사
- x86_64도 같은 구조인지 `cpu/x86`에서 확인한다(Linux CI 대상).

## 2. 작업

### A′. 메커니즘 검증 실험 (커밋 금지, B와 병렬)

같은 harness, `single` 10M/2M, 3회, 순서 회전, macOS와 가능하면 Linux로 측정한다.

| 후보 | 변경 | 목적 |
|---|---|---|
| fork | 현재 | 기준 |
| probe-plain | P0-0과 동일 | 기준 |
| no-inflate | `withConnectionTimeout`의 `synchronized(conn.getDatabase())`만 제거(upstream 구조로 복원, **unsafe 실험**) | inflation 제거 효과 |
| java-sync-wrapper | 아래 C′-2 방식으로 hot native 6~10개만 변환 | wrapper slow path 회피 효과 |
| probe-plain + no-inflate | | 결합 |
| probe-plain + java-sync-wrapper | | 결합 |

필수 관측:
1. **직접 증거:** async-profiler를 `cstack=vm`(또는 `vmx`)으로 실행해 `NativeDB.reset → [VM 프레임] → pthread_jit_write_protect_np` 사이의 프레임을 확인한다. `complete_monitor_locking_C`/`unlocking_C` 계열이면 메커니즘이 확정된다.
2. 각 후보의 JFR `jdk.JavaMonitorInflate`(warmup/측정). no-inflate는 0이어야 한다.
3. CPU ns/op, ops/s, `pthread_jit_write_protect_np` inclusive sample.

기대: no-inflate나 java-sync-wrapper에서 `pthread_jit_write_protect_np` sample이 xerial 수준(≈10)으로 떨어진다. probe-plain과 결합하면 xerial 대비 +10% 이내가 된다. **[추정]**

**판정 규칙:** 둘 중 하나라도 JIT sample을 80% 이상 줄이면 가설을 채택하고 C′로 간다. 둘 다 효과가 없으면 멈추고 `cstack=vm` 프로파일을 첨부해 보고한다. 이 경우에도 B는 계속한다.

### B. P0-1 ~ P0-3 구현 (즉시 진행)

v1 §2 P0-1/2/3과 v2 §2.B의 수정 사항을 그대로 따른다. 새 native 함수는 `sqlite3_db_handle(stmt)`를 쓰고 JNI field를 읽지 않는다. A′의 결과와 무관하게 커밋한다. 각 커밋 후 전체 테스트와 `make test-faults`를 실행한다.

### C′. inflation 대책 (A′ 채택 시)

두 가지 모두 구현한다. C′-1은 fork가 만든 회귀를 없애고, C′-2는 경합으로 inflation이 불가피한 상황(shared*, Gateway)에서 xerial을 넘어서기 위한 것이다.

**C′-1. fork가 만든 non-top 재진입 제거 (회귀 제거)**
- 목표: 비경합 경로에서 NativeDB monitor inflation 0건. 연결 초기화 포함, xerial과 동일해야 한다.
- 원인 구조: `withConnectionTimeout`이 DB를 잡은 채 lambda가 `synchronized(conn)`을 잡고, 그 안에서 DB의 synchronized 메서드에 다시 들어간다. 해결 방향은 둘 중 하나다.
  - (a) `withConnectionTimeout`이 `conn → db` 순서로 둘 다 잡고, 내부 lambda의 `synchronized(conn)`을 제거한다. lambda는 private이므로 내부 변경이다.
  - (b) conn monitor가 보호하던 상태(generated keys 등)를 DB monitor가 이미 배타적으로 보호함을 증명하고, 해당 `synchronized(conn)`을 제거한다.
- **어느 쪽이든** 전 코드의 `conn`/`db`/metadata monitor 획득 순서를 표로 만든다. 순서 역전이 없음을 보이고, `run-lock-safety.py` 29개 시나리오 차등 검증에서 `scope_changes=0`을 확인한다. 외부 코드가 `synchronized(connection)`으로 드라이버와 조정하는 호환성(CI-LOCK-SAFETY §4)이 바뀌면 결과 문서에 명시한다.
- DB monitor 보유 범위를 넓히지 않는다(v1 §3).

**C′-2. hot `synchronized native`를 synchronized Java wrapper + private native로 변환 (inflation 내성)**
- 예시: `public synchronized int step(long s) { return step0(s); }` + `private native int step0(long s);`. public 시그니처와 동기화 semantics는 그대로 유지한다.
- 대상: statement 한 건 경로에서 호출되는 것부터 적용한다. `step, reset, clear_bindings, changes, total_changes, bind_*, column_*, bind_parameter_count, column_count, finalize`. 호출 수는 CI-COST-PROFILE §3 counts로 우선순위를 정한다.
- JNI 심볼 이름이 바뀌므로 **모든 플랫폼 native를 다시 빌드**해야 한다. fork는 이미 새 JNI 함수를 추가했으므로 추가 부담은 같다. `NativeDB.h` 재생성, `make native-faults`, JDK 8 smoke(`scripts/vt-java8-smoke.sh`)를 확인한다.
- 검증: shared4/shared16에서 xerial도 inflation을 겪는다(CI-LOCK-COMPARISON 진단). 이 변경은 xerial보다 나은 결과를 낼 수 있는 유일한 후보이므로 shared 시나리오 수치를 반드시 보고한다.

### D. 소규모 hot path 정리 (v2 D 유지)

`recoverTransactionRestart()`에 monitor 보유 경로용 fast path를 추가한다(`pendingTransactionRestart == null`이면 즉시 반환). v1 P3의 setlk 감지 static화도 여기서 처리한다.

### 보류

- v2 C(restore guard 재설계): 보류. C′-2 이후 프로파일에서 `checkBackupAccess`가 다시 상위에 나타날 때만 재검토한다.
- ReentrantLock 전환과 락 범위 확장: 이번 범위가 아니다.

## 3. 측정 환경 규칙

- **최종 합격 판정은 GitHub Actions Linux x86_64**(`vt-lock-comparison.yml`, `vt-benchmark.yml`)로만 한다. OrbStack arm64 VM 결과는 방향 확인용이다.
- 로컬 결합 후보는 순서 편향을 피하기 위해 반복마다 **모든 후보의 순서를 회전**한다. §A처럼 특정 쌍이 항상 같은 순서로 실행되면 안 된다.
- v1 §4의 합격 기준을 그대로 적용한다.

## 4. 중단 규칙

- A′에서 가설이 기각되더라도 B, D는 계속한다.
- 멈추고 보고하는 경우:
  - C′-1에서 lock-order 역전이나 lock-safety 차등이 해소되지 않는 경우
  - 회귀 금지 계약(v1 §3) 위반이 불가피한 경우
  - 기존 테스트가 설계 수준에서 실패하는 경우

## 5. 기대치 (macOS `single`, 추정)

| 단계 | CPU ns/op |
|---|---:|
| fork | ≈1,800 |
| + B(P0) | ≈1,180 |
| + C′-1 (inflation 제거) | ≈700–800 |
| + C′-2, D | ≈xerial(680) 이하 |

## 6. 산출물

- `PERF-PARITY-RESULT.md`에 §B, §A′, §C′를 추가한다. 기존 절은 수정하지 않는다.
- 커밋: B(P0-1, P0-2, P0-3), C′-1, C′-2, D를 각각 분리한다.
- C′-1의 lock 획득 순서 표와 C′-2의 변환 목록을 결과 문서에 싣는다.
- `VIRTUAL-THREADS.md`에 동작 변화를 요약한다.
