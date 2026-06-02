package com.asc.aava.datasource.config;

import com.asc.aava.datasource.autoconfigure.AavaDatasourceProperties;
import com.asc.aava.secrets.SecretsManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Password-based DataSource configuration (default mode).
 * Active when aava.datasource.auth-mode=password or when the property is absent.
 */
@Configuration
@ConditionalOnProperty(
        name = "aava.datasource.auth-mode",
        havingValue = "password",
        matchIfMissing = true
)
@DependsOn("applicationPropertiesLoader")
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String DEFAULT_DRIVER  = "org.postgresql.Driver";
    private static final String DEFAULT_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";

    private final AavaDatasourceProperties props;

    @Autowired(required = false)
    private SecretsManager secretsManager;

    @Value("${datasource.url:}")
    private String dbUrl;

    @Value("${datasource.username:}")
    private String dbUsername;

    @Value("${datasource.password:}")
    private String dbPassword;

    @Value("${datasource.password.keyname:}")
    private String dbPasswordKeyName;

    @Value("${datasource.hikari.connection-timeout:30000}")
    private Long hikariConnectionTimeout;

    @Value("${datasource.hikari.minimum-idle:2}")
    private Integer hikariMinimumIdle;

    @Value("${datasource.hikari.maximum-pool-size:5}")
    private Integer hikariMaximumPoolSize;

    @Value("${datasource.hikari.idle-timeout:300000}")
    private Long hikariIdleTimeout;

    @Value("${datasource.hikari.max-lifetime:1800000}")
    private Long hikariMaxLifetime;

    @Value("${datasource.hikari.auto-commit:false}")
    private Boolean hikariAutoCommit;

    @Value("${datasource.hikari.schema:#{null}}")
    private String hikariSchema;

    @Value("${properties.hibernate.default_schema:#{null}}")
    private String jpaDefaultSchema;

    @Value("${database-platform:}")
    private String jpaDialect;

    @Value("${hibernate.ddl-auto:none}")
    private String jpaGenerateDdl;

    @Value("${show-sql:false}")
    private Boolean jpaShowSql;

    @Value("${properties.hibernate.format_sql:false}")
    private Boolean jpaFormatSql;

    @Value("${spring.application.name:aava-service}")
    private String applicationName;

    @Value("${datasource.password.keyname:}")
    private String DB_PASSWORD_KEY_NAME;

    public DatabaseConfig(AavaDatasourceProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        logger.info("Initializing password-mode DataSource for application: {}", applicationName);

        String schema  = resolveSchema();
        String dialect = resolveDialect();

        HikariConfig config = new HikariConfig();

        String jdbcUrl = dbUrl;
        if (jdbcUrl != null && !jdbcUrl.contains("currentSchema=")) {
            jdbcUrl = jdbcUrl + "?currentSchema=" + schema;
        }
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(dbUsername);
        config.setPassword(resolvePassword());
        config.setDriverClassName(DEFAULT_DRIVER);

        config.addDataSourceProperty("ssl",     props.getSsl());
        config.addDataSourceProperty("sslmode", props.getSslmode());

        config.setConnectionTimeout(hikariConnectionTimeout);
        config.setMinimumIdle(hikariMinimumIdle);
        config.setMaximumPoolSize(hikariMaximumPoolSize);
        config.setIdleTimeout(hikariIdleTimeout);
        config.setMaxLifetime(hikariMaxLifetime);
        config.setAutoCommit(hikariAutoCommit);
        config.setSchema(schema);
        config.setPoolName(applicationName + "-HikariCP");

        applyPrepStmtOptimizations(config);

        logger.info("Password-mode DataSource configured: url={}, schema={}", jdbcUrl, schema);
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        String schema  = resolveSchema();
        String dialect = resolveDialect();

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(props.getBasePackage().split(","));

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(Boolean.parseBoolean(jpaGenerateDdl));
        vendorAdapter.setShowSql(jpaShowSql);
        em.setJpaVendorAdapter(vendorAdapter);

        em.setJpaProperties(buildHibernateProperties(dialect, schema));

        logger.info("EntityManagerFactory configured: dialect={}, schema={}, packages={}",
                dialect, schema);
        return em;
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        JpaTransactionManager txManager = new JpaTransactionManager();
        txManager.setEntityManagerFactory(entityManagerFactory.getObject());
        return txManager;
    }

    // --- helpers ---

    private String resolvePassword() {

        String dbSecretPassword = null;

        if (secretsManager != null) {
            dbSecretPassword = secretsManager.getSecret(DB_PASSWORD_KEY_NAME);
        }

        if (dbSecretPassword == null || dbSecretPassword.isEmpty()) {
            dbSecretPassword = dbPassword;
        }
        // Allow subclasses / future SecretsManager integration via property overlay.
        // The consuming service's ApplicationPropertiesLoader can override datasource.password.
        return dbSecretPassword;
    }

    private String resolveSchema() {
        if (hikariSchema != null && !hikariSchema.isBlank()) return hikariSchema;
        return props.getSchema();
    }

    private String resolveDialect() {
        if (jpaDialect != null && !jpaDialect.isBlank()) return jpaDialect;
        return DEFAULT_DIALECT;
    }

    private String resolveJpaSchema() {
        if (jpaDefaultSchema != null && !jpaDefaultSchema.isBlank()) return jpaDefaultSchema;
        return resolveSchema();
    }

    static void applyPrepStmtOptimizations(HikariConfig config) {
        config.addDataSourceProperty("cachePrepStmts",            "true");
        config.addDataSourceProperty("prepStmtCacheSize",         "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit",     "2048");
        config.addDataSourceProperty("useServerPrepStmts",        "true");
        config.addDataSourceProperty("useLocalSessionState",      "true");
        config.addDataSourceProperty("rewriteBatchedStatements",  "true");
        config.addDataSourceProperty("cacheResultSetMetadata",    "true");
        config.addDataSourceProperty("cacheServerConfiguration",  "true");
        config.addDataSourceProperty("elideSetAutoCommits",       "true");
        config.addDataSourceProperty("maintainTimeStats",         "false");
    }

    Properties buildHibernateProperties(String dialect, String schema) {
        Properties p = new Properties();
        p.setProperty("hibernate.dialect",                              dialect);
        p.setProperty("hibernate.default_schema",                      resolveJpaSchema());
        p.setProperty("hibernate.format_sql",                          String.valueOf(jpaFormatSql));
        p.setProperty("hibernate.jdbc.batch_size",                     "20");
        p.setProperty("hibernate.order_inserts",                       "true");
        p.setProperty("hibernate.order_updates",                       "true");
        p.setProperty("hibernate.jdbc.batch_versioned_data",           "true");
        p.setProperty("hibernate.connection.provider_disables_autocommit", "true");
        p.setProperty("hibernate.temp.use_jdbc_metadata_defaults",    "false");
        return p;
    }
}
