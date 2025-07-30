package org.tanzu.vcentermcp.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

/**
 * Configuration class for WebClient used to communicate with vCenter vAPI.
 * 
 * This configuration class provides a customized WebClient.Builder that is configured
 * specifically for vCenter API communication. The main customization is SSL/TLS
 * handling, which can be configured to either validate certificates (secure) or
 * skip validation (insecure) based on the vCenter configuration.
 * 
 * The WebClient is used by the VapiClient to make HTTP requests to vCenter's vAPI
 * endpoints. It supports both secure and insecure connections, with appropriate
 * logging and error handling for each scenario.
 * 
 * Key features:
 * - Configurable SSL certificate validation
 * - Default JSON content type headers
 * - Comprehensive logging for debugging
 * - Error handling for SSL configuration issues
 */
@Configuration
public class WebClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);

    /**
     * Creates and configures a WebClient.Builder for vCenter API communication.
     * 
     * This bean method creates a WebClient.Builder that is specifically configured
     * for communicating with vCenter's vAPI endpoints. The configuration includes:
     * 
     * - Default JSON content type headers for API communication
     * - SSL/TLS configuration based on the vCenter settings
     * - Insecure SSL context when certificate validation is disabled
     * - Comprehensive logging for debugging connection issues
     * 
     * When the vCenter configuration has insecure=true, this method creates an
     * SSL context that trusts all certificates using InsecureTrustManagerFactory.
     * This is useful for development environments or when dealing with self-signed
     * certificates, but should not be used in production.
     * 
     * The method includes extensive error handling and logging to help diagnose
     * SSL configuration issues that might prevent successful vCenter connections.
     * 
     * @param vCenterConfig The vCenter configuration containing SSL settings
     * @return A configured WebClient.Builder ready for vCenter API communication
     * @throws RuntimeException if SSL context configuration fails
     */
    @Bean
    public WebClient.Builder webClientBuilder(VCenterConfig vCenterConfig) {
        logger.info("Configuring WebClient.Builder for vCenter: {}:{} (insecure={})", 
                   vCenterConfig.getHost(), vCenterConfig.getPort(), vCenterConfig.isInsecure());

        // Create base WebClient.Builder with JSON headers
        WebClient.Builder builder = WebClient.builder()
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json");

        if (vCenterConfig.isInsecure()) {
            try {
                logger.warn("SSL validation is DISABLED for vCenter connection (insecure=true). This is not recommended for production!");
                
                // Create an SSL context that trusts all certificates
                SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
                
                logger.debug("Created SSL context with InsecureTrustManagerFactory");
                
                // Create HttpClient with the insecure SSL context
                HttpClient httpClient = HttpClient.create()
                    .secure(spec -> spec.sslContext(sslContext));
                
                logger.debug("Created HttpClient with insecure SSL context");
                
                // Use the custom HttpClient
                builder.clientConnector(new ReactorClientHttpConnector(httpClient));
                
                logger.info("Successfully configured insecure SSL context for vCenter connection");
            } catch (SSLException e) {
                logger.error("Failed to configure insecure SSL context: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to configure insecure SSL context", e);
            } catch (Exception e) {
                logger.error("Unexpected error configuring insecure SSL context: {}", e.getMessage(), e);
                throw new RuntimeException("Unexpected error configuring insecure SSL context", e);
            }
        } else {
            logger.info("Using default SSL validation for vCenter connection");
        }

        return builder;
    }
} 