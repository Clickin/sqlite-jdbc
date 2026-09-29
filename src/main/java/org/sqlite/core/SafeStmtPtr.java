package org.sqlite.core;

import java.sql.SQLException;

/**
 * A class for safely wrapping calls to a native pointer to a statement, ensuring no other thread
 * has access to the pointer while it is run
 */
public class SafeStmtPtr {
    // store a reference to the DB, to lock it before any safe function is called. This avoids
    // deadlocking by locking the DB. All calls with the raw pointer are synchronized with the DB
    // anyways, so making a separate lock would be pointless
    private final DB db;
    private final long ptr;

    private volatile boolean closed = false;
    // to return on subsequent calls to close() after this ptr has been closed
    private int closedRC;
    // to throw on subsequent calls to close after this ptr has been closed, if the close function
    // threw an exception
    private SQLException closeException;

    /**
     * Construct a new Safe Pointer Wrapper to ensure a pointer is properly handled
     *
     * @param db the database that made this pointer. Always locked before any safe run function is
     *     executed to avoid deadlocks
     * @param ptr the raw pointer
     */
    public SafeStmtPtr(DB db, long ptr) {
        this.db = db;
        this.ptr = ptr;
    }

    /**
     * Check whether this pointer has been closed
     *
     * @return whether this pointer has been closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Close this pointer
     *
     * @return the return code of the close callback function
     * @throws SQLException if the close callback throws an SQLException, or the pointer is locked
     *     elsewhere
     */
    public int close() throws SQLException {
        synchronized (db) {
            db.checkBackupAccess();
            return internalClose();
        }
    }

    private int internalClose() throws SQLException {
        try {
            // if this is already closed, return or throw the previous result
            if (closed) {
                if (closeException != null) throw closeException;
                return closedRC;
            }
            closedRC = db.finalize(this, ptr);
            return closedRC;
        } catch (SQLException ex) {
            this.closeException = ex;
            throw ex;
        } finally {
            this.closed = true;
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    public <E extends Throwable> int safeRunInt(SafePtrIntFunction<E> run) throws SQLException, E {
        synchronized (db) {
            this.ensureOpen();
            return run.run(db, ptr);
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    public <E extends Throwable> long safeRunLong(SafePtrLongFunction<E> run)
            throws SQLException, E {
        synchronized (db) {
            this.ensureOpen();
            return run.run(db, ptr);
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    public <E extends Throwable> double safeRunDouble(SafePtrDoubleFunction<E> run)
            throws SQLException, E {
        synchronized (db) {
            this.ensureOpen();
            return run.run(db, ptr);
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return code of the function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    public <T, E extends Throwable> T safeRun(SafePtrFunction<T, E> run) throws SQLException, E {
        synchronized (db) {
            this.ensureOpen();
            return run.run(db, ptr);
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @throws SQLException if the pointer is utilized elsewhere
     */
    public <E extends Throwable> void safeRunConsume(SafePtrConsumer<E> run)
            throws SQLException, E {
        synchronized (db) {
            this.ensureOpen();
            run.run(db, ptr);
        }
    }

    /**
     * Returns the open pointer for a caller that already holds the monitor of {@code owner}, with
     * the same checks as the safeRun methods. Entering the monitor again would only add a recursive
     * lock-stack entry; deep recursion overflows the JVM lock stack and inflates the monitor.
     *
     * @return the pointer, or 0 when {@code owner} is not this pointer's DB; the caller must then
     *     use a safeRun method
     */
    long pointerForMonitorOwner(DB owner) throws SQLException {
        if (owner != db) {
            return 0;
        }
        this.ensureOpen();
        return ptr;
    }

    private void ensureOpen() throws SQLException {
        db.checkBackupAccess();
        if (this.closed) {
            throw new SQLException("stmt pointer is closed");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SafeStmtPtr that = (SafeStmtPtr) o;
        return ptr == that.ptr;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(ptr);
    }

    @FunctionalInterface
    public interface SafePtrIntFunction<E extends Throwable> {
        int run(DB db, long ptr) throws E;
    }

    @FunctionalInterface
    public interface SafePtrLongFunction<E extends Throwable> {
        long run(DB db, long ptr) throws E;
    }

    @FunctionalInterface
    public interface SafePtrDoubleFunction<E extends Throwable> {
        double run(DB db, long ptr) throws E;
    }

    @FunctionalInterface
    public interface SafePtrFunction<T, E extends Throwable> {
        T run(DB db, long ptr) throws E;
    }

    @FunctionalInterface
    public interface SafePtrConsumer<E extends Throwable> {
        void run(DB db, long ptr) throws E;
    }
}
