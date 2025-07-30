package org.tanzu.vcentermcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.tanzu.vcentermcp.vcenter.VCenterService;

import java.util.List;

/**
 * Main Spring Boot application class for the vCenter MCP (Model Context Protocol) Server.
 * 
 * This application provides a Model Context Protocol server that connects to VMware vCenter
 * and exposes vCenter operations as tools that can be consumed by AI assistants and other
 * MCP clients. The server supports read-only operations for listing clusters, resource pools,
 * and virtual machines.
 * 
 * Key features:
 * - Connects to vCenter using vAPI (VMware API)
 * - Provides MCP tools for vCenter operations
 * - Supports Cloud Foundry deployment with service binding
 * - Uses Spring Boot configuration properties for flexible configuration
 * 
 * @author vCenter MCP Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableConfigurationProperties
public class VCenterMcpApplication {

    /**
     * Main application entry point.
     * 
     * This method initializes the Spring Boot application and sets up the MCP server
     * configuration. It programmatically sets various system properties to ensure
     * the MCP server is properly identified as "vcenter-mcp" with version "1.0.0".
     * 
     * The system properties are set to override any configuration that might be
     * present in application.properties or environment variables, ensuring consistent
     * MCP server identification.
     * 
     * @param args Command line arguments passed to the application
     */
    public static void main(String[] args) {
        // Set MCP server properties programmatically to ensure they are set
        System.setProperty("spring.application.name", "vcenter-mcp");
        System.setProperty("spring.ai.mcp.server.name", "vcenter-mcp");
        System.setProperty("spring.ai.mcp.server.version", "1.0.0");
        System.setProperty("mcp.server.name", "vcenter-mcp");
        System.setProperty("mcp.server.version", "1.0.0");
        System.setProperty("server.name", "vcenter-mcp");
        System.setProperty("server.version", "1.0.0");
        System.setProperty("MCP_SERVER_NAME", "vcenter-mcp");
        System.setProperty("MCP_SERVER_VERSION", "1.0.0");
        
        SpringApplication.run(VCenterMcpApplication.class, args);
    }

    /**
     * Registers the vCenter service tools with the MCP server.
     * 
     * This bean method creates a list of ToolCallback objects from the VCenterService,
     * which contains all the MCP tools for vCenter operations. The tools are automatically
     * discovered and registered by Spring AI's MCP server configuration.
     * 
     * The VCenterService contains tools for:
     * - Listing clusters in vCenter
     * - Listing resource pools within clusters
     * - Listing virtual machines in clusters or resource pools
     * - Listing all virtual machines across the entire vCenter
     * 
     * @param vCenterService The service containing vCenter operation tools
     * @return List of ToolCallback objects representing the available MCP tools
     */
    @Bean
    public List<ToolCallback> registerTools(VCenterService vCenterService) {
        return List.of(ToolCallbacks.from(vCenterService));
    }
}
