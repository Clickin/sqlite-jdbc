// --------------------------------------
// sqlite-jdbc Project
//
// ControlStatement.java
// --------------------------------------
package org.sqlite.core;

/**
 * The closed set of driver-generated transaction control statements that may be retried with
 * Java-side busy waits. User-written SQL never maps to this set: eligibility comes from the
 * caller's operation kind, never from SQL text inspection.
 */
public enum ControlStatement {
    BEGIN_DEFERRED("begin;"),
    BEGIN_IMMEDIATE("begin immediate;"),
    BEGIN_EXCLUSIVE("begin exclusive;"),
    COMMIT("commit;"),
    /**
     * The begin probe of {@code DB#ensureAutoCommit}: step semantics are preserved by the caller.
     */
    AUTOCOMMIT_PROBE_BEGIN("begin;"),
    /** The compatibility commit probe after an auto-commit statement. */
    AUTOCOMMIT_PROBE_COMMIT("commit;");

    private final String sql;

    ControlStatement(String sql) {
        this.sql = sql;
    }

    /** The canonical SQL text of this control statement. */
    public String sql() {
        return sql;
    }
}
