package network.vonix.vonixcore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DatabaseConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Database manager using HikariCP for connection pooling.
 * Supports multiple database backends:
 * - SQLite (default, single-file storage)
 * - MySQL/MariaDB
 * - PostgreSQL
 * - Turso (LibSQL edge database)
 * - Supabase (PostgreSQL-based)
 */
public class Database {

    public enum DatabaseType {
        SQLITE, MYSQL, POSTGRESQL, TURSO, SUPABASE
    }

    private final MinecraftServer server;
    private HikariDataSource dataSource;
    private DatabaseType databaseType = DatabaseType.SQLITE;
    private final ExecutorService executor;

    public Database(MinecraftServer server) {
        this.server = server;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "vonixcore-DB-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Get the executor for async database operations.
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Initialize the database connection pool and create tables.
     */
    public void initialize() throws SQLException {
        String dbType = DatabaseConfig.getInstance().getType().toLowerCase();

        databaseType = switch (dbType) {
            case "mysql" -> DatabaseType.MYSQL;
            case "postgresql", "postgres" -> DatabaseType.POSTGRESQL;
            case "turso", "libsql" -> DatabaseType.TURSO;
            case "supabase" -> DatabaseType.SUPABASE;
            default -> DatabaseType.SQLITE;
        };

        HikariConfig config = new HikariConfig();
        config.setPoolName("VonixCore-DB-Pool");
        config.setMaximumPoolSize(DatabaseConfig.getInstance().getConnectionPoolSize());
        config.setMinimumIdle(2);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(DatabaseConfig.getInstance().getConnectionTimeout());

        switch (databaseType) {
            case MYSQL -> configureMySql(config);
            case POSTGRESQL -> configurePostgreSql(config);
            case TURSO -> configureTurso(config);
            case SUPABASE -> configureSupabase(config);
            default -> configureSqlite(config);
        }

        dataSource = new HikariDataSource(config);

        // Create tables
        createTables();
    }

    /**
     * Configure SQLite connection (single file, all data in one database).
     */
    private void configureSqlite(HikariConfig config) {
        File worldFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        File dataFolder = new File(worldFolder, "vonixcore");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String fileName = DatabaseConfig.getInstance().getSqliteFile();
        File dbFile = new File(dataFolder, fileName);

        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // SQLite-specific optimizations
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "10000");
        config.addDataSourceProperty("temp_store", "MEMORY");

        VonixCore.LOGGER.info("[VonixCore] Using SQLite database: {}", dbFile.getAbsolutePath());
    }

