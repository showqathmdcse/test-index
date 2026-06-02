package com.asc.aava.datasource.loader;

import com.asc.aava.config.management.ConfigurationCategory;
import com.asc.aava.config.management.GenericConfigurationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic bootstrap: loads all key-value pairs from {@link GenericConfigurationService}
 * into Spring's Environment as the highest-priority property source ("externalConfig").
 *
 * This bean activates only when a {@link GenericConfigurationService} bean is present
 * (i.e. the aava-core-java-starter is on the classpath and configured).
 *
 * Service-specific secrets (e.g. Redis passwords, LLM keys) should be loaded by the
 * consuming service's own ApplicationPropertiesLoader that extends or complements this.
 *
 * All four DataSource configs depend on this bean via @DependsOn("genericPropertiesLoader")
 * so that external DB properties are available when HikariCP initialises.
 */
@Configuration
@ConditionalOnBean(GenericConfigurationService.class)
public class GenericPropertiesLoader {

    private static final Logger logger = LoggerFactory.getLogger(GenericPropertiesLoader.class);

    @Autowired
    private GenericConfigurationService configurationService;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired(required = false)
    private org.springframework.cloud.context.refresh.ContextRefresher contextRefresher;

    @PostConstruct
    public void loadExternalConfiguration() {
        try {
            MutablePropertySources sources = environment.getPropertySources();
            if (sources.get("externalConfig") != null) {
                logger.info("PropertySource 'externalConfig' already present; skipping bootstrap");
                return;
            }

            Map<String, String> allConfigs = configurationService.getAllConfigurationsAsMap(ConfigurationCategory.MONITORING);

            if (allConfigs == null || allConfigs.isEmpty()) {
                logger.warn("GenericConfigurationService returned empty configuration map");
                return;
            }

            Map<String, Object> props = new HashMap<>(allConfigs);
            sources.addFirst(new MapPropertySource("externalConfig", props));
            logger.info("Loaded {} external configuration properties from GenericConfigurationService", props.size());

            if (contextRefresher != null) {
                try {
                    contextRefresher.refresh();
                    logger.info("ContextRefresher triggered after external config load");
                } catch (Exception e) {
                    logger.warn("ContextRefresher.refresh() failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load external configuration from GenericConfigurationService", e);
        }
    }
}
