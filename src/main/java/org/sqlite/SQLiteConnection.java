package org.sqlite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.core.ControlStatement;
import org.sqlite.core.CoreDatabaseMetaData;
import org.sqlite.core.DB;
import org.sqlite.core.NativeDB;
import org.sqlite.jdbc4.JDBC4DatabaseMetaData;

/** */
public abstract class SQLiteConnection implements Connection {
    private static final String RESOURCE_NAME_PREFIX = ":resource:";
    private final DB db;
    private CoreDatabaseMetaData meta = null;
    private final SQLiteConnectionConfig connectionConfig;

    private TransactionMode currentTransactionMode;
    private boolean firstStatementExecuted = false;

    /**
     * Connection constructor for reusing an existing DB handle
     *
     * @param db
     */
    public SQLiteConnection(DB db) {
        this.db = db;
        connectionConfig = db.getConfig().newConnectionConfig();
    }

    /**
     * Constructor to create a connection to a database at the given location.
     *
     * @param url The location of the database.
     * @param fileName The database.
     * @throws SQLException
     */
    public SQLiteConnection(String url, String fileName) throws SQLException {
        this(url, fileName, new Properties());
    }

    /**
     * Constructor to create a pre-configured connection to a database at the given location.
     *
     * @param url The location of the database file.
     * @param fileName The database.
     * @param prop The configurations to apply.
     * @throws SQLException
     */
    public SQLiteConnection(String url, String fileName, Properties prop) throws SQLException {
        DB newDB = null;
        try {
            this.db = newDB = open(url, fileName, prop);
            SQLiteConfig config = this.db.getConfig();
            this.connectionConfig = this.db.getConfig().newConnectionConfig();
            config.apply(this);
            this.currentTransactionMode = this.getDatabase().getConfig().getTransactionMode();
            // connection starts in "clean" state (even though some PRAGMA statements were executed)
            this.firstStatementExecuted = false;
        } catch (Throwable t) {
            try {
                if (newDB != null) {
                    newDB.close();
                }
            } catch (Exception e) {
                t.addSuppressed(e);
            }
            throw t;
        }
    }

    public TransactionMode getCurrentTransactionMode() {
        return this.currentTransactionMode;
    }

    public void setCurrentTransactionMode(final TransactionMode currentTransactionMode) {
        this.currentTransactionMode = currentTransactionMode;
    }

    public void setFirstStatementExecuted(final boolean firstStatementExecuted) {
        this.firstStatementExecuted = firstStatementExecuted;
    }

    public boolean isFirstStatementExecuted() {
        return firstStatementExecuted;
    }

    public SQLiteConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    public CoreDatabaseMetaData getSQLiteDatabaseMetaData() throws SQLException {
        checkOpen();

        if (meta == null) {
            meta = new JDBC4DatabaseMetaData(this);
        }

        return meta;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return (DatabaseMetaData) getSQLiteDatabaseMetaData();
    }

    public String getUrl() {
        return db.getUrl();
    }

    public void setSchema(String schema) throws SQLException {
        // TODO
    }

    public String getSchema() throws SQLException {
        // TODO
        return null;
    }

