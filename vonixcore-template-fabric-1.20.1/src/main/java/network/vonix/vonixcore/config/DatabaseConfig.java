package network.vonix.vonixcore.config;

import java.nio.file.Path;

/**
 * Database configuration for VonixCore.
 * Stored in config/vonixcore-database.yml
 * 
 * Supported database types:
 * - sqlite: Local SQLite file (default, recommended for single servers)
 * - mysql: MySQL/MariaDB server
 * - postgresql: PostgreSQL server
 * - turso: Turso (LibSQL) edge database
 * - supabase: Supabase PostgreSQL database
 */
public class DatabaseConfig extends BaseConfig {

    private static DatabaseConfig instance;

    public static DatabaseConfig getInstance() {
        if (instance == null) {
            instance = new DatabaseConfig();
        }
        return instance;
    }

    public static void init(Path configDir) {
        getInstance().loadConfig(configDir);
    }

    private DatabaseConfig() {
        super("vonixcore-database.yml");
    }

    private void loadConfig(Path configDir) {
        super.load(configDir);
    }

    @Override
    protected String getHeader() {
        return """
                # VonixCore Database Configuration
                # Configure database connection and performance settings
                #
                # Supported types: sqlite, mysql, postgresql, turso, supabase
                """;
    }

    @Override
    protected void setDefaults() {
        // Database type
        setDefault("database.type", "sqlite");

        // SQLite settings
        setDefault("sqlite.file", "vonixcore.db");

        // MySQL settings
        setDefault("mysql.host", "localhost");
        setDefault("mysql.port", 3306);
        setDefault("mysql.database", "vonixcore");
        setDefault("mysql.username", "root");
        setDefault("mysql.password", "");
        setDefault("mysql.ssl", false);

        // PostgreSQL settings
        setDefault("postgresql.host", "localhost");
        setDefault("postgresql.port", 5432);
        setDefault("postgresql.database", "vonixcore");
        setDefault("postgresql.username", "postgres");
        setDefault("postgresql.password", "");
        setDefault("postgresql.ssl", false);

        // Turso settings
        setDefault("turso.url", "libsql://your-database.turso.io");
        setDefault("turso.auth_token", "");

        // Supabase settings
        setDefault("supabase.host", "db.xxxxxxxxxxxx.supabase.co");
        setDefault("supabase.port", 5432);
        setDefault("supabase.database", "postgres");
        setDefault("supabase.password", "");

        // Connection pool settings
        setDefault("pool.max_connections", 10);
        setDefault("pool.timeout_ms", 5000);

        // Performance settings
        setDefault("performance.batch_size", 500);
        setDefault("performance.batch_delay_ms", 500);
        setDefault("performance.purge_days", 30);
    }

    // ============ Getters ============

    public String getType() {
        return getString("database.type", "sqlite");
    }

    // SQLite
    public String getSqliteFile() {
        return getString("sqlite.file", "vonixcore.db");
    }

    // MySQL
    public String getMysqlHost() {
        return getString("mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return getInt("mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return getString("mysql.database", "vonixcore");
    }

    public String getMysqlUsername() {
        return getString("mysql.username", "root");
    }

    public String getMysqlPassword() {
        return getString("mysql.password", "");
    }

    public boolean getMysqlSsl() {
        return getBoolean("mysql.ssl", false);
    }

    // PostgreSQL
    public String getPostgresqlHost() {
        return getString("postgresql.host", "localhost");
    }

    public int getPostgresqlPort() {
        return getInt("postgresql.port", 5432);
    }

    public String getPostgresqlDatabase() {
        return getString("postgresql.database", "vonixcore");
    }

    public String getPostgresqlUsername() {
        return getString("postgresql.username", "postgres");
    }

    public String getPostgresqlPassword() {
        return getString("postgresql.password", "");
    }

    public boolean getPostgresqlSsl() {
        return getBoolean("postgresql.ssl", false);
    }

    // Turso
    public String getTursoUrl() {
        return getString("turso.url", "libsql://your-database.turso.io");
    }

    public String getTursoAuthToken() {
        return getString("turso.auth_token", "");
    }

    // Supabase
    public String getSupabaseHost() {
        return getString("supabase.host", "db.xxxxxxxxxxxx.supabase.co");
    }

    public int getSupabasePort() {
        return getInt("supabase.port", 5432);
    }

    public String getSupabaseDatabase() {
        return getString("supabase.database", "postgres");
    }

    public String getSupabasePassword() {
        return getString("supabase.password", "");
    }

    // Pool
    public int getConnectionPoolSize() {
        return getInt("pool.max_connections", 10);
    }

    public int getConnectionTimeout() {
        return getInt("pool.timeout_ms", 5000);
    }

    // Performance
    public int getConsumerBatchSize() {
        return getInt("performance.batch_size", 500);
    }

    public int getConsumerDelayMs() {
        return getInt("performance.batch_delay_ms", 500);
    }

    public int getDataPurgeDays() {
        return getInt("performance.purge_days", 30);
    }
}
