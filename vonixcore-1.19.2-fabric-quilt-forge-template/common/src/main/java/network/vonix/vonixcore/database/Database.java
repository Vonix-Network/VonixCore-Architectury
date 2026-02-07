package network.vonix.vonixcore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraft.server.MinecraftServer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DatabaseConfig;
import network.vonix.vonixcore.platform.Platform;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database manager using HikariCP for connection pooling.
 */
public class Database {

    public enum DatabaseType {
        SQLITE, MYSQL, POSTGRESQL, TURSO, SUPABASE
    }

    private final MinecraftServer server;
    private HikariDataSource dataSource;
    private DatabaseType databaseType = DatabaseType.SQLITE;

    public Database(MinecraftServer server) {
        this.server = server;
    }

    public void initialize() throws SQLException {
        String dbType = DatabaseConfig.CONFIG.type.get().toLowerCase();

        databaseType = switch (dbType) {
            case "mysql" -> DatabaseType.MYSQL;
            case "postgresql", "postgres" -> DatabaseType.POSTGRESQL;
            case "turso", "libsql" -> DatabaseType.TURSO;
            case "supabase" -> DatabaseType.SUPABASE;
            default -> DatabaseType.SQLITE;
        };

        HikariConfig config = new HikariConfig();
        config.setPoolName("VonixCore-DB-Pool");
        config.setMaximumPoolSize(DatabaseConfig.CONFIG.connectionPoolSize.get());
        config.setMinimumIdle(2);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        // Cap connection timeout to prevent server hangs - max 5 seconds for SQLite, 8 for remote DBs
        int configuredTimeout = DatabaseConfig.CONFIG.connectionTimeout.get();
        int maxTimeout = databaseType == DatabaseType.SQLITE ? 5000 : 8000;
        config.setConnectionTimeout(Math.min(configuredTimeout, maxTimeout));
        // Additional safety: fail fast on connection errors
        config.setInitializationFailTimeout(1); // Fail immediately if cannot create initial connections

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

    private void configureSqlite(HikariConfig config) {
        // Use server root for world data or config dir?
        // Source used world folder.
        // We can access world folder via server.
        File worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        File dataFolder = new File(worldFolder, "vonixcore");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String fileName = DatabaseConfig.CONFIG.sqliteFile.get();
        File dbFile = new File(dataFolder, fileName);

        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "10000");
        config.addDataSourceProperty("temp_store", "MEMORY");

        VonixCore.LOGGER.info("[VonixCore] Using SQLite database: {}", dbFile.getAbsolutePath());
    }