    public void abort(Executor executor) throws SQLException {
        // TODO
    }

    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        // TODO
    }

    public int getNetworkTimeout() throws SQLException {
        // TODO
        return 0;
    }

    /**
     * Checks whether the type, concurrency, and holdability settings for a {@link ResultSet} are
     * supported by the SQLite interface. Supported settings are:
     *
     * <ul>
     *   <li>type: {@link ResultSet#TYPE_FORWARD_ONLY}
     *   <li>concurrency: {@link ResultSet#CONCUR_READ_ONLY})
     *   <li>holdability: {@link ResultSet#CLOSE_CURSORS_AT_COMMIT}
     * </ul>
     *
     * @param rst the type setting.
     * @param rsc the concurrency setting.
     * @param rsh the holdability setting.
     * @throws SQLException
     */
    protected void checkCursor(int rst, int rsc, int rsh) throws SQLException {
        if (rst != ResultSet.TYPE_FORWARD_ONLY)
            throw new SQLException("SQLite only supports TYPE_FORWARD_ONLY cursors");
        if (rsc != ResultSet.CONCUR_READ_ONLY)
            throw new SQLException("SQLite only supports CONCUR_READ_ONLY cursors");
        if (rsh != ResultSet.CLOSE_CURSORS_AT_COMMIT)
            throw new SQLException("SQLite only supports closing cursors at commit");
    }

    /**
     * Sets the mode that will be used to start transactions on this connection.
     *
     * @param mode One of {@link SQLiteConfig.TransactionMode}
     * @see <a
     *     href="https://www.sqlite.org/lang_transaction.html">https://www.sqlite.org/lang_transaction.html</a>
     */
    protected void setTransactionMode(SQLiteConfig.TransactionMode mode) {
        connectionConfig.setTransactionMode(mode);
    }

    /** @see java.sql.Connection#getTransactionIsolation() */
    @Override
    public int getTransactionIsolation() {
        return connectionConfig.getTransactionIsolation();
    }

    /** @see java.sql.Connection#setTransactionIsolation(int) */
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();

        switch (level) {
            case java.sql.Connection.TRANSACTION_READ_COMMITTED:
            case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
                // Fall-through: Spec allows upgrading isolation to a higher level
            case java.sql.Connection.TRANSACTION_SERIALIZABLE:
                getDatabase().exec("PRAGMA read_uncommitted = false;", getAutoCommit());
                break;
            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
                getDatabase().exec("PRAGMA read_uncommitted = true;", getAutoCommit());
                break;
            default:
                throw new SQLException(
                        "Unsupported transaction isolation level: "
                                + level
                                + ". "
                                + "Must be one of TRANSACTION_READ_UNCOMMITTED, TRANSACTION_READ_COMMITTED, "
                                + "TRANSACTION_REPEATABLE_READ, or TRANSACTION_SERIALIZABLE in java.sql.Connection");
        }
        connectionConfig.setTransactionIsolation(level);
    }

    /**
     * Opens a connection to the database using an SQLite library. * @throws SQLException
     *
     * @see <a
     *     href="https://www.sqlite.org/c3ref/c_open_autoproxy.html">https://www.sqlite.org/c3ref/c_open_autoproxy.html</a>
     */
    private static DB open(String url, String origFileName, Properties props) throws SQLException {
        // Create a copy of the given properties
        Properties newProps = new Properties();
        newProps.putAll(props);

        // Extract pragma as properties
        String fileName = extractPragmasFromFilename(url, origFileName, newProps);
        SQLiteConfig config = new SQLiteConfig(newProps);

        // check the path to the file exists
        if (!fileName.isEmpty()
                && !":memory:".equals(fileName)
                && !fileName.startsWith("file:")
                && !fileName.contains("mode=memory")) {
            if (fileName.startsWith(RESOURCE_NAME_PREFIX)) {
                String resourceName = fileName.substring(RESOURCE_NAME_PREFIX.length());

                // search the class path
                ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
                URL resourceAddr = contextCL.getResource(resourceName);
                if (resourceAddr == null) {
                    try {
                        resourceAddr = new URL(resourceName);
                    } catch (MalformedURLException e) {
                        throw new SQLException(
                                String.format("resource %s not found: %s", resourceName, e));
                    }
                }

                try {
                    fileName = extractResource(resourceAddr).getAbsolutePath();
                } catch (IOException e) {
                    throw new SQLException(String.format("failed to load %s: %s", resourceName, e));
                }
            } else {
                fileName = new File(fileName).getAbsolutePath();
            }
        }

        // load the native DB
        DB db = null;
        try {
            NativeDB.load();
            db = new NativeDB(url, fileName, config);
        } catch (Exception e) {
            SQLException err = new SQLException("Error opening connection");
            err.initCause(e);
            throw err;
        }
        db.open(fileName, config.getOpenModeFlags());
        return db;
    }

    /**
     * Returns a file name from the given resource address.
     *
     * @param resourceAddr The resource address.
     * @return The extracted file name.
     * @throws IOException
     */
    private static File extractResource(URL resourceAddr) throws IOException {
        if (resourceAddr.getProtocol().equals("file")) {
            try {
                return new File(resourceAddr.toURI());
            } catch (URISyntaxException e) {
                throw new IOException(e.getMessage());
            }
        }

        String tempFolder = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        String dbFileName = String.format("sqlite-jdbc-tmp-%s.db", UUID.randomUUID());
        File dbFile = new File(tempFolder, dbFileName);

        if (dbFile.exists()) {
            long resourceLastModified = resourceAddr.openConnection().getLastModified();
            long tmpFileLastModified = dbFile.lastModified();
            if (resourceLastModified < tmpFileLastModified) {
                return dbFile;
            } else {
                // remove the old DB file
                boolean deletionSucceeded = dbFile.delete();
                if (!deletionSucceeded) {
                    throw new IOException(
                            "failed to remove existing DB file: " + dbFile.getAbsolutePath());
                }
            }

            //            String md5sum1 = SQLiteJDBCLoader.md5sum(resourceAddr.openStream());
            //            String md5sum2 = SQLiteJDBCLoader.md5sum(new FileInputStream(dbFile));
            //
            //            if (md5sum1.equals(md5sum2))
            //                return dbFile; // no need to extract the DB file
            //            else
            //            {
            //            }
        }

        URLConnection conn = resourceAddr.openConnection();
        // Disable caches to avoid keeping unnecessary file references after the single-use copy
        conn.setUseCaches(false);
        try (InputStream reader = conn.getInputStream();
                OutputStream writer = new FileOutputStream(dbFile)) {
            // Replace this code with buffer copy so we don't rely on java.nio package for Android
            // compatibility
            // Files.copy(reader, dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, bytesRead);
            }
            return dbFile;
        }
    }

    public DB getDatabase() {
        return db;
    }

    /** @see java.sql.Connection#getAutoCommit() */
    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();

        return connectionConfig.isAutoCommit();
    }

    /** @see java.sql.Connection#setAutoCommit(boolean) */
    @Override
    public void setAutoCommit(boolean ac) throws SQLException {
        synchronized (db) {
            checkOpen();
            if (connectionConfig.isAutoCommit() == ac) return;

            // The JDBC-side setting follows a successful control statement: a busy-failed
            // BEGIN must not leave the connection advertising an open transaction, and a
            // failed COMMIT must not silently turn auto-commit on (plan 10.2).
            if (ac) {
                if (this.isTransactionRestartPending()) {
                    // The native transaction already ended before its follow-up BEGIN failed.
                    this.setTransactionRestartPending(false);
                } else {
                    db.execControl(ControlStatement.COMMIT, ac, false);
                }
                connectionConfig.setAutoCommit(true);
                this.firstStatementExecuted = false;
                this.currentTransactionMode = null;
                // COMMIT is complete. A failure in this compatibility probe must not imply that
                // COMMIT failed or leave JDBC auto-commit disabled.
                db.ensureAutoCommit(true);
            } else {
                db.execControl(beginStatement(), ac, true);
                this.setTransactionRestartPending(false);
                connectionConfig.setAutoCommit(false);
                this.currentTransactionMode = this.getConnectionConfig().getTransactionMode();
                this.firstStatementExecuted = false;
            }
        }
    }

    /** The driver-generated BEGIN matching the configured transaction mode. */
    protected ControlStatement beginStatement() {
        switch (this.getConnectionConfig().getTransactionMode()) {
            case IMMEDIATE:
                return ControlStatement.BEGIN_IMMEDIATE;
            case EXCLUSIVE:
                return ControlStatement.BEGIN_EXCLUSIVE;
            default:
                return ControlStatement.BEGIN_DEFERRED;
        }
    }

    /**
     * True when a driver-generated BEGIN after a successful COMMIT/ROLLBACK failed: the JDBC
     * connection is autoCommit=false but the native connection has no transaction. User SQL then
     * needs an explicit restart before it can run, otherwise each statement would silently
     * auto-commit (plan 10.3).
     */
    private ControlStatement pendingTransactionRestart;

    /** Whether the next user SQL needs the driver-generated BEGIN restarted first. */
    protected boolean isTransactionRestartPending() {
        return pendingTransactionRestart != null;
    }

    protected void setTransactionRestartPending(boolean pending) {
        pendingTransactionRestart = pending ? beginStatement() : null;
    }

    protected void setTransactionRestartPending(ControlStatement begin) {
        pendingTransactionRestart = begin;
    }

    /**
     * Restarts the driver-generated BEGIN after a failed one. Single statement, own wait budget;
     * never replays the preceding COMMIT or ROLLBACK.
     */
    protected void restartTransaction() throws SQLException {
        ControlStatement begin =
                pendingTransactionRestart == null ? beginStatement() : pendingTransactionRestart;
        try {
            db.execControl(begin, getAutoCommit(), false);
            setTransactionRestartPending(false);
            this.firstStatementExecuted = false;
            this.setCurrentTransactionMode(transactionMode(begin));
        } catch (SQLException restartFailure) {
            setTransactionRestartPending(begin);
            throw restartFailure;
        }
    }

    private TransactionMode transactionMode(ControlStatement begin) {
        switch (begin) {
            case BEGIN_IMMEDIATE:
                return TransactionMode.IMMEDIATE;
            case BEGIN_EXCLUSIVE:
                return TransactionMode.EXCLUSIVE;
            default:
                return TransactionMode.DEFERRED;
        }
    }

    /**
     * Recovers a pending transaction restart before another statement runs, or throws a clear error
     * so user SQL cannot silently auto-commit (plan 10.3).
     */
    protected void recoverTransactionRestart() throws SQLException {
        synchronized (db) {
            if (this.isTransactionRestartPending()) {
                try {
                    restartTransaction();
                } catch (SQLException restartFailure) {
                    throw new SQLException(
                            "transaction restart pending: the automatic BEGIN after the last"
                                    + " commit/rollback failed; call rollback() or setAutoCommit() to"
                                    + " recover",
                            restartFailure.getSQLState(),
                            restartFailure.getErrorCode(),
                            restartFailure);
                }
            }
        }
    }

    /**
     * @return The busy timeout value for the connection.
     * @see <a
     *     href="https://www.sqlite.org/c3ref/busy_timeout.html">https://www.sqlite.org/c3ref/busy_timeout.html</a>
     */
    public int getBusyTimeout() {
        return db.getConfig().getBusyTimeout();
    }

    /**
     * Sets the timeout value for the connection. A timeout value less than or equal to zero turns
     * off all busy handlers.
     *
     * @see <a
     *     href="https://www.sqlite.org/c3ref/busy_timeout.html">https://www.sqlite.org/c3ref/busy_timeout.html</a>
     * @param timeoutMillis The timeout value in milliseconds.
     * @throws SQLException
     */
    public void setBusyTimeout(int timeoutMillis) throws SQLException {
        db.getConfig().setBusyTimeout(timeoutMillis);
        db.busy_timeout(timeoutMillis);
    }

    public void setLimit(SQLiteLimits limit, int value) throws SQLException {
        // Calling sqlite3_limit with a negative number is a no-op:
        // https://www.sqlite.org/c3ref/limit.html
        if (value >= 0) {
            db.limit(limit.getId(), value);
        }
    }

    public void getLimit(SQLiteLimits limit) throws SQLException {
        db.limit(limit.getId(), -1);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return db.isClosed();
    }

    /** @see java.sql.Connection#close() */
    @Override
    public void close() throws SQLException {
        if (isClosed()) return;
        if (meta != null) meta.close();

        db.close();
    }

    /**
     * Whether an SQLite library interface to the database has been established.
     *
     * @throws SQLException
     */
    protected void checkOpen() throws SQLException {
        if (isClosed()) throw new SQLException("database connection closed");
    }

    /**
     * @return Compile-time library version numbers.
     * @throws SQLException
     * @see <a
     *     href="https://www.sqlite.org/c3ref/c_source_id.html">https://www.sqlite.org/c3ref/c_source_id.html</a>
     */
    public String libversion() throws SQLException {
        checkOpen();

        return db.libversion();
    }

    /** @see java.sql.Connection#commit() */
    @Override
    public void commit() throws SQLException {
        synchronized (db) {
            checkOpen();
            if (connectionConfig.isAutoCommit())
                throw new SQLException("database in auto-commit mode");
            if (this.isTransactionRestartPending()) {
                // Nothing native to commit: the previous transaction was already committed. A fresh
                // BEGIN completes recovery; do not issue a second BEGIN or replay COMMIT.
                this.restartTransaction();
                return;
            }
            try {
                db.execControl(ControlStatement.COMMIT, getAutoCommit(), true);
            } catch (SQLException commitFailure) {
                // The transaction is still active; a later commit() may retry the COMMIT.
                throw commitFailure;
            }
            ControlStatement nextBegin = beginStatement();
            try {
                db.execControl(nextBegin, getAutoCommit(), true);
                this.setTransactionRestartPending(false);
            } catch (SQLException beginFailure) {
                // The data is committed; do not replay the COMMIT. Mark the missing BEGIN so user
                // SQL cannot silently auto-commit (plan 10.3).
                this.setTransactionRestartPending(nextBegin);
                throw beginFailure;
            }
            this.firstStatementExecuted = false;
            this.setCurrentTransactionMode(transactionMode(nextBegin));
        }
    }

    /** @see java.sql.Connection#rollback() */
    @Override
    public void rollback() throws SQLException {
        synchronized (db) {
            checkOpen();
            if (connectionConfig.isAutoCommit())
                throw new SQLException("database in auto-commit mode");
            if (this.isTransactionRestartPending()) {
                // There is no native transaction to roll back; restart the BEGIN that originally
                // failed, without replaying ROLLBACK.
                this.restartTransaction();
                return;
            }
            db.exec("rollback;", getAutoCommit());
            ControlStatement nextBegin = beginStatement();
            try {
                db.execControl(nextBegin, getAutoCommit(), true);
                this.setTransactionRestartPending(false);
            } catch (SQLException beginFailure) {
                this.setTransactionRestartPending(nextBegin);
                throw beginFailure;
            }
            this.firstStatementExecuted = false;
            this.setCurrentTransactionMode(transactionMode(nextBegin));
        }
    }

    /**
     * Add a listener for DB update events, see https://www.sqlite.org/c3ref/update_hook.html
     *
     * @param listener The listener to receive update events
     */
    public void addUpdateListener(SQLiteUpdateListener listener) {
        db.addUpdateListener(listener);
    }

    /**
     * Remove a listener registered for DB update events.
     *
     * @param listener The listener to no longer receive update events
     */
    public void removeUpdateListener(SQLiteUpdateListener listener) {
        db.removeUpdateListener(listener);
    }

    /**
     * Add a listener for DB commit/rollback events, see
     * https://www.sqlite.org/c3ref/commit_hook.html
     *
     * @param listener The listener to receive commit events
     */
    public void addCommitListener(SQLiteCommitListener listener) {
        db.addCommitListener(listener);
    }

    /**
     * Remove a listener registered for DB commit/rollback events.
     *
     * @param listener The listener to no longer receive commit/rollback events.
     */
    public void removeCommitListener(SQLiteCommitListener listener) {
        db.removeCommitListener(listener);
    }

    /**
     * Extracts PRAGMA values from the filename and sets them into the Properties object which will
     * be used to build the SQLConfig. The sanitized filename is returned.
     *
     * @param filename
     * @param prop
     * @return a PRAGMA-sanitized filename
     * @throws SQLException
     */
    protected static String extractPragmasFromFilename(String url, String filename, Properties prop)
            throws SQLException {
        int parameterDelimiter = filename.indexOf('?');
        if (parameterDelimiter == -1) {
            // nothing to extract
            return filename;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(filename.substring(0, parameterDelimiter));

        int nonPragmaCount = 0;
        String[] parameters = filename.substring(parameterDelimiter + 1).split("&");
        for (int i = 0; i < parameters.length; i++) {
            // process parameters in reverse-order, last specified pragma value wins
            String parameter = parameters[parameters.length - 1 - i].trim();

            if (parameter.isEmpty()) {
                // duplicated &&& sequence, drop
                continue;
            }

            String[] kvp = parameter.split("=");
            String key = kvp[0].trim().toLowerCase();
            if (SQLiteConfig.pragmaSet.contains(key)) {
                if (kvp.length == 1) {
                    throw new SQLException(
                            String.format(
                                    "Please specify a value for PRAGMA %s in URL %s", key, url));
                }
                String value = kvp[1].trim();
                if (!value.isEmpty()) {
                    if (prop.containsKey(key)) {
                        //
                        // IGNORE
                        //
                        // this allows DriverManager.getConnection(String, Properties)
                        // to override URL parameters programmatically.
                        //
                        // It also ignores duplicate pragma keys in the URL. The reversed
                        // processing order ensures the last-supplied pragma value is used.
                    } else {
                        prop.setProperty(key, value);
                    }
                }
            } else {
                // not a Pragma, retain as part of filename
                sb.append(nonPragmaCount == 0 ? '?' : '&');
                sb.append(parameter);
                nonPragmaCount++;
            }
        }

        final String newFilename = sb.toString();
        return newFilename;
    }

    protected String transactionPrefix() {
        return this.connectionConfig.transactionPrefix();
    }

    /**
     * Returns a byte array representing the schema content. This method is intended for in-memory
     * schemas. Serialized databases are limited to 2gb.
     *
     * @param schema The schema to serialize
     * @return A byte[] holding the database content
     */
    public byte[] serialize(String schema) throws SQLException {
        return db.serialize(schema);
    }

    /**
     * Deserialize the schema using the given byte array. This method is intended for in-memory
     * database. The call will replace the content of an existing schema. To make sure there is an
     * existing schema, first execute ATTACH ':memory:' AS schema_name
     *
     * @param schema The schema to serialize
     * @param buff The buffer to deserialize
     */
    public void deserialize(String schema, byte[] buff) throws SQLException {
        db.deserialize(schema, buff);
    }
}
