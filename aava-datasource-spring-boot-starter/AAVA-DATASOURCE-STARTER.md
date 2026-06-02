# AAVA Datasource Spring Boot Starter

**Artifact:** `com.asc.aava:aava-datasource-spring-boot-starter:1.0.0`
**Java:** 21 | **Spring Boot:** 3.5.x | **Database:** PostgreSQL

---

## Overview 

Every AAVA service that connects to a database needs a secure, production-grade connection.
That connection must work across three cloud environments — AWS, Azure, and GCP — and also
work on a developer's laptop with a simple password.

Previously, each service copy-pasted ~400 lines of datasource configuration code and maintained
it independently. Any security fix or improvement had to be replicated across every service.

This starter **centralises all datasource configuration into a single shared library**. A service
team adds one dependency to their `pom.xml`, sets two properties, and gets a fully configured,
cloud-aware PostgreSQL connection pool — with zero boilerplate.

### Benefits at a Glance

| Before | After |
|--------|-------|
| ~400 lines of datasource code per service | 2 lines in `pom.xml` + 2 properties |
| Security fixes applied per service manually | Fix once in the starter, all services benefit on next release |
| Hardcoded package names and schema per service | Fully configurable per service |
| Cloud SDK credentials tied to one service | Reusable across any AAVA service |
| No default — developers had to know all the config | Sensible defaults out of the box |

---

## How It Works (Developers)

The starter uses Spring Boot's auto-configuration mechanism. When you add the dependency,
Spring Boot automatically detects and wires the correct `DataSource` bean based on a single
property: `aava.datasource.auth-mode`.

```
aava.datasource.auth-mode = password    →  username/password (local dev, traditional DB)
aava.datasource.auth-mode = aws-iam     →  AWS RDS IAM authentication via STS
aava.datasource.auth-mode = azure-wif   →  Azure DB with Workload Identity Federation
aava.datasource.auth-mode = gcp-wif     →  Cloud SQL with GCP Application Default Credentials
```

Switching environments is a one-environment-variable change (`DATASOURCE_AUTH_MODE`). No code
change, no redeployment of config classes.

---

## Quick Start

### Step 1 — Add the dependency

```xml
<dependency>
    <groupId>com.asc.aava</groupId>
    <artifactId>aava-datasource-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Step 2 — Exclude Spring Boot's default datasource auto-configuration

In your main application class:

```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
@EnableTransactionManagement
public class MyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyServiceApplication.class, args);
    }
}
```

> **Why?** The starter provides its own `DataSource` and `EntityManagerFactory` beans.
> Excluding the defaults prevents Spring Boot from trying to create a second set of beans
> and failing due to missing `spring.datasource.url`.

### Step 3 — Add the two required properties

```properties
# Base package(s) containing your @Entity classes (comma-separated for multiple)
aava.datasource.base-package=com.example.myservice

# Auth mode — driven by environment variable, defaults to password
aava.datasource.auth-mode=${DATASOURCE_AUTH_MODE:password}
```

That's it for local development. The service will connect using a username and password.

---

## Authentication Modes

### `password` (Default)

Standard username/password authentication. Used for local development and traditional
PostgreSQL deployments.

**Required properties:**

```properties
datasource.url=jdbc:postgresql://localhost:5432/mydb
datasource.username=myuser
datasource.password=mypassword
```

**Optional — fetch password from a secrets manager instead:**

```properties
datasource.password.keyname=MY_DB_PASSWORD_SECRET_KEY
```

If `datasource.password.keyname` is set and a `SecretsManager` bean is present, the password
is fetched from the vault. The plain `datasource.password` is used as a fallback.

---

### `aws-iam`

AWS RDS IAM authentication. The service assumes an IAM role and generates a short-lived
authentication token instead of using a static password. No database password is stored anywhere.

**Required properties:**

```properties
datasource.url=jdbc:postgresql://<rds-host>:5432/mydb
datasource.username=myiamuser
aava.secrets.region=us-east-1
aava.secrets.assume-role-arn=arn:aws:iam::123456789012:role/my-rds-role
```

**How it works:**
1. On every new connection, the starter calls AWS STS to assume the configured IAM role.
2. AWS RDS Utilities generates a signed authentication token (valid 15 minutes).
3. That token is used as the JDBC password.
4. Hikari's `maxLifetime` is set to **14 minutes** to force connection refresh before the token expires.

**IAM policy required on the assumed role:**
```json
{
  "Effect": "Allow",
  "Action": "rds-db:connect",
  "Resource": "arn:aws:rds-db:us-east-1:123456789012:dbuser/*/myiamuser"
}
```

---

### `azure-wif`

Azure Database for PostgreSQL with Workload Identity Federation. Uses `DefaultAzureCredential`,
which resolves credentials automatically from the environment (Kubernetes WIF, Managed Identity,
or Azure CLI for local dev).

**Required properties:**

```properties
datasource.url=jdbc:postgresql://<azure-pg-host>:5432/mydb
datasource.username=my-aad-principal-name
```

**No password property is needed.** The starter fetches a short-lived OAuth2 token scoped to
`https://ossrdbms-aad.database.windows.net/.default` and uses it as the password.

