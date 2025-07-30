package org.tanzu.vcentermcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Processor for handling Cloud Foundry service bindings and vCenter configuration.
 * 
 * This component is responsible for processing the VCAP_SERVICES environment variable
 * that is automatically set by Cloud Foundry when services are bound to the application.
 * It ensures that vCenter credentials from service bindings take precedence over
 * environment variables or application.properties when the configuration is incomplete.
 * 
 * The processor runs automatically after the Spring context is initialized using
 * the @PostConstruct annotation. It checks if the current vCenter configuration
 * is complete, and if not, it looks for vCenter service credentials in VCAP_SERVICES
 * and updates the VCenterConfig accordingly.
 * 
 * Configuration priority (highest to lowest):
 * 1. Cloud Foundry service binding (VCAP_SERVICES)
 * 2. Environment variables
 * 3. application.properties
 * 4. Default values
 */
@Component
public class VCenterConfigProcessor {

    private static final Logger logger = LoggerFactory.getLogger(VCenterConfigProcessor.class);

    /** The vCenter configuration object to be updated */
    @Autowired
    private VCenterConfig vCenterConfig;

    /** Spring environment for accessing environment variables */
    @Autowired
    private Environment environment;

    /**
     * Processes VCAP_SERVICES to configure vCenter connection settings.
     * 
     * This method is automatically called after the Spring context is initialized.
     * It checks if the current vCenter configuration is complete (host, username,
     * and password are all set and valid). If the configuration is incomplete,
     * it parses the VCAP_SERVICES environment variable to find vCenter service
     * credentials and updates the VCenterConfig object.
     * 
     * The method handles various scenarios:
     * - Configuration already complete from environment variables
     * - VCAP_SERVICES not available
     * - vCenter service not found in VCAP_SERVICES
     * - JSON parsing errors
     * 
     * Logging is provided at various levels to help with debugging configuration issues.
     */
    @PostConstruct
    public void processVCapServices() {
        logger.info("Processing vCenter configuration...");
        
        // Log current configuration state
        logger.info("Current config - Host: '{}', Username: '{}', Password: '{}'", 
                   vCenterConfig.getHost(), 
                   vCenterConfig.getUsername(), 
                   vCenterConfig.getPassword() != null ? "***" : "null");
        
        // Check if we need to process VCAP_SERVICES
        if (isConfigurationComplete()) {
            logger.info("vCenter configuration is complete from environment variables");
            return;
        }

        String vcapServices = environment.getProperty("VCAP_SERVICES");
        logger.info("VCAP_SERVICES available: {}", vcapServices != null && !vcapServices.isEmpty());
        
        if (vcapServices == null || vcapServices.isEmpty()) {
            logger.warn("VCAP_SERVICES not available and configuration incomplete");
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode vcapServicesNode = mapper.readTree(vcapServices);
            
            // Find vCenter service in VCAP_SERVICES
            JsonNode credentials = findVCenterCredentials(vcapServicesNode);
            if (credentials != null) {
                updateConfigurationFromVCap(credentials);
                logger.info("vCenter configuration updated from VCAP_SERVICES");
                logger.info("Final config - Host: '{}', Username: '{}', Password: '{}'", 
                           vCenterConfig.getHost(), 
                           vCenterConfig.getUsername(), 
                           vCenterConfig.getPassword() != null ? "***" : "null");
            } else {
                logger.warn("No vCenter service found in VCAP_SERVICES");
            }
        } catch (Exception e) {
            logger.error("Error processing VCAP_SERVICES: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if the current vCenter configuration is complete and valid.
     * 
     * A configuration is considered complete if all required fields (host, username,
     * and password) are present and valid. A field is considered valid if it:
     * - Is not null
     * - Is not empty or whitespace-only
     * - Does not contain placeholder values (${...})
     * 
     * @return true if the configuration is complete, false otherwise
     */
    private boolean isConfigurationComplete() {
        String host = vCenterConfig.getHost();
        String username = vCenterConfig.getUsername();
        String password = vCenterConfig.getPassword();
        
        // Check for null, empty, or placeholder values
        boolean hostValid = host != null && !host.trim().isEmpty() && !host.contains("${");
        boolean usernameValid = username != null && !username.trim().isEmpty() && !username.contains("${");
        boolean passwordValid = password != null && !password.trim().isEmpty() && !password.contains("${");
        
        logger.debug("Configuration validation - Host valid: {}, Username valid: {}, Password valid: {}", 
                    hostValid, usernameValid, passwordValid);
        
        return hostValid && usernameValid && passwordValid;
    }

    /**
     * Finds vCenter service credentials in the VCAP_SERVICES JSON structure.
     * 
     * This method iterates through all services in VCAP_SERVICES looking for
     * a service whose name contains "vcenter" (case-insensitive). When found,
     * it returns the credentials object for that service.
     * 
     * @param vcapServicesNode The parsed VCAP_SERVICES JSON node
     * @return The credentials JsonNode if a vCenter service is found, null otherwise
     */
    private JsonNode findVCenterCredentials(JsonNode vcapServicesNode) {
        for (JsonNode serviceNode : vcapServicesNode) {
            for (JsonNode service : serviceNode) {
                String serviceName = service.path("name").asText();
                logger.debug("Found service: {}", serviceName);
                if (serviceName.toLowerCase().contains("vcenter")) {
                    logger.info("Found vCenter service: {}", serviceName);
                    return service.path("credentials");
                }
            }
        }
        return null;
    }

    /**
     * Updates the VCenterConfig with credentials from VCAP_SERVICES.
     * 
     * This method updates the configuration only if the current values are null,
     * empty, or contain placeholder values. This ensures that VCAP_SERVICES
     * credentials take precedence when environment variables are not fully set,
     * but don't override valid existing configuration.
     * 
     * The method handles all vCenter configuration properties:
     * - host: vCenter server hostname/IP
     * - username: Authentication username
     * - password: Authentication password
     * - port: Server port (only if still at default 443)
     * - insecure: SSL validation setting (always updated if present)
     * 
     * @param credentials The credentials JsonNode from VCAP_SERVICES
     */
    private void updateConfigurationFromVCap(JsonNode credentials) {
        // Always update if current value is null, empty, or placeholder
        String currentHost = vCenterConfig.getHost();
        if (currentHost == null || currentHost.trim().isEmpty() || currentHost.contains("${")) {
            String host = credentials.path("host").asText();
            vCenterConfig.setHost(host);
            logger.info("Set host from VCAP: {}", host);
        }

        String currentUsername = vCenterConfig.getUsername();
        if (currentUsername == null || currentUsername.trim().isEmpty() || currentUsername.contains("${")) {
            String username = credentials.path("username").asText();
            vCenterConfig.setUsername(username);
            logger.info("Set username from VCAP: {}", username);
        }

        String currentPassword = vCenterConfig.getPassword();
        if (currentPassword == null || currentPassword.trim().isEmpty() || currentPassword.contains("${")) {
            String password = credentials.path("password").asText();
            vCenterConfig.setPassword(password);
            logger.info("Set password from VCAP: ***");
        }

        // Update port if it's still default
        if (vCenterConfig.getPort() == 443) {
            int port = credentials.path("port").asInt(443);
            vCenterConfig.setPort(port);
            logger.info("Set port from VCAP: {}", port);
        }

        // Always set insecure from VCAP if available
        if (credentials.has("insecure")) {
            boolean insecure = credentials.path("insecure").asBoolean(true);
            vCenterConfig.setInsecure(insecure);
            logger.info("Set insecure from VCAP: {}", insecure);
        }
    }
} 