package com.asc.aava.datasource.config;

import com.asc.aava.datasource.autoconfigure.AavaDatasourceProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;

/**
 * Cloud SQL (PostgreSQL) DataSource using GCP Workload Identity Federation.
 * Active when aava.datasource.auth-mode=gcp-wif.
 *
 * Authentication flow:
 *   GoogleCredentials.getApplicationDefault() resolves credentials in order:
 *     1. GOOGLE_APPLICATION_CREDENTIALS (WIF config JSON, used by GKE WI / GitHub Actions)
 *     2. GCE/GKE metadata server
 *     3. gcloud ADC (local dev)
 *   A Cloud Platform scoped OAuth2 token is used as the PostgreSQL password when Cloud SQL
 *   IAM authentication is enabled on the instance.
 *
 * Tokens expire in 60 minutes; Hikari maxLifetime is set to 50 minutes so connections are
 * refreshed before expiry.
 */
@Configuration
@ConditionalOnProperty(
        name = "aava.datasource.auth-mode",
        havingValue = "gcp-wif"
)
@DependsOn("applicationPropertiesLoader")
public class DataSourceWithGCPWIF {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceWithGCPWIF.class);

    private static final String GCP_CLOUDSQL_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String DEFAULT_DRIVER     = "org.postgresql.Driver";
    private static final String DEFAULT_DIALECT    = "org.hibernate.dialect.PostgreSQLDialect";
    private static final long   GCP_MAX_LIFETIME_MS = 50 * 60 * 1000L;

    private final AavaDatasourceProperties props;

    @Value("${datasource.url:}")
    private String dbUrl;

    @Value("${datasource.username:}")
    private String dbUsername;

    @Value("${datasource.hikari.connection-timeout:30000}")
    private Long hikariConnectionTimeout;

    @Value("${datasource.hikari.minimum-idle:2}")
    private Integer hikariMinimumIdle;

    @Value("${datasource.hikari.maximum-pool-size:5}")
    private Integer hikariMaximumPoolSize;

    @Value("${datasource.hikari.idle-timeout:300000}")
    private Long hikariIdleTimeout;

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

    public DataSourceWithGCPWIF(AavaDatasourceProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        logger.info("Initializing GCP WIF DataSource for application: {}", applicationName);

        String schema = resolveSchema();

        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials
                    .getApplicationDefault()
                    .createScoped(Collections.singletonList(GCP_CLOUDSQL_SCOPE));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load GCP Application Default Credentials", e);
        }

        String jdbcUrl = dbUrl;
        if (jdbcUrl != null && !jdbcUrl.contains("currentSchema=")) {
            jdbcUrl = jdbcUrl + "?currentSchema=" + schema;
        }

        GcpWifDataSource gcpDataSource = new GcpWifDataSource(credentials, jdbcUrl, dbUsername);
        gcpDataSource.setDriverClassName(DEFAULT_DRIVER);

        HikariConfig config = new HikariConfig();
        config.setDataSource(gcpDataSource);
        config.setConnectionTimeout(hikariConnectionTimeout);
        config.setMinimumIdle(hikariMinimumIdle);
        config.setMaximumPoolSize(hikariMaximumPoolSize);
        config.setIdleTimeout(hikariIdleTimeout);
        config.setMaxLifetime(GCP_MAX_LIFETIME_MS);
        config.setAutoCommit(hikariAutoCommit);
        config.setSchema(schema);
        config.setPoolName(applicationName + "-HikariCP-GCPWIF");

        DatabaseConfig.applyPrepStmtOptimizations(config);
        config.addDataSourceProperty("ssl",     "true");
        config.addDataSourceProperty("sslmode", "require");

        logger.info("GCP WIF DataSource configured: url={}, user={}", jdbcUrl, dbUsername);
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
        String dialect = resolveDialect();
        String schema  = resolveSchema();

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(props.getBasePackage().split(","));

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(Boolean.parseBoolean(jpaGenerateDdl));
        vendorAdapter.setShowSql(jpaShowSql);
        em.setJpaVendorAdapter(vendorAdapter);

        em.setJpaProperties(buildHibernateProperties(dialect, schema));

        logger.info("EntityManagerFactory (GCP WIF) configured: dialect={}, schema={}", dialect, schema);
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

    private Properties buildHibernateProperties(String dialect, String schema) {
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

    /**
     * Custom DataSource that refreshes GCP OAuth2 credentials and returns a fresh token
     * as the PostgreSQL password on every new connection.
     */
    public static class GcpWifDataSource extends DriverManagerDataSource {

        private static final Logger log = LoggerFactory.getLogger(GcpWifDataSource.class);

        private final GoogleCredentials credentials;

        public GcpWifDataSource(GoogleCredentials credentials, String url, String username) {
            this.credentials = credentials;
            this.setUrl(url);
            this.setUsername(username);

            Properties props = new Properties();
            props.setProperty("ssl",     "true");
            props.setProperty("sslmode", "require");
            this.setConnectionProperties(props);
        }

        @Override
        public String getPassword() {
            try {
                log.debug("Refreshing GCP credentials for Cloud SQL connection...");
                credentials.refreshIfExpired();
                String token = credentials.getAccessToken().getTokenValue();
                log.debug("GCP token acquired, expires at {}", credentials.getAccessToken().getExpirationTime());
                return token;
            } catch (IOException e) {
                log.error("Failed to refresh GCP credentials for Cloud SQL", e);
                throw new RuntimeException("GCP WIF token refresh failed", e);
            }
        }
    }
}