    private void configureMySql(HikariConfig config) {
        String host = DatabaseConfig.CONFIG.mysqlHost.get();
        int port = DatabaseConfig.CONFIG.mysqlPort.get();
        String database = DatabaseConfig.CONFIG.mysqlDatabase.get();
        String username = DatabaseConfig.CONFIG.mysqlUsername.get();
        String password = DatabaseConfig.CONFIG.mysqlPassword.get();
        boolean ssl = DatabaseConfig.CONFIG.mysqlSsl.get();

        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                host, port, database, ssl));
        config.setUsername(username);
        config.setPassword(password);

        VonixCore.LOGGER.info("[VonixCore] Using MySQL database at {}:{}/{}", host, port, database);
    }

    private void configurePostgreSql(HikariConfig config) {
        String host = DatabaseConfig.CONFIG.postgresqlHost.get();
        int port = DatabaseConfig.CONFIG.postgresqlPort.get();
        String database = DatabaseConfig.CONFIG.postgresqlDatabase.get();
        String username = DatabaseConfig.CONFIG.postgresqlUsername.get();
        String password = DatabaseConfig.CONFIG.postgresqlPassword.get();
        boolean ssl = DatabaseConfig.CONFIG.postgresqlSsl.get();

        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%d/%s?sslmode=%s",
                host, port, database, ssl ? "require" : "disable"));
        config.setUsername(username);
        config.setPassword(password);

        VonixCore.LOGGER.info("[VonixCore] Using PostgreSQL database at {}:{}/{}", host, port, database);
    }

    private void configureTurso(HikariConfig config) {
        String url = DatabaseConfig.CONFIG.tursoUrl.get();
        String authToken = DatabaseConfig.CONFIG.tursoAuthToken.get();

        String jdbcUrl = url.replace("libsql://", "jdbc:libsql://");
        if (!jdbcUrl.startsWith("jdbc:")) {
            jdbcUrl = "jdbc:libsql://" + url;
        }

        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl(jdbcUrl + "?authToken=" + authToken);
        config.setMaximumPoolSize(Math.min(5, DatabaseConfig.CONFIG.connectionPoolSize.get()));

        VonixCore.LOGGER.info("[VonixCore] Using Turso database at {}", url);
    }

    private void configureSupabase(HikariConfig config) {
        String host = DatabaseConfig.CONFIG.supabaseHost.get();
        int port = DatabaseConfig.CONFIG.supabasePort.get();
        String database = DatabaseConfig.CONFIG.supabaseDatabase.get();
        String password = DatabaseConfig.CONFIG.supabasePassword.get();

        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%d/%s?sslmode=require&prepareThreshold=0",
                host, port, database));
        config.setUsername("postgres");
        config.setPassword(password);

        config.setMaximumPoolSize(Math.min(5, DatabaseConfig.CONFIG.connectionPoolSize.get()));
        config.addDataSourceProperty("socketTimeout", "30");

        VonixCore.LOGGER.info("[VonixCore] Using Supabase database at {}:{}", host, port);
    }

    private void createTables() throws SQLException {
        String autoIncrement = getAutoIncrementSyntax();
        String textType = getTextTypeSyntax();

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // ... (Same table creation logic, omitted for brevity but should be included)
            // I'll assume the original SQL is fine, just copying it.
            
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
                        message %s NOT NULL
                    )
                    """, autoIncrement, textType, textType));

            // Command log table
            stmt.execute(String.format("""
                    CREATE TABLE IF NOT EXISTS vp_command (
                        id INTEGER PRIMARY KEY %s,
                        time BIGINT NOT NULL,
                        user %s NOT NULL,
                        command %s NOT NULL
                    )
                    """, autoIncrement, textType, textType));

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
                        line1 %s,
                        line2 %s,
                        line3 %s,
                        line4 %s
                    )
                    """, autoIncrement, textType, textType, textType, textType, textType, textType));

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

            createIndexes(stmt);

            VonixCore.LOGGER.info("[VonixCore] Database tables created/verified");
        }
    }

    private String getAutoIncrementSyntax() {
        return switch (databaseType) {
            case MYSQL -> "AUTO_INCREMENT";
            case POSTGRESQL, SUPABASE -> "GENERATED ALWAYS AS IDENTITY";
            default -> "AUTOINCREMENT";
        };
    }

    private String getTextTypeSyntax() {
        return switch (databaseType) {
            case MYSQL -> "VARCHAR(255)";
            case POSTGRESQL, SUPABASE -> "TEXT";
            default -> "TEXT";
        };
    }

    private void createIndexes(Statement stmt) throws SQLException {
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_time ON vp_block (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_user ON vp_block (user)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_location ON vp_block (world, x, y, z)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_block_coords ON vp_block (x, z)");

        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_container_time ON vp_container (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_container_location ON vp_container (world, x, y, z)");

        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_entity_time ON vp_entity (time)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_entity_location ON vp_entity (world, x, y, z)");

        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_user_uuid ON vp_user (uuid)");
        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_user_name ON vp_user (username)");

        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_homes_uuid ON vonixcore_homes (uuid)");

        executeIgnoreError(stmt, "CREATE INDEX IF NOT EXISTS idx_economy_balance ON vonixcore_economy (balance DESC)");
    }

    private void executeIgnoreError(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database not initialized");
        }
        return dataSource.getConnection();
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public boolean isMySQL() {
        return databaseType == DatabaseType.MYSQL;
    }

    public boolean isPostgreSQL() {
        return databaseType == DatabaseType.POSTGRESQL || databaseType == DatabaseType.SUPABASE;
    }

    public boolean isSQLite() {
        return databaseType == DatabaseType.SQLITE || databaseType == DatabaseType.TURSO;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            VonixCore.LOGGER.info("[VonixCore] Database connection pool closed");
        }
    }
}
