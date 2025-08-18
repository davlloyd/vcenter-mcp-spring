package org.tanzu.vcentermcp.vcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.tanzu.vcentermcp.config.VCenterConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for communicating with VMware vCenter using the vAPI (VMware API) protocol.
 * 
 * This client implements a VMware SDK-style interface for interacting with vCenter's
 * vAPI endpoints. It provides a fluent API that mimics the structure of the official
 * VMware Java SDK, making it familiar for developers who have worked with VMware APIs.
 * 
 * The client handles:
 * - Session management and authentication with vCenter
 * - HTTP communication using Spring WebClient
 * - JSON request/response processing
 * - vAPI protocol compliance for vCenter 8.0
 * - Multiple authentication methods for compatibility
 * 
 * The client is organized into service interfaces that follow the VMware SDK pattern:
 * - ClusterService: For cluster operations
 * - ResourcePoolService: For resource pool operations  
 * - VmService: For virtual machine operations
 * 
 * All operations are read-only and support the MCP server's requirements for
 * listing clusters, resource pools, and virtual machines.
 */
@Component
public class VapiClient {

    private static final Logger logger = LoggerFactory.getLogger(VapiClient.class);
    
    /** vCenter configuration containing connection settings */
    private final VCenterConfig vCenterConfig;
    
    /** WebClient for HTTP communication with vCenter */
    private final WebClient webClient;
    
    /** Jackson ObjectMapper for JSON processing */
    private final ObjectMapper objectMapper;
    
    /** Thread-safe cache for vAPI session tokens */
    private final ConcurrentHashMap<String, String> sessionTokens = new ConcurrentHashMap<>();
    
    // vAPI service interfaces (following VMware SDK pattern)
    /** Service interface for cluster operations */
    private final ClusterService clusterService;
    
    /** Service interface for resource pool operations */
    private final ResourcePoolService resourcePoolService;
    
    /** Service interface for virtual machine operations */
    private final VmService vmService;
    
    /**
     * Constructs a new VapiClient with the specified configuration.
     * 
     * This constructor initializes the client with vCenter connection settings
     * and creates the WebClient for HTTP communication. It also initializes
     * the service interfaces that provide the VMware SDK-style API.
     * 
     * The client is configured to use HTTPS with the specified host and port,
     * and includes default JSON headers for API communication.
     * 
     * @param vCenterConfig Configuration containing vCenter connection settings
     * @param webClientBuilder Pre-configured WebClient.Builder with SSL settings
     */
    public VapiClient(VCenterConfig vCenterConfig, WebClient.Builder webClientBuilder) {
        this.vCenterConfig = vCenterConfig;
        this.objectMapper = new ObjectMapper();
        
        String baseUrl = "https://" + vCenterConfig.getHost() + ":" + vCenterConfig.getPort();
        logger.info("Initializing VapiClient for vCenter: {} (insecure={})", baseUrl, vCenterConfig.isInsecure());
        
        this.webClient = webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
            
        // Initialize vAPI service interfaces
        this.clusterService = new ClusterService();
        this.resourcePoolService = new ResourcePoolService();
        this.vmService = new VmService();
    }
    
    /**
     * Returns the cluster service interface.
     * 
     * This method provides access to cluster-related operations following
     * the VMware SDK pattern. The returned service can be used to list
     * clusters in the vCenter.
     * 
     * @return ClusterService instance for cluster operations
     */
    public ClusterService clusters() {
        return clusterService;
    }
    
    /**
     * Returns the resource pool service interface.
     * 
     * This method provides access to resource pool-related operations following
     * the VMware SDK pattern. The returned service can be used to list
     * resource pools within clusters.
     * 
     * @return ResourcePoolService instance for resource pool operations
     */
    public ResourcePoolService resourcePools() {
        return resourcePoolService;
    }
    
    /**
     * Returns the virtual machine service interface.
     * 
     * This method provides access to virtual machine-related operations following
     * the VMware SDK pattern. The returned service can be used to list
     * virtual machines in clusters or resource pools.
     * 
     * @return VmService instance for virtual machine operations
     */
    public VmService vms() {
        return vmService;
    }
    
