# Virtual threads: Java-side waits for backup/restore

This fork moves the busy-wait loop of `backup`/`restore` out of JNI and into
Java so that a waiting virtual thread no longer pins its carrier on JDK 24+
(JEP 491). The SQLite engine and the JDBC call model are unchanged: SQL always
runs on the caller's thread, and no driver-internal executor is introduced.

## What changed

`NativeDB.c` previously ran the whole copy loop inside one JNI call
(`copyLoop`), sleeping with `sqlite3_sleep()` while holding the DB monitor.
Now the native side exposes four session boundaries and Java drives the loop:

| Native method | Responsibility |
|---|---|
| `backupInit(dbNameUtf8, otherFileUtf8, openFlags, sessionDbIsBackupSource)` | Opens the other file, creates `sqlite3_backup`; returns `{resultCode, sessionPointer}` |
| `backupStep(sessionPointer, pagesPerStep)` | One `sqlite3_backup_step()`; returns its result code |
| `backupProgress(sessionPointer)` | `{pagesRemaining, pageCount}` after a successful step |
| `backupFinish(sessionPointer)` | `sqlite3_backup_finish()` (rolls back incomplete copies) and closes the temporary file connection |

`NativeDB.driveBackupCopy(...)` runs the loop. On `SQLITE_BUSY`/`SQLITE_LOCKED`
it waits with `Thread.sleep(sleepTimeMillis)` **on the Java side**, still inside
the `synchronized` DB method, so the previous exclusion guarantees are kept:

- No other thread can use or `close()` the connection while a backup/restore
  session is open. The DB monitor is held for the entire loop, exactly as the
  former `synchronized native` call did.
- `restore` keeps its destination protection: the destination is `this` DB, and
  the monitor excludes other users until the copy finishes or aborts.

Behavior on JDKs:

| Runtime | Waiting backup/restore virtual thread |
|---|---|
| JDK 24+ (JEP 491) | releases its carrier while sleeping in the Java wait; verified by test |
| JDK 8–23 | blocks its carrier while sleeping, same as the former native `sqlite3_sleep` wait |

Java 8 source compatibility is preserved (`maven.compiler.release` stays 8; the
new code uses only `Thread.sleep` and existing APIs).

## Result-code semantics

For a completed copy the driver returns `SQLITE_OK` as before. One deliberate
alignment with the documented native behavior: the old C loop reported
`sqlite3_errcode(pFile)` of the *temporary* file connection, which could
mask the actual failure of a **restore** that exhausted its busy retries or was
aborted while the source stayed locked (potentially returning a spurious
`SQLITE_OK` for a silently incomplete restore). The Java-driven loop returns
the last observed result code instead, so a busy-exhausted restore now
surfaces as `SQLITE_BUSY`/`SQLITE_LOCKED`. Backup-direction behavior is
unchanged.

Additionally, a `Thread.interrupt()` delivered while the copy waits in Java now
aborts the operation with `SQLITE_INTERRUPT` (interrupt flag restored) and
leaves the connection usable. `Statement.cancel()` semantics are unchanged
(it does not interrupt threads).

Progress observer callbacks fire after each successful step, on the caller's
thread, exactly as before.

## Verified

Local macOS ARM64, SQLite 3.53.4 amalgamation:

- Upstream suite on JDK 25.0.2: **472 tests, 0 failures, 0 errors, 8 skipped**
  (the 8 skips are pre-existing).
- `BackupTest` (4) and `BackupBusyWaitTest` (4) on a real **JDK 8.0.472**
  runtime: 8/8 pass.
- `BackupBusyWaitTest` on JDK 25 verifies, under a `BEGIN EXCLUSIVE` locked
  destination:
  - backup exhausts its busy budget with real elapsed Java-side waits and
    reports `SQLITE_BUSY`;
  - a busy **restore** throws `SQLITE_BUSY` instead of silently "succeeding";
  - `Thread.interrupt()` during the wait aborts the copy and leaves the
    connection usable for a fresh backup;
  - a virtual thread sleeping in the wait records **zero**
    `jdk.VirtualThreadPinned` JFR events while holding the DB monitor, and a
    second virtual thread still progresses.

## Not covered (future work)

- Generic `SQLITE_BUSY` retry for user SQL statements. Re-stepping a prepared
  statement after `SQLITE_BUSY` can re-run observable side effects (e.g. user
  defined functions execute twice when SQLite rolls back the statement), so a
  general retry loop is not safe without per-operation semantics. First
  verified candidates are the driver-generated `BEGIN`/`COMMIT` statements
  executed by `SQLiteConnection.setAutoCommit`/`commit()`; note that
  `Connection.commit()` performs `COMMIT` followed by the next `BEGIN`, so any
  retry must target the individual `DB.exec` operation, not the JDBC method.
- The in-loop waits still hold the DB monitor, so other threads of the same
  connection remain blocked while waiting (same as before this change; only
  the carrier behavior improved).
- Backup waits do not respond to `Statement.cancel()`.
