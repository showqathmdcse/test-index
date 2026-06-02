package com.asc.aava.datasource.config;

import com.asc.aava.datasource.autoconfigure.AavaDatasourceProperties;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
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
import java.util.Properties;

/**
 * Azure Database for PostgreSQL DataSource using Workload Identity Federation.
 * Active when aava.datasource.auth-mode=azure-wif.
 *
 * Authentication flow:
 *   DefaultAzureCredential resolves credentials (Kubernetes WIF env vars → Managed Identity →
 *   Azure CLI) and obtains an OAuth2 token scoped to ossrdbms-aad.database.windows.net.
 *   Azure Database for PostgreSQL accepts this token as the connection password when the DB
 *   user is an AAD principal.
 *
 * Token TTL is typically 60-75 minutes; Hikari maxLifetime is set to 50 minutes to force
 * connection refresh before the token expires.
 */
@Configuration
@ConditionalOnProperty(
        name = "aava.datasource.auth-mode",
        havingValue = "azure-wif"
)
@DependsOn("applicationPropertiesLoader")
public class DataSourceWithAzureWIF {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceWithAzureWIF.class);

    private static final String AZURE_POSTGRESQL_SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
    private static final String DEFAULT_DRIVER         = "org.postgresql.Driver";
    private static final String DEFAULT_DIALECT        = "org.hibernate.dialect.PostgreSQLDialect";
    private static final long   AZURE_MAX_LIFETIME_MS  = 50 * 60 * 1000L;

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

    public DataSourceWithAzureWIF(AavaDatasourceProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        logger.info("Initializing Azure WIF DataSource for application: {}", applicationName);

        String schema = resolveSchema();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();

        String jdbcUrl = dbUrl;
        if (jdbcUrl != null && !jdbcUrl.contains("currentSchema=")) {
            jdbcUrl = jdbcUrl + "?currentSchema=" + schema;
        }

        AzureWifDataSource azureDataSource = new AzureWifDataSource(credential, jdbcUrl, dbUsername);
        azureDataSource.setDriverClassName(DEFAULT_DRIVER);

        HikariConfig config = new HikariConfig();
        config.setDataSource(azureDataSource);
        config.setConnectionTimeout(hikariConnectionTimeout);
        config.setMinimumIdle(hikariMinimumIdle);
        config.setMaximumPoolSize(hikariMaximumPoolSize);
        config.setIdleTimeout(hikariIdleTimeout);
        config.setMaxLifetime(AZURE_MAX_LIFETIME_MS);
        config.setAutoCommit(hikariAutoCommit);
        config.setSchema(schema);
        config.setPoolName(applicationName + "-HikariCP-AzureWIF");

        DatabaseConfig.applyPrepStmtOptimizations(config);
        config.addDataSourceProperty("ssl",     "true");
        config.addDataSourceProperty("sslmode", "require");

        logger.info("Azure WIF DataSource configured: url={}, user={}", jdbcUrl, dbUsername);
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

        logger.info("EntityManagerFactory (Azure WIF) configured: dialect={}, schema={}", dialect, schema);
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
     * Custom DataSource that fetches a fresh Azure AD OAuth2 token on every new connection.
     * Hikari's maxLifetime ensures connections are evicted before the token expires.
     */
    public static class AzureWifDataSource extends DriverManagerDataSource {

        private static final Logger log = LoggerFactory.getLogger(AzureWifDataSource.class);

        private final TokenCredential credential;
        private final TokenRequestContext tokenRequestContext;

        public AzureWifDataSource(TokenCredential credential, String url, String username) {
            this.credential = credential;
            this.tokenRequestContext = new TokenRequestContext().addScopes(AZURE_POSTGRESQL_SCOPE);
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
                log.debug("Fetching Azure AD token for PostgreSQL connection...");
                AccessToken token = credential.getToken(tokenRequestContext).block();
                if (token == null) {
                    throw new RuntimeException("Azure AD token response was null");
                }
                log.debug("Azure AD token acquired, expires at {}", token.getExpiresAt());
                return token.getToken();
            } catch (Exception e) {
                log.error("Failed to acquire Azure AD token for PostgreSQL", e);
                throw new RuntimeException("Azure WIF token acquisition failed", e);
            }
        }
    }
}
