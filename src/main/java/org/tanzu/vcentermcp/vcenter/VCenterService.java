package org.tanzu.vcentermcp.vcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service class that provides MCP (Model Context Protocol) tools for vCenter operations.
 * 
 * This service acts as the bridge between the MCP server and the vCenter vAPI client.
 * It exposes vCenter operations as MCP tools that can be consumed by AI assistants
 * and other MCP clients. All operations are read-only and focus on listing and
 * querying vCenter resources.
 * 
 * The service provides the following MCP tools:
 * - getClusters(): Lists all clusters in the vCenter
 * - getResourcePoolsInCluster(): Lists resource pools within a specific cluster
 * - getVMsInCluster(): Lists virtual machines within a specific cluster
 * - getVMsInResourcePool(): Lists virtual machines within a specific resource pool
 * - listAllVirtualMachines(): Lists all virtual machines across the entire vCenter
 * 
 * Each tool method includes comprehensive logging for debugging and error handling
 * to provide meaningful feedback when operations fail.
 * 
 * The service also includes helper methods for name-to-ID resolution and data
 * structure classes for representing vCenter resources.
 */
@Service
public class VCenterService {

    private static final Logger logger = LoggerFactory.getLogger(VCenterService.class);
    
    /** The vAPI client for communicating with vCenter */
    private final VapiClient vapiClient;

    /**
     * Constructs a new VCenterService with the specified vAPI client.
     * 
     * @param vapiClient The vAPI client for vCenter communication
     */
    public VCenterService(VapiClient vapiClient) {
        this.vapiClient = vapiClient;
        logger.info("VCenterService initialized with VapiClient");
    }

