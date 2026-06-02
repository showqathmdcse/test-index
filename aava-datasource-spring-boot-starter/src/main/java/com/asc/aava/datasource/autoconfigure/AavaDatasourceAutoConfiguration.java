package com.asc.aava.datasource.autoconfigure;

import com.asc.aava.datasource.config.DatabaseConfig;
import com.asc.aava.datasource.config.DataSourceWithAzureWIF;
import com.asc.aava.datasource.config.DataSourceWithGCPWIF;
import com.asc.aava.datasource.config.DataSourceWithIAM;
import com.asc.aava.datasource.loader.GenericPropertiesLoader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * AAVA Datasource Spring Boot Auto-Configuration.
 *
 * Activates multi-cloud PostgreSQL DataSource beans based on the value of
 * {@code aava.datasource.auth-mode} (defaults to "password").
 *
 * Exactly one of the four @Configuration classes will be loaded:
 *   - password  → {@link DatabaseConfig}
 *   - aws-iam   → {@link DataSourceWithIAM}
 *   - azure-wif → {@link DataSourceWithAzureWIF}
 *   - gcp-wif   → {@link DataSourceWithGCPWIF}
 *
 * {@link GenericPropertiesLoader} is imported and will activate automatically
 * when aava-core-java-starter is on the classpath.
 *
 * Consuming services must set:
 *   aava.datasource.packages-to-scan=com.example.myservice
 *
 * And must exclude Spring Boot's auto-configuration in their @SpringBootApplication:
 *   exclude = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class }
 */
@AutoConfiguration(
        before = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class }
)
@EnableConfigurationProperties(AavaDatasourceProperties.class)
@ConditionalOnMissingBean(DataSource.class)
@Import({
        GenericPropertiesLoader.class,
        DatabaseConfig.class,
        DataSourceWithIAM.class,
        DataSourceWithAzureWIF.class,
        DataSourceWithGCPWIF.class
})
public class AavaDatasourceAutoConfiguration {
    // No bean definitions here — all beans are in the imported @Configuration classes.
    // Each imported class is guarded by @ConditionalOnProperty(aava.datasource.auth-mode).
}