**Token lifecycle:**
- Azure AD tokens are valid for 60–75 minutes.
- Hikari `maxLifetime` is set to **50 minutes** to refresh connections before expiry.

**Credential resolution order (DefaultAzureCredential):**

| Environment | Credential Used |
|-------------|-----------------|
| Kubernetes (GKE/AKS with WIF) | `AZURE_CLIENT_ID` + `AZURE_FEDERATED_TOKEN_FILE` + `AZURE_TENANT_ID` |
| Azure VM / AKS with Managed Identity | System/user-assigned Managed Identity |
| Local developer machine | Azure CLI (`az login`) |

**Azure Database for PostgreSQL prerequisites:**
- The AAD admin must be set on the server.
- The DB username must match the AAD principal display name or object-ID login.
- `azure_ad_admin` must be configured on the PostgreSQL server.

---

### `gcp-wif`

Cloud SQL (PostgreSQL) with GCP Workload Identity Federation. Uses `GoogleCredentials.getApplicationDefault()`,
which resolves credentials automatically from the environment.

**Required properties:**

```properties
datasource.url=jdbc:postgresql://<cloud-sql-ip>:5432/mydb
datasource.username=my-sa@my-project.iam
```

> **Note on username format:** For Cloud SQL IAM auth, the DB username is the service account
> email **without** the `.gserviceaccount.com` suffix.
> e.g. `my-sa@my-project.iam` for `my-sa@my-project.iam.gserviceaccount.com`

**Token lifecycle:**
- GCP OAuth2 tokens are valid for 60 minutes.
- Hikari `maxLifetime` is set to **50 minutes**.

**Credential resolution order:**

| Environment | Credential Used |
|-------------|-----------------|
| GKE with Workload Identity | GKE metadata server (automatic) |
| `GOOGLE_APPLICATION_CREDENTIALS` env var | WIF credential config JSON |
| Local developer machine | `gcloud auth application-default login` |

**Cloud SQL instance prerequisites:**
- Instance flag `cloudsql.iam_authentication = on` must be set.
- The service account must have `roles/cloudsql.instanceUser` on the instance.

---

## Full Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `aava.datasource.auth-mode` | `password` | Authentication mode: `password`, `aws-iam`, `azure-wif`, `gcp-wif` |
| `aava.datasource.base-package` | `com.asc.aava` | Comma-separated base package(s) for JPA `@Entity` scanning |
| `aava.datasource.schema` | `core` | Default DB schema (used by Hikari + Hibernate) |
| `aava.datasource.ssl` | `false` | Enable SSL on the JDBC connection (`password` mode only) |
| `aava.datasource.sslmode` | `require` | SSL mode: `require`, `verify-ca`, `verify-full` |
| `datasource.url` | _(required)_ | JDBC URL of the PostgreSQL instance |
| `datasource.username` | _(required)_ | Database username |
| `datasource.password` | _(empty)_ | Database password (`password` mode only) |
| `datasource.password.keyname` | _(empty)_ | Secret key name to fetch password from SecretsManager |
| `datasource.hikari.connection-timeout` | `30000` | Hikari: max ms to wait for a connection from the pool |
| `datasource.hikari.minimum-idle` | `2` | Hikari: minimum idle connections maintained in the pool |
| `datasource.hikari.maximum-pool-size` | `5` | Hikari: maximum number of connections in the pool |
| `datasource.hikari.idle-timeout` | `300000` | Hikari: max ms a connection can sit idle |
| `datasource.hikari.max-lifetime` | `1800000` | Hikari: max connection lifetime in ms (overridden for IAM/WIF modes) |
| `datasource.hikari.auto-commit` | `false` | Hikari: connection auto-commit behaviour |
| `datasource.hikari.schema` | _(inherits `aava.datasource.schema`)_ | Overrides schema at the Hikari level |
| `database-platform` | `org.hibernate.dialect.PostgreSQLDialect` | Hibernate dialect |
| `hibernate.ddl-auto` | `none` | Hibernate DDL strategy: `none`, `validate`, `update`, `create` |
| `show-sql` | `false` | Log all SQL statements |
| `properties.hibernate.format_sql` | `false` | Format logged SQL |
| `properties.hibernate.default_schema` | _(inherits schema)_ | Hibernate schema; overrides `aava.datasource.schema` at Hibernate level |
| `aava.secrets.region` | _(required for aws-iam)_ | AWS region for RDS and STS |
| `aava.secrets.assume-role-arn` | _(required for aws-iam)_ | IAM role ARN to assume for RDS token generation |
| `spring.application.name` | `aava-service` | Used as the Hikari pool name prefix |

---

## Schema and Package Scan Precedence

### Schema

The starter resolves schema in this order (first non-blank wins):

```
datasource.hikari.schema  →  aava.datasource.schema  →  "core" (hardcoded default)
```