    /**
     * MCP tool: Gets a list of all clusters in the vCenter.
     * 
     * This tool retrieves all clusters available in the vCenter and returns them
     * as a list of ClusterInfo objects. Each cluster includes its ID, name, and
     * resource pool ID (which may be null as it's not included in the vAPI response).
     * 
     * The method includes comprehensive logging and error handling to help with
     * debugging issues with vCenter connectivity or data processing.
     * 
     * @return List of ClusterInfo objects representing all clusters in the vCenter
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "Get a list of all clusters in the vCenter")
    public List<ClusterInfo> getClusters() {
        logger.info("=== MCP TOOL CALLED: getClusters() ===");
        try {
            logger.info("Making vAPI call to get clusters from vCenter");
            JsonNode clustersNode = vapiClient.clusters().list();
            
            logger.info("Raw clusters response: {}", clustersNode.toString());
            logger.info("Clusters response type: {}", clustersNode.getNodeType());
            logger.info("Clusters response is array: {}", clustersNode.isArray());
            logger.info("Clusters response size: {}", clustersNode.size());
            
            List<ClusterInfo> result = new ArrayList<>();
            for (JsonNode cluster : clustersNode) {
                logger.info("Processing cluster node: {}", cluster.toString());
                logger.info("Available fields: {}", cluster.fieldNames());
                logger.info("Cluster node type: {}", cluster.getNodeType());
                
                // Check if fields exist before accessing them
                if (!cluster.has("cluster")) {
                    logger.error("Cluster node missing 'cluster' field: {}", cluster.toString());
                    continue;
                }
                if (!cluster.has("name")) {
                    logger.error("Cluster node missing 'name' field: {}", cluster.toString());
                    continue;
                }
                
                // For clusters, we don't have a resource_pool field in the response
                // The resource pool ID will be null and can be retrieved separately if needed
                result.add(new ClusterInfo(
                    cluster.get("cluster").asText(),
                    cluster.get("name").asText(),
                    null  // resource_pool field is not available in cluster response
                ));
            }
            logger.info("Retrieved {} clusters from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve clusters via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve clusters: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets a list of resource pools in a specific cluster.
     * 
     * This tool retrieves all resource pools that belong to the specified cluster.
     * The cluster is identified by its friendly name, which is converted to the
     * internal cluster ID for the vAPI call.
     * 
     * The method includes error handling for cases where the cluster name is not
     * found, and comprehensive logging for debugging.
     * 
     * @param clusterName The friendly name of the cluster to list resource pools for
     * @return List of ResourcePoolInfo objects representing resource pools in the cluster
     * @throws RuntimeException if the cluster is not found or the vAPI call fails
     */
    @Tool(description = "Get a list of resource pools in a specific cluster. Parameter: clusterName (String) - the friendly name of the cluster")
    public List<ResourcePoolInfo> getResourcePoolsInCluster(String clusterName) {
        try {
            logger.debug("Making vAPI call to get resource pools for cluster: {}", clusterName);
            
            // First get the cluster ID from the cluster name
            String clusterId = getClusterIdByName(clusterName);
            if (clusterId == null) {
                throw new RuntimeException("Cluster not found: " + clusterName);
            }
            
            JsonNode resourcePoolsNode = vapiClient.resourcePools().list(clusterId);
            
            List<ResourcePoolInfo> result = new ArrayList<>();
            for (JsonNode resourcePool : resourcePoolsNode) {
                result.add(new ResourcePoolInfo(
                    resourcePool.get("resource_pool").asText(),
                    resourcePool.get("name").asText()
                ));
            }
            logger.info("Retrieved {} resource pools for cluster '{}' via vAPI", result.size(), clusterName);
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve resource pools for cluster '{}' via vAPI: {}", clusterName, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve resource pools for cluster '" + clusterName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets a list of virtual machines in a specific cluster.
     * 
     * This tool retrieves all virtual machines that belong to the specified cluster.
     * The cluster is identified by its friendly name, which is converted to the
     * internal cluster ID for the vAPI call.
     * 
     * The method includes error handling for cases where the cluster name is not
     * found, and comprehensive logging for debugging.
     * 
     * @param clusterName The friendly name of the cluster to list VMs for
     * @return List of VMInfo objects representing virtual machines in the cluster
     * @throws RuntimeException if the cluster is not found or the vAPI call fails
     */
    @Tool(description = "Get a list of virtual machines in a specific cluster. Parameter: clusterName (String) - the friendly name of the cluster")
    public List<VMInfo> getVMsInCluster(String clusterName) {
        try {
            logger.debug("Making vAPI call to get VMs for cluster: {}", clusterName);
            
            // First get the cluster ID from the cluster name
            String clusterId = getClusterIdByName(clusterName);
            if (clusterId == null) {
                throw new RuntimeException("Cluster not found: " + clusterName);
            }
            
            JsonNode vmsNode = vapiClient.vms().list(clusterId, null);
            
            List<VMInfo> result = new ArrayList<>();
            for (JsonNode vm : vmsNode) {
                result.add(new VMInfo(
                    vm.get("vm").asText(),
                    vm.get("name").asText(),
                    vm.get("power_state").asText()
                ));
            }
            logger.info("Retrieved {} VMs for cluster '{}' via vAPI", result.size(), clusterName);
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve VMs for cluster '{}' via vAPI: {}", clusterName, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve VMs for cluster '" + clusterName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets a list of virtual machines in a specific resource pool.
     * 
     * This tool retrieves all virtual machines that belong to the specified resource pool.
     * The resource pool is identified by its friendly name, which is converted to the
     * internal resource pool ID for the vAPI call.
     * 
     * The method includes error handling for cases where the resource pool name is not
     * found, and comprehensive logging for debugging.
     * 
     * @param resourcePoolName The friendly name of the resource pool to list VMs for
     * @return List of VMInfo objects representing virtual machines in the resource pool
     * @throws RuntimeException if the resource pool is not found or the vAPI call fails
     */
    @Tool(description = "Get a list of virtual machines in a specific resource pool. Parameter: resourcePoolName (String) - the friendly name of the resource pool")
    public List<VMInfo> getVMsInResourcePool(String resourcePoolName) {
        try {
            logger.debug("Making vAPI call to get VMs for resource pool: {}", resourcePoolName);
            
            // First get the resource pool ID from the resource pool name
            String resourcePoolId = getResourcePoolIdByName(resourcePoolName);
            if (resourcePoolId == null) {
                throw new RuntimeException("Resource pool not found: " + resourcePoolName);
            }
            
            JsonNode vmsNode = vapiClient.vms().list(null, resourcePoolId);
            
            List<VMInfo> result = new ArrayList<>();
            for (JsonNode vm : vmsNode) {
                result.add(new VMInfo(
                    vm.get("vm").asText(),
                    vm.get("name").asText(),
                    vm.get("power_state").asText()
                ));
            }
            logger.info("Retrieved {} VMs for resource pool '{}' via vAPI", result.size(), resourcePoolName);
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve VMs for resource pool '{}' via vAPI: {}", resourcePoolName, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve VMs for resource pool '" + resourcePoolName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Lists all virtual machines across the entire vCenter.
     * 
     * This tool retrieves all virtual machines in the vCenter regardless of their
     * cluster or resource pool assignment. It's useful for getting a complete
     * inventory of all VMs in the environment.
     * 
     * Unlike the other VM listing tools, this method requires no parameters and
     * returns every VM in the vCenter. The method includes comprehensive logging
     * and error handling.
     * 
     * @return List of VMInfo objects representing all virtual machines in the vCenter
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "List ALL virtual machines across the entire vCenter. This tool requires NO parameters and will return every VM in the vCenter regardless of cluster or resource pool.")
    public List<VMInfo> listAllVirtualMachines() {
        try {
            logger.debug("Making vAPI call to get all VMs from vCenter");
            
            // Get all VMs without any filters
            JsonNode vmsNode = vapiClient.vms().list(null, null);
            
            List<VMInfo> result = new ArrayList<>();
            for (JsonNode vm : vmsNode) {
                result.add(new VMInfo(
                    vm.get("vm").asText(),
                    vm.get("name").asText(),
                    vm.get("power_state").asText()
                ));
            }
            logger.info("Retrieved {} total VMs from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve all VMs via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve all VMs: " + e.getMessage(), e);
        }
    }

    /**
     * Helper method: Converts a cluster name to its internal cluster ID.
     * 
     * This method retrieves all clusters from vCenter and searches for one
     * with a matching name. It's used by the MCP tools to convert user-friendly
     * cluster names to the internal IDs required by the vAPI.
     * 
     * @param clusterName The friendly name of the cluster to find
     * @return The cluster ID if found, null otherwise
     */
    private String getClusterIdByName(String clusterName) {
        try {
            JsonNode clustersNode = vapiClient.clusters().list();
            for (JsonNode cluster : clustersNode) {
                if (cluster.get("name").asText().equals(clusterName)) {
                    return cluster.get("cluster").asText();
                }
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to get cluster ID for name '{}': {}", clusterName, e.getMessage());
            return null;
        }
    }

    /**
     * Helper method: Converts a resource pool name to its internal resource pool ID.
     * 
     * This method searches through all clusters and their resource pools to find
     * a resource pool with a matching name. It's used by the MCP tools to convert
     * user-friendly resource pool names to the internal IDs required by the vAPI.
     * 
     * The search is performed across all clusters since resource pool names might
     * not be globally unique in vCenter.
     * 
     * @param resourcePoolName The friendly name of the resource pool to find
     * @return The resource pool ID if found, null otherwise
     */
    private String getResourcePoolIdByName(String resourcePoolName) {
        try {
            // Get all clusters first
            JsonNode clustersNode = vapiClient.clusters().list();
            for (JsonNode cluster : clustersNode) {
                String clusterId = cluster.get("cluster").asText();
                JsonNode resourcePoolsNode = vapiClient.resourcePools().list(clusterId);
                for (JsonNode resourcePool : resourcePoolsNode) {
                    if (resourcePool.get("name").asText().equals(resourcePoolName)) {
                        return resourcePool.get("resource_pool").asText();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to get resource pool ID for name '{}': {}", resourcePoolName, e.getMessage());
            return null;
        }
    }

    /**
     * Data structure representing a vCenter cluster.
     * 
     * This class holds information about a vCenter cluster including its
     * internal ID, display name, and associated resource pool ID. The
     * resource pool ID may be null as it's not included in the vAPI
     * cluster response.
     */
    public static class ClusterInfo {
        private final String id;
        private final String name;
        private final String resourcePoolId;

        public ClusterInfo(String id, String name, String resourcePoolId) {
            this.id = id;
            this.name = name;
            this.resourcePoolId = resourcePoolId;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getResourcePoolId() { return resourcePoolId; }

        @Override
        public String toString() {
            return "ClusterInfo{id='" + id + "', name='" + name + "', resourcePoolId='" + (resourcePoolId != null ? resourcePoolId : "N/A") + "'}";
        }
    }

    /**
     * Data structure representing a vCenter resource pool.
     * 
     * This class holds information about a vCenter resource pool including
     * its internal ID and display name.
     */
    public static class ResourcePoolInfo {
        private final String id;
        private final String name;

        public ResourcePoolInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }

        @Override
        public String toString() {
            return "ResourcePoolInfo{id='" + id + "', name='" + name + "'}";
        }
    }

    /**
     * Data structure representing a vCenter virtual machine.
     * 
     * This class holds information about a vCenter virtual machine including
     * its internal ID, display name, and current power state.
     */
    public static class VMInfo {
        private final String id;
        private final String name;
        private final String powerState;

        public VMInfo(String id, String name, String powerState) {
            this.id = id;
            this.name = name;
            this.powerState = powerState;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getPowerState() { return powerState; }

        @Override
        public String toString() {
            return "VMInfo{id='" + id + "', name='" + name + "', powerState='" + powerState + "'}";
        }
    }
} 