    /**
     * Service interface for cluster operations.
     * 
     * This inner class provides methods for interacting with vCenter clusters,
     * following the VMware SDK pattern. Currently supports listing all clusters.
     */
    public class ClusterService {
        /**
         * Lists all clusters in the vCenter.
         * 
         * This method retrieves a list of all clusters available in the vCenter.
         * The response includes cluster IDs, names, and configuration details.
         * 
         * @return JsonNode containing the list of clusters
         */
        public JsonNode list() {
            logger.info("=== VAPI CLUSTER SERVICE: list() ===");
            return invokeVapiMethod("com.vmware.vcenter.Cluster", "list", null);
        }
    }
    
    /**
     * Service interface for resource pool operations.
     * 
     * This inner class provides methods for interacting with vCenter resource pools,
     * following the VMware SDK pattern. Supports listing resource pools within
     * a specific cluster.
     */
    public class ResourcePoolService {
        /**
         * Lists resource pools within a specific cluster.
         * 
         * This method retrieves a list of resource pools that belong to the
         * specified cluster. The cluster ID is used to filter the results.
         * 
         * @param clusterId The ID of the cluster to list resource pools for
         * @return JsonNode containing the list of resource pools
         */
        public JsonNode list(String clusterId) {
            logger.info("=== VAPI RESOURCE POOL SERVICE: list({}) ===", clusterId);
            ObjectNode input = objectMapper.createObjectNode();
            input.put("cluster", clusterId);
            return invokeVapiMethod("com.vmware.vcenter.ResourcePool", "list", input);
        }
    }
    
    /**
     * Service interface for virtual machine operations.
     * 
     * This inner class provides methods for interacting with vCenter virtual machines,
     * following the VMware SDK pattern. Supports listing VMs with optional filtering
     * by cluster or resource pool.
     */
    public class VmService {
        /**
         * Lists virtual machines with optional filtering.
         * 
         * This method retrieves a list of virtual machines. The results can be
         * filtered by cluster ID, resource pool ID, or both. If both parameters
         * are null, all VMs in the vCenter are returned.
         * 
         * @param clusterId Optional cluster ID to filter VMs by cluster
         * @param resourcePoolId Optional resource pool ID to filter VMs by resource pool
         * @return JsonNode containing the list of virtual machines
         */
        public JsonNode list(String clusterId, String resourcePoolId) {
            logger.info("=== VAPI VM SERVICE: list(cluster={}, resourcePool={}) ===", clusterId, resourcePoolId);
            ObjectNode input = objectMapper.createObjectNode();
            if (clusterId != null) {
                input.put("cluster", clusterId);
            }
            if (resourcePoolId != null) {
                input.put("resource_pool", resourcePoolId);
            }
            return invokeVapiMethod("com.vmware.vcenter.VM", "list", input);
        }
    }
    