`hibernate.default_schema` follows the same pattern independently, so if you need them to
differ (unusual), set each explicitly.

### Packages to Scan

```
aava.datasource.base-package=com.example.serviceA,com.example.shared
```

Multiple packages are comma-separated. All packages are scanned for classes annotated with
`@Entity`, `@MappedSuperclass`, or `@Embeddable`.

---

## Connection Pool (Hikari) Tuning

The starter ships with sensible defaults for most services. For high-throughput services,
tune the pool size:

```properties
# For a service handling heavy concurrent DB load
datasource.hikari.minimum-idle=5
datasource.hikari.maximum-pool-size=20
datasource.hikari.connection-timeout=20000
```

> **Rule of thumb:** `maximum-pool-size` = (number of CPU cores × 2) + effective disk spindle count.
> For most cloud DB instances, start with 5–10 and increase based on observed wait times.

### Token-based modes (aws-iam, azure-wif, gcp-wif)

In these modes the starter **overrides** `max-lifetime` regardless of what you configure:

| Mode | maxLifetime set by starter | Reason |
|------|---------------------------|--------|
| `aws-iam` | 14 minutes | IAM tokens expire in 15 min |
| `azure-wif` | 50 minutes | Azure AD tokens expire in 60–75 min |
| `gcp-wif` | 50 minutes | GCP tokens expire in 60 min |

---

## Migrating an Existing Service

If your service has inline `DatabaseConfig`, `DataSourceWithIAM`, `DataSourceWithAzureWIF`,
or `DataSourceWithGCPWIF` classes:

1. **Add** the starter dependency to `pom.xml`.
2. **Delete** the four inline datasource config classes.
3. **Add** `aava.datasource.base-package` to `application.properties`.
4. **Verify** your `@SpringBootApplication` excludes `DataSourceAutoConfiguration` and
   `HibernateJpaAutoConfiguration`.
5. If your service has an `ApplicationPropertiesLoader` that loaded generic DB config from
   `GenericConfigurationService`, remove that logic — the starter's `GenericPropertiesLoader`
   handles it. Keep only your service-specific secrets (e.g. Redis password, API keys).

**Checklist:**

```
[ ] aava-datasource-spring-boot-starter added to pom.xml
[ ] 4 inline datasource config classes deleted
[ ] aava.datasource.base-package set in application.properties
[ ] aava.datasource.schema set if your service uses a schema other than "core"
[ ] @SpringBootApplication excludes DataSourceAutoConfiguration + HibernateJpaAutoConfiguration
[ ] ApplicationPropertiesLoader pruned to service-specific secrets only (if applicable)
[ ] Build passes: mvn clean package
[ ] Service starts and connects to DB successfully
```

---

## GenericPropertiesLoader (AAVA Core Integration)

When `aava-core-java-starter` is on the classpath, the starter automatically activates
`GenericPropertiesLoader`. This bean:

1. Calls `GenericConfigurationService.getAllConfigurationsAsMap(MONITORING)` at startup.
2. Injects all returned key-value pairs as the highest-priority property source (`externalConfig`).
3. Optionally triggers `ContextRefresher` to rebind `@ConfigurationProperties` beans.

This means **any property returned by the App Config Service automatically overrides**
`application.properties` values — including datasource URL, username, schema, and pool settings.

Services that do **not** use `aava-core-java-starter` are unaffected; the bean simply doesn't activate.

---

## Frequently Asked Questions

**Q: Can I use this with a non-PostgreSQL database?**
No. The starter is PostgreSQL-specific. The driver, dialect, and SSL defaults are all
PostgreSQL-oriented. A separate starter would be needed for other databases.

**Q: Does the starter support multiple DataSources (e.g. primary + read-replica)?**
Not currently. The starter registers a single `@Primary` DataSource. Multi-DataSource
support would require the service to define additional non-primary beans manually.

**Q: What happens if I don't set `aava.datasource.base-package`?**
The default `com.asc.aava` will be used. For AAVA services this covers the standard package
hierarchy. Override it if your entities live in a different package.

**Q: Can I still override individual Hibernate properties?**
Yes. Any property under `hibernate.*` or `properties.hibernate.*` overrides the starter's
defaults because they are bound via `@Value` and used directly when building the
`LocalContainerEntityManagerFactoryBean`.

**Q: The starter says `@ConditionalOnMissingBean(DataSource.class)`. What does that mean?**
If your service defines its own `DataSource` bean for any reason, the starter steps aside
entirely. Your bean takes priority.

**Q: How do I test locally with `aws-iam` mode without real AWS credentials?**
Switch back to `password` mode locally:
```properties
# application-local.properties
aava.datasource.auth-mode=password
datasource.password=localpassword
```
Use the `local` Spring profile for local development.

---

## Version History

| Version | Changes |
|---------|---------|
| 1.0.0 | Initial release. Password, AWS IAM, Azure WIF, GCP WIF modes. Config-driven schema and base-package. GenericPropertiesLoader integration. |
