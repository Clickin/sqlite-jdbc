# Busy-policy verification

P01–P12 results are from `mvn -q test -Dtest=BusyPolicyTest` on the local macOS ARM64/JDK 25 build. The tests hold a real file-backed writer lock and observe the *effective* `PRAGMA busy_timeout`; Java-side configuration fields alone are not treated as proof.

## Policy decision

The Java wait path is limited to `NativeDB` connections with no driver-registered `BusyHandler`, a readable positive native timeout, and a linked engine whose `PRAGMA compile_options` does not contain `ENABLE_SETLK_TIMEOUT`.

- Each control statement reads the live native timeout. There is no cached timeout readback, so SQL `PRAGMA busy_timeout=N` and temporary query-timeout changes are visible.
- Each native attempt reads the timeout again, installs a stack-local callback that only records invocation and returns immediately, steps once, then restores the default timeout handler before returning to Java.
- The callback is installed only for a positive timeout. Zero or unreadable timeout uses one legacy native step; this avoids replacing an unknown handler when `PRAGMA busy_timeout` reports zero.
- A driver BusyHandler or enabled/unknown setlk capability takes the legacy path without modifying policy. When a BusyHandler is replaced by SQL `PRAGMA busy_timeout`, the Java-side handler reference remains stale; that causes a conservative legacy fallback, not restoration of the removed handler.
- Native compile-option probing and per-attempt timeout readback failures are fail-closed: no Java retry. The standard local engine reported no `ENABLE_SETLK_TIMEOUT` option.
- If the calling thread is already inside a driver-managed SQLite-to-Java callback, no-wait eligibility is rejected before changing the native handler. This covers user functions (including aggregate/window), collations, driver busy/progress handlers, and update/commit listeners; V06 tests a UDF reentering a second locked connection.
- `PRAGMA busy_timeout` can change policy during `prepareStatement`, before `execute()`. The batch path also changes it while preparing a batch entry, then reports `query returns results`; the next policy read sees the changed value.

Guarantee covers the bundled engine and driver-managed callback entry points. External native code can install an untracked handler or invoke Java without these wrappers; SQLite exposes no public handler getter, so those configurations are unverified and may wait beneath an untracked native callback.

## Experiments

| ID | Observed result |
|---|---|
| P01 | Default timeout: contended driver-generated BEGIN entered Java wait, returned BUSY at its budget, preserved auto-commit=true and timeout; after releasing the writer, BEGIN succeeded. |
| P02 | `setBusyTimeout(10)` then `(45)`: the effective native timeout was 45 before and after the contended BEGIN; the control path used Java wait. |
| P03 | SQL `PRAGMA busy_timeout=35` overrode the Java config value 3000. Contended BEGIN used Java wait, then ordinary INSERT still observed BUSY; native readback remained 35. |
| P04 | Registered Java BusyHandler received callback index 0 on the calling thread; no Java wait was recorded. |
| P05 | SQL `PRAGMA busy_timeout=35` replaced a registered handler: its callback was not revived or called; the effective timeout remained 35 and the control statement used the legacy path. |
| P06 | Clearing a custom handler left the effective timeout disabled (0); a contended BEGIN made no Java sleep. |
| P07 | Timeouts 0 and -1 both retained SQLite's effective disabled value (0), returned BUSY without Java sleep, and left auto-commit unchanged. |
| P08 | A statement with query timeout 1 temporarily used the native query timeout for a blocked INSERT; after BUSY, both the native readback and connection setting returned to 35. |
| P09 | Preparing `PRAGMA busy_timeout=41` changed the effective value before execution. `Statement.execute` and `DB._exec` changed it to 52 and 63. A batch entry changed it to 74 during preparation, then failed with `BatchUpdateException: query returns results`. |
| P10 | Starting from timeout 0, SQL changed it to 55; the next contended BEGIN used Java wait and left the live native value at 55, proving no earlier timeout snapshot was reused. |
| P11 | A timeout change requested from another thread during Java wait took effect after the in-progress operation released the DB monitor; the final native value was 300 and no older value overwrote it. |
| P12 | A custom-handler fallback invoked the same callback for both control BEGIN and later ordinary INSERT, with no Java waits. |

The retry schedule matches SQLite 3.53.4 `sqliteDefaultBusyCallback` (`target/sqlite-amalgamation-3530400/sqlite3.c:189120-189149`): `{1, 2, 5, 10, 15, 20, 25, 25, 25, 50, 50, 100}` ms, then 100 ms, limited by SQLite's cumulative requested-sleep budget. `System.nanoTime()` adds a separate wall-time ceiling for attempt/JNI overhead; scheduler oversleep can therefore shorten the retries slightly versus native behavior. Each JNI attempt restores the handler before Java sleep.
