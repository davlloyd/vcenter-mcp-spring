package org.tanzu.vcentermcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration class for vCenter connection settings.
 * 
 * This class uses Spring Boot's @ConfigurationProperties to automatically bind
 * vCenter configuration from various sources including:
 * - application.properties file
 * - Environment variables
 * - Cloud Foundry service bindings (via VCenterConfigProcessor)
 * 
 * Configuration properties are bound using the "vcenter" prefix, so properties
 * like "vcenter.host", "vcenter.port", etc. are automatically mapped to this class.
 * 
 * Default values are provided for common settings, and the configuration can be
 * overridden by environment variables or Cloud Foundry service bindings.
 */
@Component
@ConfigurationProperties(prefix = "vcenter")
public class VCenterConfig {

    /** vCenter server hostname or IP address */
    private String host;
    
    /** vCenter server port (default: 443 for HTTPS) */
    private int port = 443;
    
    /** Username for vCenter authentication */
    private String username;
    
    /** Password for vCenter authentication */
    private String password;
    
    /** Whether to skip SSL certificate validation (default: true for development) */
    private boolean insecure = true;

    // Getters and setters
    /**
     * Gets the vCenter server hostname or IP address.
     * @return The hostname or IP address
     */
    public String getHost() { return host; }
    
    /**
     * Sets the vCenter server hostname or IP address.
     * @param host The hostname or IP address
     */
    public void setHost(String host) { this.host = host; }

    /**
     * Gets the vCenter server port.
     * @return The port number (default: 443)
     */
    public int getPort() { return port; }
    
    /**
     * Sets the vCenter server port.
     * @param port The port number
     */
    public void setPort(int port) { this.port = port; }

    /**
     * Gets the username for vCenter authentication.
     * @return The username
     */
    public String getUsername() { return username; }
    
    /**
     * Sets the username for vCenter authentication.
     * @param username The username
     */
    public void setUsername(String username) { this.username = username; }

    /**
     * Gets the password for vCenter authentication.
     * @return The password
     */
    public String getPassword() { return password; }
    
    /**
     * Sets the password for vCenter authentication.
     * @param password The password
     */
    public void setPassword(String password) { this.password = password; }

    /**
     * Checks if SSL certificate validation should be skipped.
     * @return true if SSL validation is disabled, false otherwise
     */
    public boolean isInsecure() { return insecure; }
    
    /**
     * Sets whether SSL certificate validation should be skipped.
     * @param insecure true to disable SSL validation, false to enable it
     */
    public void setInsecure(boolean insecure) { this.insecure = insecure; }

    /**
     * Sets the insecure flag from a string value.
     * 
     * This method is used to handle string-to-boolean conversion for environment
     * variables, which are always strings. It parses the string value to determine
     * the boolean setting.
     * 
     * @param insecure String representation of the insecure setting ("true"/"false")
     */
    public void setInsecure(String insecure) {
        this.insecure = Boolean.parseBoolean(insecure);
    }

    /**
     * Returns a string representation of the configuration.
     * 
     * The password is hidden for security reasons to prevent it from appearing
     * in logs or debug output.
     * 
     * @return String representation with password hidden
     */
    @Override
    public String toString() {
        return "VCenterConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                ", password='[HIDDEN]'" +
                ", insecure=" + insecure +
                '}';
    }
} 