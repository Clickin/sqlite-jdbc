# vt-java-wait BASELINE (Phase 0)

작성일: 2026-09-26, Asia/Seoul. 실제 확인값만 기록한다.

## 환경

- Repo: `Clickin/sqlite-jdbc`, worktree `/Users/senghyunjo/github/sqlite-jdbc-vt`
- Branch: `vt-java-wait`, HEAD `d074eea7237dd6eef4d6b62e45c9e06511de85f0`
- Parent/upstream 기준: `3850773aed93aa657b8025d44be208a98ea761f3` (HEAD 기준 1 commit 전진)
- Dirty: `src/main/resources/org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib` (빌드 산물, 미커밋), plan 문서 untracked
- JDK: sdkman candidates — 8.0.472-amzn, 8.0.492-zulu, 17.0.14-tem, 21.0.6-tem, 25.0.2-tem
- Maven 3.9.16 (기본 JAVA_HOME = JDK 25.0.2-tem, `java.version` 미설정 상태)
- OS/arch: macOS 25.5.0 arm64, Apple clang (make native 사용)
- SQLite engine: amalgamation 3.53.4 (`target/sqlite-amalgamation-3530400/sqlite3.c`)

## Native 빌드/로딩

- `make native` → `$(TARGET)/sqlite-3.53.4-Mac-aarch64/libsqlitejdbc.dylib`, Makefile:149-156 가 `src/main/resources/org/sqlite/native/Mac/aarch64/` 와 `target/classes/...` 로 복사
- SQLite compile flags: Makefile:115-129 (`SQLITE_ENABLE_UPDATE_DELETE_LIMIT`, `SQLITE_MAX_VARIABLE_NUMBER=250000` 등). **`SQLITE_ENABLE_SETLK_TIMEOUT` 없음** — sqlite3_busy_timeout이 VFS setlk timeout을 건드리는 구성 아님 (Makefile 기준, 런타임 `PRAGMA compile_options` 재확인은 Phase 3에서)
- 테스트: `mvn test -Dtest=...` (surefire 3.6.0), compiler release=8 (pom:106, J9+ 경로 release=9 pom:116)

## 호출 경로 지도 (HEAD d074eea 기준)

| 경로 | 위치 | 제어문 | 동기화 |
|---|---|---|---|
| setAutoCommit | `SQLiteConnection.java:347-364` | `commit;` / `transactionPrefix()` via `db.exec(sql, ac)` | `DB.exec` synchronized; **Java 설정을 SQL 성공 전에 변경 (결함 R5)** |
| commit | `SQLiteConnection.java:435-444` | `commit;` + `transactionPrefix()` (다음 BEGIN), 각각 별도 `db.exec` | 同 |
| rollback | `SQLiteConnection.java:446-455` | `rollback;` + `transactionPrefix()` | 同 |
| DB.exec | `DB.java:188-205` | prepare→step→close→`ensureAutoCommit(ac)` | synchronized(DB) |
| ensureAutoCommit | `DB.java:1224-1259` | 캐시된 `begin;`/`commit;` SafeStmtPtr 직접 step (probe) | synchronized(DB) |
| tryEnforceTransactionMode | `JDBC3Connection.java:49-82` | `_exec("PRAGMA query_only = ...")`, `_exec("commit; ...")`, `_exec("BEGIN IMMEDIATE; ...")` | `NativeDB._exec` synchronized (prepare/step/finalize C측) |
| transactionPrefix | `SQLiteConnectionConfig.java:151` | `begin;` / `begin immediate;` / `begin exclusive;` | — |

## busy 정책 변경 경로

- `NativeDB.c:614-624` `busy_timeout(ms)` JNI → `sqlite3_busy_timeout`
- `NativeDB.c:636-667` `busy_handler` JNI → `sqlite3_busy_handler(db, cb, ctx)`, ctx=`malloc` BusyHandlerContext (free_busy_handler로 해제), Java 필드 `busyHandler` long 추적 (`NativeDB.java:126,641`)
- 연결 초기화: `DB.open` → `busy_timeout(config.getBusyTimeout())` (`DB.java:225`)
- `SQLiteConnection.setBusyTimeout` → `db.getConfig().setBusyTimeout` + `db.busy_timeout(ms)`
- statement query timeout: progress handler 기반 (busy_timeout과 무관) — P08은 busy 정책과 독립임을 확인
- SQLite 3.53.4에서 `sqlite3_busy_handler` 호출 시 `db->busyTimeout=0`, `sqlite3_busy_timeout(ms>0)`가 default busy callback 설치 + `db->busyTimeout=ms` → **`PRAGMA busy_timeout` readback(>0)=default timeout handler 설치 여부의 정확한 상태 표시**

## backup session 현재 결함 (Phase 1 대상, NativeDB.c:1486-1710)

1. allocator 혼용: `sqlite3_malloc` (1600) vs `free` (1518)
2. `backup_init` 실패/open 실패 시 `session==NULL` → `freeBackupSession(NULL)`이 즉시 반환 → **임시 연결 `pFile` 누수** (1626 주석과 동작 불일치)
3. `sqlite3_malloc` 실패 시 `backup_finish` 후에도 `pFile` 누수 (session 생성 전이라)
4. `NewLongArray`/`SetLongArrayRegion` 실패 시 session(pFile,pBackup) 누수, 예외 확인 없음
5. restore (`copyFromSessionDb==false`)의 `backup_init` 실패를 `sqlite3_errcode(pFile)`(source)에서 조회 — destination은 `pDb` (1616-1618)
6. `driveBackupCopy` (`NativeDB.java:505-551`): monitor 보유 + Java `Thread.sleep` + finally `backupFinish` — Phase 2/1 테스트 대상

## 기존 테스트 상태

- `BackupBusyWaitTest`: **static initializer에서 프로세스 전역 `jdk.virtualThreadScheduler.maxPoolSize=1` 설정 (계획 6.1 위반)**, VT는 reflection 사용, heartbeat가 대상 join 이후 확인 (계획 6.2 위반) → Phase 2에서 child JVM 방식으로 교체
- `BackupTest`: 정상 경로 위주

## 기준 실행 기록

- 이전 agent의 전체 suite 결과(JDK 25/8)는 실행 기록이며 본 구현 증거 아님. 본 구현 검증은 각 Phase 커밋 시점에 재실행하여 IMPLEMENTATION-REPORT.md에 기록.