    /**
     * Configure MySQL/MariaDB connection.
     */
    private void configureMySql(HikariConfig config) {
        String host = DatabaseConfig.getInstance().getMysqlHost();
        int port = DatabaseConfig.getInstance().getMysqlPort();
        String database = DatabaseConfig.getInstance().getMysqlDatabase();
        String username = DatabaseConfig.getInstance().getMysqlUsername();
        String password = DatabaseConfig.getInstance().getMysqlPassword();
        boolean ssl = DatabaseConfig.getInstance().getMysqlSsl();

        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                host, port, database, ssl));
        config.setUsername(username);
        config.setPassword(password);

        VonixCore.LOGGER.info("[VonixCore] Using MySQL database at {}:{}/{}", host, port, database);
    }

    /**
     * Configure PostgreSQL connection.
     */
    private void configurePostgreSql(HikariConfig config) {
        String host = DatabaseConfig.getInstance().getPostgresqlHost();
        int port = DatabaseConfig.getInstance().getPostgresqlPort();
        String database = DatabaseConfig.getInstance().getPostgresqlDatabase();
        String username = DatabaseConfig.getInstance().getPostgresqlUsername();
        String password = DatabaseConfig.getInstance().getPostgresqlPassword();
        boolean ssl = DatabaseConfig.getInstance().getPostgresqlSsl();

        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%d/%s?sslmode=%s",
                host, port, database, ssl ? "require" : "disable"));
        config.setUsername(username);
        config.setPassword(password);

        VonixCore.LOGGER.info("[VonixCore] Using PostgreSQL database at {}:{}/{}", host, port, database);
    }

    /**
     * Configure Turso (LibSQL) connection.
     * Note: Turso uses HTTP-based LibSQL protocol over WebSocket.
     * This implementation uses their JDBC-compatible driver.
     */
    private void configureTurso(HikariConfig config) {
        String url = DatabaseConfig.getInstance().getTursoUrl();
        String authToken = DatabaseConfig.getInstance().getTursoAuthToken();

        // Convert libsql:// to https:// for JDBC compatibility
        String jdbcUrl = url.replace("libsql://", "jdbc:libsql://");
        if (!jdbcUrl.startsWith("jdbc:")) {
            jdbcUrl = "jdbc:libsql://" + url;
        }

        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl(jdbcUrl + "?authToken=" + authToken);

        // Turso-specific: Lower pool size since it's a remote connection
        config.setMaximumPoolSize(Math.min(5, DatabaseConfig.getInstance().getConnectionPoolSize()));

        VonixCore.LOGGER.info("[VonixCore] Using Turso database at {}", url);
    }

    /**
     * Configure Supabase (PostgreSQL) connection.
     */
    private void configureSupabase(HikariConfig config) {
        String host = DatabaseConfig.getInstance().getSupabaseHost();
        int port = DatabaseConfig.getInstance().getSupabasePort();
        String database = DatabaseConfig.getInstance().getSupabaseDatabase();
        String password = DatabaseConfig.getInstance().getSupabasePassword();

        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%d/%s?sslmode=require&prepareThreshold=0",
                host, port, database));
        config.setUsername("postgres");
        config.setPassword(password);

        // Supabase-specific: Connection handling for serverless
        config.setMaximumPoolSize(Math.min(5, DatabaseConfig.getInstance().getConnectionPoolSize()));
        config.addDataSourceProperty("socketTimeout", "30");

        VonixCore.LOGGER.info("[VonixCore] Using Supabase database at {}:{}", host, port);
    }

    /**
     * Create the necessary database tables.
     * Uses appropriate SQL syntax based on database type.
     */
    private void createTables() throws SQLException {
        String autoIncrement = getAutoIncrementSyntax();
        String textType = getTextTypeSyntax();

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Block log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_block (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type %s NOT NULL,
                        old_type %s,
                        old_data %s,
                        new_type %s,
                        new_data %s,
                        action INTEGER NOT NULL,
                        rolled_back INTEGER DEFAULT 0
                    )
                    """, autoIncrement, textType, textType, textType, textType, textType, textType, textType));

            // Container log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_container (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type %s NOT NULL,
                        item %s NOT NULL,
                        amount INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        rolled_back INTEGER DEFAULT 0
                    )
                    """, autoIncrement, textType, textType, textType, textType));

            // Entity log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_entity (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        entity_type %s NOT NULL,
                        entity_data %s,
                        action INTEGER NOT NULL
                    )
                    """, autoIncrement, textType, textType, textType, textType));

            // Chat log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_chat (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        message %s NOT NULL
                    )
                    """, autoIncrement, textType, textType, textType));

            // Command log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_command (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        command %s NOT NULL
                    )
                    """, autoIncrement, textType, textType, textType));

            // Sign log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_sign (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        text %s
                    )
                    """, autoIncrement, textType, textType, textType));

            // Interaction log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_interaction (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        world %s NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type %s NOT NULL
                    )
                    """, autoIncrement, textType, textType, textType));

            // User cache table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_user (
                        id INTEGER PRIMARY KEY %s,
                        uuid %s UNIQUE NOT NULL,
                        username %s NOT NULL
                    )
                    """, autoIncrement, textType, textType));

            // Economy table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_economy (
                        uuid %s PRIMARY KEY,
                        username %s NOT NULL,
                        balance DOUBLE PRECISION DEFAULT 0,
                        last_transaction BIGINT DEFAULT 0
                    )
                    """, textType, textType));

            // Homes table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_homes (
                        id INTEGER PRIMARY KEY %s,
                        uuid %s NOT NULL,
                        name %s NOT NULL,
                        world %s NOT NULL,
                        x DOUBLE PRECISION NOT NULL,
                        y DOUBLE PRECISION NOT NULL,
                        z DOUBLE PRECISION NOT NULL,
                        yaw REAL DEFAULT 0,
                        pitch REAL DEFAULT 0
                    )
                    """, autoIncrement, textType, textType, textType));

            // Warps table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_warps (
                        name %s PRIMARY KEY,
                        world %s NOT NULL,
                        x DOUBLE PRECISION NOT NULL,
                        y DOUBLE PRECISION NOT NULL,
                        z DOUBLE PRECISION NOT NULL,
                        yaw REAL DEFAULT 0,
                        pitch REAL DEFAULT 0
                    )
                    """, textType, textType));

            // Permission groups table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_groups (
                        name %s PRIMARY KEY,
                        display_name %s,
                        prefix %s,
                        suffix %s,
                        weight INTEGER DEFAULT 0,
                        parent %s,
                        permissions %s
                    )
                    """, textType, textType, textType, textType, textType, textType));

            // Permission users table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_users (
                        uuid %s PRIMARY KEY,
                        username %s,
                        primary_group %s DEFAULT 'default',
                        groups %s,
                        prefix %s,
                        suffix %s,
                        permissions %s
                    )
                    """, textType, textType, textType, textType, textType, textType, textType));

            // Discord linked accounts table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vonixcore_discord_links (
                        minecraft_uuid %s PRIMARY KEY,
                        discord_id %s UNIQUE NOT NULL,
                        linked_at BIGINT NOT NULL
                    )
                    """, textType, textType));

            // Create indexes for query optimization
            createIndexes(stmt);

            VonixCore.LOGGER.info("[VonixCore] Database tables created/verified");
        }
    }

    /**
     * Get the appropriate AUTO_INCREMENT syntax for the database type.
     */
    private String getAutoIncrementSyntax() {
        return switch (databaseType) {
            case MYSQL -> "AUTO_INCREMENT";
            case POSTGRESQL, SUPABASE -> "GENERATED ALWAYS AS IDENTITY";
            default -> "AUTOINCREMENT";
        };
    }

    /**
     * Get the appropriate TEXT type syntax for the database type.
     */
    private String getTextTypeSyntax() {
        return switch (databaseType) {
            case MYSQL -> "VARCHAR(255)";
            case POSTGRESQL, SUPABASE -> "TEXT";
            default -> "TEXT";
        };
    }

    /**
     * Create database indexes for query optimization.
     */
    private void createIndexes(Statement stmt) throws SQLException {
        // Block table indexes
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_time ON vp_block (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_user ON vp_block (user)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_location ON vp_block (world, x, y, z)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_coords ON vp_block (x, z)");

        // Container table indexes
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_container_time ON vp_container (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_container_location ON vp_container (world, x, y, z)");

        // Entity table indexes
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_entity_time ON vp_entity (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_entity_location ON vp_entity (world, x, y, z)");

        // User table indexes
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_user_uuid ON vp_user (uuid)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_user_name ON vp_user (username)");

        // Homes index
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_homes_uuid ON vonixcore_homes (uuid)");

        // Economy index
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_economy_balance ON vonixcore_economy (balance DESC)");
    }

    /**
     * Execute SQL and ignore errors (for index creation on databases that don't
     * support IF NOT EXISTS).
     */
    private void executeIgnoreError(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException ignored) {
            // Index may already exist or syntax not supported
        }
    }

    /**
     * Get a connection from the pool.
     *
     * @return A database connection
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * Get the current database type.
     *
     * @return The database type enum
     */
    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    /**
     * Check if using MySQL.
     *
     * @return True if using MySQL
     */
    public boolean isMySQL() {
        return databaseType == DatabaseType.MYSQL;
    }

    /**
     * Check if using PostgreSQL (including Supabase).
     *
     * @return True if using PostgreSQL or Supabase
     */
    public boolean isPostgreSQL() {
        return databaseType == DatabaseType.POSTGRESQL || databaseType == DatabaseType.SUPABASE;
    }

    /**
     * Check if using SQLite (including Turso).
     *
     * @return True if using SQLite or Turso
     */
    public boolean isSQLite() {
        return databaseType == DatabaseType.SQLITE || databaseType == DatabaseType.TURSO;
    }

    /**
     * Close the database connection pool.
     */
    public void close() {
        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            VonixCore.LOGGER.info("[VonixCore] Database connection pool closed");
        }
    }
}
