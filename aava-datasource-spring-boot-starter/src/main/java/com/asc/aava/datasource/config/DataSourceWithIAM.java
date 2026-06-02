package com.asc.aava.datasource.config;

import com.asc.aava.datasource.autoconfigure.AavaDatasourceProperties;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * AWS RDS IAM DataSource configuration.
 * Active when aava.datasource.auth-mode=aws-iam.
 *
 * Required properties:
 *   datasource.url           - JDBC URL to the RDS instance
 *   datasource.username      - IAM database user
 *   aava.secrets.region      - AWS region (e.g. us-east-1)
 *   aava.secrets.assume-role-arn - ARN of the IAM role to assume for RDS token generation
 *
 * AWS IAM tokens expire after 15 minutes; Hikari maxLifetime is set to 14 minutes
 * to force connection refresh before token expiry.
 */
@Configuration
@ConditionalOnProperty(
        name = "aava.datasource.auth-mode",
        havingValue = "aws-iam"
)
@DependsOn("applicationPropertiesLoader")
public class DataSourceWithIAM {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceWithIAM.class);

    private static final String DEFAULT_DRIVER  = "org.postgresql.Driver";
    private static final String DEFAULT_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";
    // AWS IAM tokens expire in 15 min; refresh pool connections at 14 min.
    private static final long   IAM_MAX_LIFETIME_MS = 14 * 60 * 1000L;

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

    @Value("${aava.secrets.region:}")
    private String awsRegion;

    @Value("${aava.secrets.assume-role-arn:}")
    private String assumeRoleArn;

    @Value("${spring.application.name:aava-service}")
    private String applicationName;

    public DataSourceWithIAM(AavaDatasourceProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        logger.info("Initializing AWS RDS IAM DataSource for application: {}", applicationName);

        String schema = resolveSchema();

        String jdbcUrl = dbUrl;
        if (jdbcUrl != null && !jdbcUrl.contains("currentSchema=")) {
            jdbcUrl = jdbcUrl + "?currentSchema=" + schema;
        }

        Region region = Region.of(awsRegion);
        logger.info("RDS IAM: url={}, user={}, region={}, role={}", jdbcUrl, dbUsername, awsRegion, assumeRoleArn);

        StsAssumeRoleCredentialsProvider credentialsProvider = buildCredentialsProvider(assumeRoleArn, awsRegion);

        // Parse host and port from the JDBC URL
        String cleanUrl = jdbcUrl.replace("jdbc:postgresql://", "");
        String host = cleanUrl.split(":")[0];
        int port = Integer.parseInt(cleanUrl.split(":")[1].split("/")[0]);

        RdsIamDataSource iamDataSource = new RdsIamDataSource(host, port, dbUsername, region, jdbcUrl, credentialsProvider);
        iamDataSource.setDriverClassName(DEFAULT_DRIVER);

        HikariConfig config = new HikariConfig();
        config.setDataSource(iamDataSource);
        config.setConnectionTimeout(hikariConnectionTimeout);
        config.setMinimumIdle(hikariMinimumIdle);
        config.setMaximumPoolSize(hikariMaximumPoolSize);
        config.setIdleTimeout(hikariIdleTimeout);
        config.setMaxLifetime(IAM_MAX_LIFETIME_MS);
        config.setAutoCommit(hikariAutoCommit);
        config.setSchema(schema);
        config.setPoolName(applicationName + "-HikariCP-IAM");

        DatabaseConfig.applyPrepStmtOptimizations(config);

        logger.info("AWS IAM DataSource configured: url={}, schema={}", jdbcUrl, schema);
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

        logger.info("EntityManagerFactory (IAM) configured: dialect={}, schema={}", dialect, schema);
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

    private StsAssumeRoleCredentialsProvider buildCredentialsProvider(String roleArn, String region) {
        StsClient stsClient = StsClient.builder()
                .region(Region.of(region))
                .build();
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName(applicationName + "-session")
                        .build())
                .build();
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
     * Custom DataSource that overrides getPassword() to generate a fresh AWS RDS IAM auth token
     * on every new connection. Token generation uses the assumed-role credentials provider.
     */
    public static class RdsIamDataSource extends DriverManagerDataSource {

        private static final Logger log = LoggerFactory.getLogger(RdsIamDataSource.class);

        private final RdsUtilities rdsUtilities;
        private final String host;
        private final int port;
        private final String user;

        public RdsIamDataSource(String host, int port, String user, Region region,
                                String url, StsAssumeRoleCredentialsProvider credentialsProvider) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.setUrl(url);
            this.setUsername(user);

            Properties props = new Properties();
            props.setProperty("ssl",     "true");
            props.setProperty("sslmode", "require");
            this.setConnectionProperties(props);

            this.rdsUtilities = RdsUtilities.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(region)
                    .build();
        }

        @Override
        public String getPassword() {
            try {
                log.debug("Generating IAM auth token for RDS connection...");
                String token = rdsUtilities.generateAuthenticationToken(
                        GenerateAuthenticationTokenRequest.builder()
                                .hostname(host)
                                .port(port)
                                .username(user)
                                .build()
                );
                log.debug("IAM token generated, length={}", token.length());
                return token;
            } catch (Exception e) {
                log.error("Failed to generate IAM auth token for RDS", e);
                throw new RuntimeException("IAM token generation failed", e);
            }
        }
    }
}