    /**
     * Invokes a vAPI method on the vCenter server.
     * 
     * This is the core method that handles all vAPI communication with vCenter.
     * It manages session authentication, HTTP request/response handling, and
     * JSON processing. The method supports both GET and POST operations based
     * on the operation type and input parameters.
     * 
     * For list operations, it uses GET requests with query parameters for filtering.
     * For other operations, it uses POST requests with JSON request bodies.
     * 
     * The method includes comprehensive logging for debugging and handles various
     * response formats that vCenter might return.
     * 
     * @param service The vAPI service name (e.g., "com.vmware.vcenter.Cluster")
     * @param operation The operation to perform (e.g., "list")
     * @param input Optional input parameters for the operation
     * @return JsonNode containing the response data
     * @throws RuntimeException if the vAPI call fails or returns an error
     */
    private JsonNode invokeVapiMethod(String service, String operation, ObjectNode input) {
        try {
            logger.info("=== VAPI METHOD INVOKED: {}.{} ===", service, operation);
            
            // Map vAPI service to the correct vCenter vAPI endpoint
            String endpoint = mapVapiServiceToEndpoint(service);
            logger.info("vAPI endpoint: {}", endpoint);
            
            String response;
            String requestBody = null;
            String finalEndpoint = endpoint;
            if ("list".equals(operation)) {
                // Use GET for all list operations in vCenter 8.0
                if (input != null && !input.isEmpty()) {
                    // Add query parameters for filtering
                    StringBuilder queryParams = new StringBuilder();
                    if (input.has("cluster")) {
                        queryParams.append("clusters=").append(input.get("cluster").asText());
                    }
                    if (input.has("resource_pool")) {
                        if (queryParams.length() > 0) queryParams.append("&");
                        queryParams.append("resource_pools=").append(input.get("resource_pool").asText());
                    }
                    if (queryParams.length() > 0) {
                        finalEndpoint = endpoint + "?" + queryParams.toString();
                    }
                }
                
                            String sessionToken = getValidSessionToken();
            
            response = webClient.mutate()
                .defaultHeader("vmware-api-session-id", sessionToken)
                .build()
                .get()
                .uri(finalEndpoint)
                .retrieve()
                .bodyToMono(String.class)
                .block();
                logger.info("vAPI GET request to {}", finalEndpoint);
            } else {
                // Use POST for other operations
                // Create vAPI request structure
                ObjectNode request = objectMapper.createObjectNode();
                if (input != null) {
                    request.setAll(input);
                }
                requestBody = objectMapper.writeValueAsString(request);
                logger.info("vAPI request body: {}", requestBody);
                String sessionToken = getValidSessionToken();
            
            response = webClient.mutate()
                .defaultHeader("vmware-api-session-id", sessionToken)
                .build()
                .post()
                .uri(endpoint)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            }
            
            logger.info("vAPI raw response: {}", response);
            
            JsonNode responseNode = objectMapper.readTree(response);
            logger.info("vAPI response structure: {}", responseNode.toString());
            logger.info("vAPI response fields: {}", responseNode.fieldNames());
            
            // Check for vAPI errors
            if (responseNode.has("error")) {
                JsonNode error = responseNode.get("error");
                String errorMessage = error.path("message").asText("Unknown vAPI error");
                logger.error("vAPI error: {}", errorMessage);
                throw new RuntimeException("vAPI error: " + errorMessage);
            }
            
            // Handle different response formats
            JsonNode result;
            if (responseNode.has("result")) {
                // Standard vAPI response with "result" field
                result = responseNode.get("result");
                logger.info("vAPI result from 'result' field: {}", result.toString());
            } else if (responseNode.isArray()) {
                // Direct array response
                result = responseNode;
                logger.info("vAPI result as direct array: {}", result.toString());
            } else {
                // Try to use the response as-is
                result = responseNode;
                logger.info("vAPI result as direct response: {}", result.toString());
            }
            
            return result;
        } catch (Exception e) {
            // Check if this is a 401 Unauthorized error, which indicates session token is invalid
            if (e.getMessage() != null && e.getMessage().contains("401 Unauthorized")) {
                logger.warn("Received 401 Unauthorized, clearing cached session token and retrying once");
                sessionTokens.remove("session"); // Clear the invalid token
                
                // Retry the entire method call with fresh session
                return invokeVapiMethodWithFreshSession(service, operation, input);
            }
            
            logger.error("Failed to invoke vAPI method {}.{}: {}", service, operation, e.getMessage(), e);
            throw new RuntimeException("vAPI method invocation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Attempts to create a vAPI session using multiple authentication methods.
     * 
     * This method tries three different authentication approaches to establish
     * a session with vCenter, providing compatibility with different vCenter
     * configurations and versions. The methods are tried in order until one
     * succeeds.
     * 
     * Method 1: Standard vAPI session with JSON body
     * - POST to /api/session with username/password in JSON body
     * - Most common method for vCenter 8.0
     * 
     * Method 2: Basic Authentication
     * - POST to /api/session with Authorization header
     * - Handles both JSON and plain string token responses
     * - Fallback for some vCenter configurations
     * 
     * Method 3: Alternative vAPI session endpoint
     * - POST to /rest/com/vmware/cis/session
     * - Legacy endpoint for older vCenter versions
     * 
     * @return Session token if authentication succeeds, null if all methods fail
     */
    private String tryCreateSessionWithMultipleMethods() {
        // Method 1: Standard vAPI session with JSON body
        try {
            logger.info("Trying Method 1: Standard vAPI session with JSON body");
            ObjectNode sessionRequest = objectMapper.createObjectNode();
            sessionRequest.put("username", vCenterConfig.getUsername());
            sessionRequest.put("password", vCenterConfig.getPassword());
            
            String sessionRequestBody = objectMapper.writeValueAsString(sessionRequest);
            logger.info("vAPI session request: {}", sessionRequestBody.replace(vCenterConfig.getPassword(), "***"));
            
            String sessionResponse = webClient.post()
                .uri("/api/session")
                .bodyValue(sessionRequestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            
            logger.info("vAPI session response: {}", sessionResponse);
            
            JsonNode responseNode = objectMapper.readTree(sessionResponse);
            String token = responseNode.get("value").asText();
            
            logger.info("Successfully obtained vAPI session token (Method 1)");
            return token;
            
        } catch (Exception e) {
            logger.warn("Method 1 failed: {}", e.getMessage());
        }
        
        // Method 2: Basic Authentication
        try {
            logger.info("Trying Method 2: Basic Authentication");
            String credentials = vCenterConfig.getUsername() + ":" + vCenterConfig.getPassword();
            String encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
            
            String basicAuthResponse = webClient.post()
                .uri("/api/session")
                .header("Authorization", "Basic " + encodedCredentials)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            
            logger.info("Basic auth session response: {}", basicAuthResponse);
            
            // Handle both JSON response and plain string token
            String basicAuthToken;
            if (basicAuthResponse.trim().startsWith("{")) {
                // JSON response with "value" field
                JsonNode basicAuthResponseNode = objectMapper.readTree(basicAuthResponse);
                basicAuthToken = basicAuthResponseNode.get("value").asText();
            } else {
                // Plain string token (remove quotes if present)
                basicAuthToken = basicAuthResponse.trim().replaceAll("^\"|\"$", "");
            }
            
            logger.info("Successfully obtained vAPI session token via Basic Authentication (Method 2)");
            return basicAuthToken;
            
        } catch (Exception e) {
            logger.warn("Method 2 failed: {}", e.getMessage());
        }
        
        // Method 3: Alternative vAPI session endpoint
        try {
            logger.info("Trying Method 3: Alternative vAPI session endpoint");
            ObjectNode sessionRequest = objectMapper.createObjectNode();
            sessionRequest.put("username", vCenterConfig.getUsername());
            sessionRequest.put("password", vCenterConfig.getPassword());
            
            String sessionRequestBody = objectMapper.writeValueAsString(sessionRequest);
            logger.info("Alternative vAPI session request: {}", sessionRequestBody.replace(vCenterConfig.getPassword(), "***"));
            
            String sessionResponse = webClient.post()
                .uri("/rest/com/vmware/cis/session")
                .bodyValue(sessionRequestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            
            logger.info("Alternative vAPI session response: {}", sessionResponse);
            
            JsonNode responseNode = objectMapper.readTree(sessionResponse);
            String token = responseNode.get("value").asText();
            
            logger.info("Successfully obtained vAPI session token (Method 3)");
            return token;
            
        } catch (Exception e) {
            logger.warn("Method 3 failed: {}", e.getMessage());
        }
        
        logger.error("All authentication methods failed. Cannot establish vAPI session with vCenter.");
        throw new RuntimeException("Failed to authenticate with vCenter vAPI: All authentication methods failed");
    }
    
    /**
     * Gets a valid session token, refreshing it if necessary.
     * 
     * This method checks if a session token exists and is valid. If the token
     * is missing or invalid (causing 401 errors), it will clear the cached token
     * and create a new session.
     * 
     * @return A valid session token
     */
    private String getValidSessionToken() {
        String sessionToken = sessionTokens.get("session");
        if (sessionToken == null) {
            logger.info("No cached session token found, creating new session");
            sessionToken = tryCreateSessionWithMultipleMethods();
            if (sessionToken != null) {
                sessionTokens.put("session", sessionToken);
            }
        }
        return sessionToken;
    }
    
    /**
     * Invokes a vAPI method with a fresh session token after a 401 error.
     * 
     * This method is called when the original vAPI call fails with a 401 Unauthorized
     * error, indicating that the session token has expired. It creates a fresh session
     * and retries the operation once.
     * 
     * @param service The vAPI service name
     * @param operation The vAPI operation name
     * @param input The input parameters for the operation
     * @return The response from the vAPI call
     * @throws RuntimeException if the retry also fails
     */
    private JsonNode invokeVapiMethodWithFreshSession(String service, String operation, ObjectNode input) {
        try {
            logger.info("Retrying vAPI method {}.{} with fresh session token", service, operation);
            
            // Get a fresh session token
            String freshSessionToken = getValidSessionToken();
            
            // Map vAPI service to the correct vCenter vAPI endpoint
            String endpoint = mapVapiServiceToEndpoint(service);
            logger.info("vAPI retry endpoint: {}", endpoint);
            
            String response;
            if ("list".equals(operation)) {
                // Use GET for all list operations in vCenter 8.0
                String finalEndpoint = endpoint;
                if (input != null && !input.isEmpty()) {
                    // Add query parameters for filtering
                    StringBuilder queryParams = new StringBuilder();
                    if (input.has("cluster")) {
                        queryParams.append("clusters=").append(input.get("cluster").asText());
                    }
                    if (input.has("resource_pool")) {
                        if (queryParams.length() > 0) queryParams.append("&");
                        queryParams.append("resource_pools=").append(input.get("resource_pool").asText());
                    }
                    if (queryParams.length() > 0) {
                        finalEndpoint = endpoint + "?" + queryParams.toString();
                    }
                }
                
                response = webClient.mutate()
                    .defaultHeader("vmware-api-session-id", freshSessionToken)
                    .build()
                    .get()
                    .uri(finalEndpoint)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
                logger.info("vAPI retry GET request to {}", finalEndpoint);
            } else {
                // Use POST for other operations
                // Create vAPI request structure
                ObjectNode request = objectMapper.createObjectNode();
                if (input != null) {
                    request.setAll(input);
                }
                String requestBody = objectMapper.writeValueAsString(request);
                logger.info("vAPI retry request body: {}", requestBody);
                
                response = webClient.mutate()
                    .defaultHeader("vmware-api-session-id", freshSessionToken)
                    .build()
                    .post()
                    .uri(endpoint)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            }
            
            logger.info("vAPI retry successful with fresh session token");
            
            // Process the response as before
            JsonNode responseNode = objectMapper.readTree(response);
            logger.info("vAPI retry response structure: {}", responseNode.toString());
            logger.info("vAPI retry response fields: {}", responseNode.fieldNames());
            
            // Check for vAPI errors
            if (responseNode.has("error")) {
                JsonNode error = responseNode.get("error");
                String errorMessage = error.path("message").asText("Unknown vAPI error");
                logger.error("vAPI retry error: {}", errorMessage);
                throw new RuntimeException("vAPI error: " + errorMessage);
            }
            
            // Handle different response formats
            JsonNode result;
            if (responseNode.has("result")) {
                // Standard vAPI response with "result" field
                result = responseNode.get("result");
                logger.info("vAPI retry result from 'result' field: {}", result.toString());
            } else if (responseNode.isArray()) {
                // Direct array response
                result = responseNode;
                logger.info("vAPI retry result as direct array: {}", result.toString());
            } else {
                // Try to use the response as-is
                result = responseNode;
                logger.info("vAPI retry result as direct response: {}", result.toString());
            }
            
            return result;
            
        } catch (Exception retryException) {
            logger.error("Retry with fresh session token also failed: {}", retryException.getMessage(), retryException);
            throw new RuntimeException("vAPI method invocation failed after session refresh: " + retryException.getMessage(), retryException);
        }
    }
    
    /**
     * Maps vAPI service names to their corresponding vCenter API endpoints.
     * 
     * This method converts the VMware SDK-style service names to the actual
     * HTTP endpoints used by vCenter's vAPI. The mapping follows the vCenter 8.0
     * vAPI structure where services are exposed as REST endpoints.
     * 
     * @param service The vAPI service name (e.g., "com.vmware.vcenter.Cluster")
     * @return The corresponding HTTP endpoint path
     * @throws RuntimeException if the service name is not recognized
     */
    private String mapVapiServiceToEndpoint(String service) {
        switch (service) {
            case "com.vmware.vcenter.Cluster":
                return "/api/vcenter/cluster";
            case "com.vmware.vcenter.ResourcePool":
                return "/api/vcenter/resource-pool";
            case "com.vmware.vcenter.VM":
                return "/api/vcenter/vm";
            default:
                throw new RuntimeException("Unknown vAPI service: " + service);
        }
    }
} 