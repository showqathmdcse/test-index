package com.asc.aava.datasource.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the AAVA datasource starter.
 *
 * The minimum required set per auth-mode:
 *   - password  : datasource.url, datasource.username, datasource.password (or secret key-name)
 *   - aws-iam   : datasource.url, datasource.username, aava.secrets.region, aava.secrets.assume-role-arn
 *   - azure-wif : datasource.url, datasource.username
 *   - gcp-wif   : datasource.url, datasource.username
 *
 * The packages-to-scan property MUST be set so the EntityManagerFactory knows
 * which packages to scan for @Entity classes.
 */
@ConfigurationProperties(prefix = "aava.datasource")
public class AavaDatasourceProperties {

    /** Authentication mode. Defaults to 'password'. Matches DATASOURCE_AUTH_MODE env var. */
    private String authMode = "password";

    /**
     * Comma-separated base package(s) to scan for JPA @Entity classes.
     * Each consuming service sets this to its own root package, e.g. "com.example.myservice".
     * Property: aava.datasource.base-package
     */
    private String basePackage = "com.asc.aava";

    /** Default database schema name used for Hikari and Hibernate. */
    private String schema = "core";

    /** Enable SSL on the JDBC connection. */
    private String ssl = "false";

    /** SSL mode (e.g. require, verify-ca). Used when ssl=true. */
    private String sslmode = "require";

    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }

    public String getBasePackage() { return basePackage; }
    public void setBasePackage(String basePackage) { this.basePackage = basePackage; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getSsl() { return ssl; }
    public void setSsl(String ssl) { this.ssl = ssl; }

    public String getSslmode() { return sslmode; }
    public void setSslmode(String sslmode) { this.sslmode = sslmode; }
}
