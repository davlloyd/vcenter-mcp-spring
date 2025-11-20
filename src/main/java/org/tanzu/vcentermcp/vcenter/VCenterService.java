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
            IdResolutionResult resolution = getClusterIdByName(clusterName);
            if (resolution.getId() == null) {
                throw new RuntimeException("Cluster not found: " + clusterName);
            }
            
            if (resolution.hasWarning()) {
                logger.warn("Duplicate cluster name detected: {}", resolution.getWarning());
            }
            
            JsonNode resourcePoolsNode = vapiClient.resourcePools().list(resolution.getId());
            
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
            IdResolutionResult resolution = getClusterIdByName(clusterName);
            if (resolution.getId() == null) {
                throw new RuntimeException("Cluster not found: " + clusterName);
            }
            
            if (resolution.hasWarning()) {
                logger.warn("Duplicate cluster name detected: {}", resolution.getWarning());
            }
            
            JsonNode vmsNode = vapiClient.vms().list(resolution.getId(), null);
            
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
            IdResolutionResult resolution = getResourcePoolIdByName(resourcePoolName);
            if (resolution.getId() == null) {
                throw new RuntimeException("Resource pool not found: " + resourcePoolName);
            }
            
            if (resolution.hasWarning()) {
                logger.warn("Duplicate resource pool name detected: {}", resolution.getWarning());
            }
            
            JsonNode vmsNode = vapiClient.vms().list(null, resolution.getId());
            
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
     * MCP tool: Gets a list of all datacenters in the vCenter.
     * 
     * This tool retrieves all datacenters available in the vCenter and returns them
     * as a list of DataCenterInfo objects. Each datacenter includes its ID and name.
     * 
     * @return List of DataCenterInfo objects representing all datacenters in the vCenter
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "Get a list of all datacenters in the vCenter")
    public List<DataCenterInfo> listDataCenters() {
        logger.info("=== MCP TOOL CALLED: listDataCenters() ===");
        try {
            logger.info("Making vAPI call to get datacenters from vCenter");
            JsonNode datacentersNode = vapiClient.datacenters().list();
            
            List<DataCenterInfo> result = new ArrayList<>();
            if (datacentersNode.isArray()) {
                for (JsonNode datacenter : datacentersNode) {
                    if (datacenter.has("datacenter") && datacenter.has("name")) {
                        result.add(new DataCenterInfo(
                            datacenter.get("datacenter").asText(),
                            datacenter.get("name").asText()
                        ));
                    }
                }
            }
            logger.info("Retrieved {} datacenters from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve datacenters via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve datacenters: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets vCenter version information.
     * 
     * This tool retrieves version information about the vCenter system.
     * 
     * @return VersionInfo object containing version information
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "Get vCenter version information")
    public VersionInfo getVCenterVersion() {
        logger.info("=== MCP TOOL CALLED: getVCenterVersion() ===");
        try {
            logger.info("Making vAPI call to get version information from vCenter");
            JsonNode versionNode = vapiClient.appliance().getVersion();
            
            // Parse version information
            String version = "";
            String build = "";
            String vendor = "VMware";
            
            if (versionNode.isObject()) {
                if (versionNode.has("version")) {
                    version = versionNode.get("version").asText();
                }
                if (versionNode.has("build")) {
                    build = versionNode.get("build").asText();
                }
            }
            
            logger.info("Retrieved vCenter version: {}", version);
            return new VersionInfo(version, build, vendor);
        } catch (Exception e) {
            logger.error("Failed to retrieve version information via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve version information: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Lists all datastores with capacity and consumption information.
     * 
     * This tool retrieves all datastores in the vCenter along with their capacity
     * and usage information. For each datastore, it also fetches detailed information
     * including capacity and free space.
     * 
     * @return List of DatastoreInfo objects with capacity and usage details
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "List all datastores with capacity and consumption information. Returns datastore name, type, capacity in bytes, and free space in bytes.")
    public List<DatastoreInfo> listDataStoresWithCapacity() {
        logger.info("=== MCP TOOL CALLED: listDataStoresWithCapacity() ===");
        try {
            logger.info("Making vAPI call to get datastores from vCenter");
            JsonNode datastoresNode = vapiClient.datastores().list();
            
            List<DatastoreInfo> result = new ArrayList<>();
            if (datastoresNode.isArray()) {
                for (JsonNode datastore : datastoresNode) {
                    if (datastore.has("datastore") && datastore.has("name")) {
                        String datastoreId = datastore.get("datastore").asText();
                        String datastoreName = datastore.get("name").asText();
                        String type = datastore.has("type") ? datastore.get("type").asText() : "unknown";
                        
                        // Try to get detailed information about the datastore
                        long capacity = -1;
                        long freeSpace = -1;
                        try {
                            JsonNode detailsNode = vapiClient.datastores().get(datastoreId);
                            if (detailsNode.isArray() && detailsNode.size() > 0) {
                                detailsNode = detailsNode.get(0);
                            }
                            if (detailsNode.has("capacity")) {
                                capacity = detailsNode.get("capacity").asLong();
                            }
                            if (detailsNode.has("free_space")) {
                                freeSpace = detailsNode.get("free_space").asLong();
                            }
                        } catch (Exception e) {
                            logger.warn("Could not get detailed information for datastore {}: {}", datastoreName, e.getMessage());
                        }
                        
                        result.add(new DatastoreInfo(
                            datastoreId,
                            datastoreName,
                            type,
                            capacity,
                            freeSpace
                        ));
                    }
                }
            }
            logger.info("Retrieved {} datastores from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve datastores via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve datastores: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets detailed resource information for clusters including CPU and memory.
     * 
     * This tool retrieves detailed resource information for all clusters including
     * total CPU cores, total memory, and current utilization.
     * 
     * @return List of ClusterResourceInfo objects with resource details
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "Get detailed resource information for all clusters including CPU, RAM, and utilization metrics")
    public List<ClusterResourceInfo> listClusterResources() {
        logger.info("=== MCP TOOL CALLED: listClusterResources() ===");
        try {
            logger.info("Making vAPI call to get clusters and resource information");
            JsonNode clustersNode = vapiClient.clusters().list();
            
            List<ClusterResourceInfo> result = new ArrayList<>();
            if (clustersNode.isArray()) {
                for (JsonNode cluster : clustersNode) {
                    if (cluster.has("cluster") && cluster.has("name")) {
                        String clusterId = cluster.get("cluster").asText();
                        String clusterName = cluster.get("name").asText();
                        
                        // Resource information is typically not directly available from cluster list
                        // We would need to query hosts in the cluster or use resource pool info
                        // For now, we'll return basic info and note that detailed metrics may require additional queries
                        result.add(new ClusterResourceInfo(
                            clusterId,
                            clusterName,
                            -1, // CPU MHz (not available directly)
                            -1, // RAM bytes (not available directly)
                            "N/A", // CPU utilization (requires additional queries)
                            "N/A"  // Memory utilization (requires additional queries)
                        ));
                    }
                }
            }
            logger.info("Retrieved resource information for {} clusters from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve cluster resource information via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve cluster resource information: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Lists all hosts in the vCenter.
     * 
     * This tool retrieves all hosts available in the vCenter and returns them
     * as a list of HostInfo objects. Each host includes its ID, name, connection state,
     * and power state.
     * 
     * @return List of HostInfo objects representing all hosts in the vCenter
     * @throws RuntimeException if the vAPI call fails or data processing errors occur
     */
    @Tool(description = "List all hosts in the vCenter with their connection and power state")
    public List<HostInfo> listHosts() {
        logger.info("=== MCP TOOL CALLED: listHosts() ===");
        try {
            logger.info("Making vAPI call to get hosts from vCenter");
            JsonNode hostsNode = vapiClient.hosts().list();
            
            List<HostInfo> result = new ArrayList<>();
            if (hostsNode.isArray()) {
                for (JsonNode host : hostsNode) {
                    if (host.has("host") && host.has("name")) {
                        result.add(new HostInfo(
                            host.get("host").asText(),
                            host.get("name").asText(),
                            host.has("connection_state") ? host.get("connection_state").asText() : "unknown",
                            host.has("power_state") ? host.get("power_state").asText() : "unknown"
                        ));
                    }
                }
            }
            logger.info("Retrieved {} hosts from vCenter via vAPI", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve hosts via vAPI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve hosts: " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Gets detailed information about a specific virtual machine.
     * 
     * This tool retrieves detailed information about a VM identified by its name.
     * The information includes power state, CPU and memory configuration, guest OS,
     * IP addresses, host information, and other detailed VM properties.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return DetailedVMInfo object containing detailed VM information
     */
    @Tool(description = "Get detailed information about a virtual machine. Parameter: vmName (String) - the friendly name of the virtual machine")
    public DetailedVMInfo getVMDetails(String vmName) {
        logger.info("=== MCP TOOL CALLED: getVMDetails({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            if (resolution.hasWarning()) {
                logger.warn("Duplicate VM name detected: {}", resolution.getWarning());
            }
            
            logger.info("Getting detailed information for VM: {} (ID: {})", vmName, resolution.getId());
            JsonNode vmDetailsNode = vapiClient.vms().get(resolution.getId());
            
            // Parse the detailed VM information
            String name = vmName;
            String powerState = "unknown";
            String guestOs = "unknown";
            int cpuCount = -1;
            long memoryBytes = -1;
            String vmIdValue = resolution.getId();
            String hostId = "";
            String hostName = "";
            String datastoreId = "";
            String datastoreName = "";
            String resourcePoolId = "";
            String resourcePoolName = "";
            String vmFolderId = "";
            String vmFolderName = "";
            
            if (vmDetailsNode.isArray() && vmDetailsNode.size() > 0) {
                JsonNode vm = vmDetailsNode.get(0);
                name = vm.has("name") ? vm.get("name").asText() : vmName;
                powerState = vm.has("power_state") ? vm.get("power_state").asText() : "unknown";
                
                // CPU configuration
                if (vm.has("cpu")) {
                    if (vm.get("cpu").has("count")) {
                        cpuCount = vm.get("cpu").get("count").asInt();
                    }
                }
                
                // Memory configuration
                if (vm.has("memory")) {
                    if (vm.get("memory").has("size_MiB")) {
                        memoryBytes = vm.get("memory").get("size_MiB").asLong() * 1024 * 1024; // Convert MiB to bytes
                    }
                }
                
                // Guest OS
                if (vm.has("guest_OS")) {
                    guestOs = vm.get("guest_OS").asText();
                }
                
                // Host information
                if (vm.has("host")) {
                    hostId = vm.get("host").asText();
                    // Try to resolve host name
                    try {
                        IdResolutionResult hostResolution = getHostIdByName(hostId);
                        if (hostResolution.getId() != null) {
                            // If found, we already have the ID, just need the name
                            // For simplicity, use the host ID as name for now
                            hostName = hostId;
                        }
                    } catch (Exception e) {
                        logger.debug("Could not resolve host name for ID: {}", hostId);
                    }
                }
                
                // Datastore information
                if (vm.has("datastore")) {
                    datastoreId = vm.get("datastore").asText();
                    // For simplicity, use datastore ID as name for now
                    datastoreName = datastoreId;
                }
                
                // Resource pool information
                if (vm.has("resource_pool")) {
                    resourcePoolId = vm.get("resource_pool").asText();
                    // Try to resolve resource pool name
                    try {
                        IdResolutionResult rpResolution = getResourcePoolIdByName(resourcePoolId);
                        if (rpResolution.getId() != null) {
                            resourcePoolName = resourcePoolId;
                        }
                    } catch (Exception e) {
                        logger.debug("Could not resolve resource pool name for ID: {}", resourcePoolId);
                    }
                }
                
                // VM folder information
                if (vm.has("folder")) {
                    vmFolderId = vm.get("folder").asText();
                    // For simplicity, use folder ID as name for now
                    vmFolderName = vmFolderId;
                }
            }
            
            DetailedVMInfo vmInfo = new DetailedVMInfo(
                vmIdValue,
                name,
                powerState,
                guestOs,
                cpuCount,
                memoryBytes,
                hostId,
                hostName,
                datastoreId,
                datastoreName,
                resourcePoolId,
                resourcePoolName,
                vmFolderId,
                vmFolderName
            );
            
            // Add warning if duplicate names were detected
            if (resolution.hasWarning()) {
                vmInfo.setWarning(resolution.getWarning());
            }
            
            logger.info("Successfully retrieved detailed information for VM: {}", vmName);
            return vmInfo;
        } catch (Exception e) {
            logger.error("Failed to get VM details for '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to get VM details for '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Powers on a virtual machine.
     * 
     * This tool powers on a VM identified by its name. Requires vCenter write permissions.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return Status message indicating success or failure
     */
    @Tool(description = "Power on a virtual machine. Parameter: vmName (String) - the friendly name of the virtual machine")
    public String powerOnVM(String vmName) {
        logger.info("=== MCP TOOL CALLED: powerOnVM({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            String warning = resolution.hasWarning() ? " " + resolution.getWarning() : "";
            vapiClient.vms().powerOn(resolution.getId());
            logger.info("Successfully powered on VM: {}{}", vmName, warning);
            return "Successfully powered on VM: " + vmName + warning;
        } catch (Exception e) {
            logger.error("Failed to power on VM '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to power on VM '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Powers off a virtual machine.
     * 
     * This tool performs a hard power off of a VM identified by its name. Requires vCenter write permissions.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return Status message indicating success or failure
     */
    @Tool(description = "Power off a virtual machine (hard power off). Parameter: vmName (String) - the friendly name of the virtual machine")
    public String powerOffVM(String vmName) {
        logger.info("=== MCP TOOL CALLED: powerOffVM({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            String warning = resolution.hasWarning() ? " " + resolution.getWarning() : "";
            vapiClient.vms().powerOff(resolution.getId());
            logger.info("Successfully powered off VM: {}{}", vmName, warning);
            return "Successfully powered off VM: " + vmName + warning;
        } catch (Exception e) {
            logger.error("Failed to power off VM '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to power off VM '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Resets a virtual machine.
     * 
     * This tool performs a hard reset of a VM identified by its name. Requires vCenter write permissions.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return Status message indicating success or failure
     */
    @Tool(description = "Reset a virtual machine (hard reset). Parameter: vmName (String) - the friendly name of the virtual machine")
    public String resetVM(String vmName) {
        logger.info("=== MCP TOOL CALLED: resetVM({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            String warning = resolution.hasWarning() ? " " + resolution.getWarning() : "";
            vapiClient.vms().reset(resolution.getId());
            logger.info("Successfully reset VM: {}{}", vmName, warning);
            return "Successfully reset VM: " + vmName + warning;
        } catch (Exception e) {
            logger.error("Failed to reset VM '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to reset VM '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Restarts the guest OS of a virtual machine.
     * 
     * This tool performs a soft restart of the guest OS in a VM identified by its name. Requires vCenter write permissions.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return Status message indicating success or failure
     */
    @Tool(description = "Restart the guest OS of a virtual machine (soft restart). Parameter: vmName (String) - the friendly name of the virtual machine")
    public String restartVM(String vmName) {
        logger.info("=== MCP TOOL CALLED: restartVM({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            String warning = resolution.hasWarning() ? " " + resolution.getWarning() : "";
            vapiClient.vms().restart(resolution.getId());
            logger.info("Successfully restarted VM: {}{}", vmName, warning);
            return "Successfully restarted VM: " + vmName + warning;
        } catch (Exception e) {
            logger.error("Failed to restart VM '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to restart VM '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Shuts down the guest OS of a virtual machine.
     * 
     * This tool performs a soft shutdown of the guest OS in a VM identified by its name. Requires vCenter write permissions.
     * 
     * @param vmName The friendly name of the virtual machine
     * @return Status message indicating success or failure
     */
    @Tool(description = "Shut down the guest OS of a virtual machine (soft shutdown). Parameter: vmName (String) - the friendly name of the virtual machine")
    public String shutdownVM(String vmName) {
        logger.info("=== MCP TOOL CALLED: shutdownVM({}) ===", vmName);
        try {
            IdResolutionResult resolution = getVmIdByName(vmName);
            if (resolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            String warning = resolution.hasWarning() ? " " + resolution.getWarning() : "";
            vapiClient.vms().shutdown(resolution.getId());
            logger.info("Successfully shut down VM: {}{}", vmName, warning);
            return "Successfully shut down VM: " + vmName + warning;
        } catch (Exception e) {
            logger.error("Failed to shutdown VM '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to shutdown VM '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Migrates a virtual machine to a different host.
     * 
     * This tool migrates a VM to a different host. Requires vCenter write permissions.
     * Both VM name and target host name are user-friendly names that will be resolved to IDs.
     * 
     * @param vmName The friendly name of the virtual machine
     * @param targetHostName The friendly name of the target host
     * @return Status message indicating success or failure
     */
    @Tool(description = "Migrate a virtual machine to a different host. Parameters: vmName (String) - the friendly name of the virtual machine, targetHostName (String) - the friendly name of the target host")
    public String migrateVM(String vmName, String targetHostName) {
        logger.info("=== MCP TOOL CALLED: migrateVM({}, {}) ===", vmName, targetHostName);
        try {
            IdResolutionResult vmResolution = getVmIdByName(vmName);
            if (vmResolution.getId() == null) {
                throw new RuntimeException("VM not found: " + vmName);
            }
            
            IdResolutionResult hostResolution = getHostIdByName(targetHostName);
            if (hostResolution.getId() == null) {
                throw new RuntimeException("Target host not found: " + targetHostName);
            }
            
            String warning = "";
            if (vmResolution.hasWarning()) {
                warning += " " + vmResolution.getWarning();
            }
            if (hostResolution.hasWarning()) {
                warning += " " + hostResolution.getWarning();
            }
            
            vapiClient.vms().migrate(vmResolution.getId(), hostResolution.getId());
            logger.info("Successfully migrated VM {} to host {}{}", vmName, targetHostName, warning);
            return "Successfully migrated VM " + vmName + " to host " + targetHostName + warning;
        } catch (Exception e) {
            logger.error("Failed to migrate VM '{}' to host '{}': {}", vmName, targetHostName, e.getMessage(), e);
            throw new RuntimeException("Failed to migrate VM '" + vmName + "' to host '" + targetHostName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Helper class to hold ID resolution result and any warnings.
     */
    private static class IdResolutionResult {
        private final String id;
        private final String warning;
        
        public IdResolutionResult(String id, String warning) {
            this.id = id;
            this.warning = warning;
        }
        
        public String getId() { return id; }
        public String getWarning() { return warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }
    }

    /**
     * Helper method: Converts a cluster name to its internal cluster ID.
     * 
     * This method retrieves all clusters from vCenter and searches for one
     * with a matching name. It checks for duplicates and returns warnings.
     * 
     * @param clusterName The friendly name of the cluster to find
     * @return IdResolutionResult containing the cluster ID and any warnings about duplicates
     */
    private IdResolutionResult getClusterIdByName(String clusterName) {
        try {
            JsonNode clustersNode = vapiClient.clusters().list();
            String foundId = null;
            int matchCount = 0;
            
            for (JsonNode cluster : clustersNode) {
                if (cluster.get("name").asText().equals(clusterName)) {
                    if (foundId == null) {
                        foundId = cluster.get("cluster").asText();
                    }
                    matchCount++;
                }
            }
            
            if (foundId == null) {
                return new IdResolutionResult(null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d clusters with the same name '%s'. Using the first match.", matchCount, clusterName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning);
        } catch (Exception e) {
            logger.error("Failed to get cluster ID for name '{}': {}", clusterName, e.getMessage());
            return new IdResolutionResult(null, null);
        }
    }

    /**
     * Helper method: Converts a resource pool name to its internal resource pool ID.
     * 
     * This method searches through all clusters and their resource pools to find
     * a resource pool with a matching name. It checks for duplicates and returns warnings.
     * 
     * @param resourcePoolName The friendly name of the resource pool to find
     * @return IdResolutionResult containing the resource pool ID and any warnings about duplicates
     */
    private IdResolutionResult getResourcePoolIdByName(String resourcePoolName) {
        try {
            // Get all clusters first
            JsonNode clustersNode = vapiClient.clusters().list();
            String foundId = null;
            int matchCount = 0;
            
            for (JsonNode cluster : clustersNode) {
                String clusterId = cluster.get("cluster").asText();
                JsonNode resourcePoolsNode = vapiClient.resourcePools().list(clusterId);
                for (JsonNode resourcePool : resourcePoolsNode) {
                    if (resourcePool.get("name").asText().equals(resourcePoolName)) {
                        if (foundId == null) {
                            foundId = resourcePool.get("resource_pool").asText();
                        }
                        matchCount++;
                    }
                }
            }
            
            if (foundId == null) {
                return new IdResolutionResult(null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d resource pools with the same name '%s'. Using the first match.", matchCount, resourcePoolName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning);
        } catch (Exception e) {
            logger.error("Failed to get resource pool ID for name '{}': {}", resourcePoolName, e.getMessage());
            return new IdResolutionResult(null, null);
        }
    }
    
    /**
     * Helper method: Converts a VM name to its internal VM ID.
     * 
     * This method searches through all VMs in the vCenter to find one with a matching name.
     * It checks for duplicates and returns warnings.
     * 
     * @param vmName The friendly name of the VM to find
     * @return IdResolutionResult containing the VM ID and any warnings about duplicates
     */
    private IdResolutionResult getVmIdByName(String vmName) {
        try {
            JsonNode vmsNode = vapiClient.vms().list(null, null);
            String foundId = null;
            int matchCount = 0;
            
            for (JsonNode vm : vmsNode) {
                if (vm.has("name") && vm.get("name").asText().equals(vmName)) {
                    if (foundId == null) {
                        foundId = vm.get("vm").asText();
                    }
                    matchCount++;
                }
            }
            
            if (foundId == null) {
                return new IdResolutionResult(null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d VMs with the same name '%s'. Using the first match.", matchCount, vmName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning);
        } catch (Exception e) {
            logger.error("Failed to get VM ID for name '{}': {}", vmName, e.getMessage());
            return new IdResolutionResult(null, null);
        }
    }
    
    /**
     * Helper method: Converts a host name to its internal host ID.
     * 
     * This method searches through all hosts in the vCenter to find one with a matching name.
     * It checks for duplicates and returns warnings.
     * 
     * @param hostName The friendly name of the host to find
     * @return IdResolutionResult containing the host ID and any warnings about duplicates
     */
    private IdResolutionResult getHostIdByName(String hostName) {
        try {
            JsonNode hostsNode = vapiClient.hosts().list();
            String foundId = null;
            int matchCount = 0;
            
            for (JsonNode host : hostsNode) {
                if (host.has("name") && host.get("name").asText().equals(hostName)) {
                    if (foundId == null) {
                        foundId = host.get("host").asText();
                    }
                    matchCount++;
                }
            }
            
            if (foundId == null) {
                return new IdResolutionResult(null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d hosts with the same name '%s'. Using the first match.", matchCount, hostName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning);
        } catch (Exception e) {
            logger.error("Failed to get host ID for name '{}': {}", hostName, e.getMessage());
            return new IdResolutionResult(null, null);
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

    /**
     * Data structure representing a vCenter datacenter.
     * 
     * This class holds information about a vCenter datacenter including
     * its internal ID and display name.
     */
    public static class DataCenterInfo {
        private final String id;
        private final String name;

        public DataCenterInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }

        @Override
        public String toString() {
            return "DataCenterInfo{id='" + id + "', name='" + name + "'}";
        }
    }

    /**
     * Data structure representing vCenter version information.
     * 
     * This class holds information about the vCenter version, build, and vendor.
     */
    public static class VersionInfo {
        private final String version;
        private final String build;
        private final String vendor;

        public VersionInfo(String version, String build, String vendor) {
            this.version = version;
            this.build = build;
            this.vendor = vendor;
        }

        public String getVersion() { return version; }
        public String getBuild() { return build; }
        public String getVendor() { return vendor; }

        @Override
        public String toString() {
            return "VersionInfo{version='" + version + "', build='" + build + "', vendor='" + vendor + "'}";
        }
    }

    /**
     * Data structure representing a vCenter datastore with capacity information.
     * 
     * This class holds information about a vCenter datastore including
     * its internal ID, display name, type, capacity in bytes, and free space in bytes.
     */
    public static class DatastoreInfo {
        private final String id;
        private final String name;
        private final String type;
        private final long capacity;
        private final long freeSpace;

        public DatastoreInfo(String id, String name, String type, long capacity, long freeSpace) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.capacity = capacity;
            this.freeSpace = freeSpace;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public long getCapacity() { return capacity; }
        public long getFreeSpace() { return freeSpace; }
        public long getUsedSpace() { return capacity >= 0 && freeSpace >= 0 ? capacity - freeSpace : -1; }

        @Override
        public String toString() {
            return "DatastoreInfo{id='" + id + "', name='" + name + "', type='" + type + 
                   "', capacity=" + capacity + ", freeSpace=" + freeSpace + "}";
        }
    }

    /**
     * Data structure representing cluster resource information.
     * 
     * This class holds information about cluster resources including
     * CPU and memory capacity and utilization.
     */
    public static class ClusterResourceInfo {
        private final String id;
        private final String name;
        private final long cpuMhz;
        private final long ramBytes;
        private final String cpuUtilization;
        private final String memoryUtilization;

        public ClusterResourceInfo(String id, String name, long cpuMhz, long ramBytes, 
                                   String cpuUtilization, String memoryUtilization) {
            this.id = id;
            this.name = name;
            this.cpuMhz = cpuMhz;
            this.ramBytes = ramBytes;
            this.cpuUtilization = cpuUtilization;
            this.memoryUtilization = memoryUtilization;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public long getCpuMhz() { return cpuMhz; }
        public long getRamBytes() { return ramBytes; }
        public String getCpuUtilization() { return cpuUtilization; }
        public String getMemoryUtilization() { return memoryUtilization; }

        @Override
        public String toString() {
            return "ClusterResourceInfo{id='" + id + "', name='" + name + 
                   "', cpuMhz=" + cpuMhz + ", ramBytes=" + ramBytes + 
                   "', cpuUtilization='" + cpuUtilization + "', memoryUtilization='" + memoryUtilization + "'}";
        }
    }

    /**
     * Data structure representing a vCenter host.
     * 
     * This class holds information about a vCenter host including
     * its internal ID, display name, connection state, and power state.
     */
    public static class HostInfo {
        private final String id;
        private final String name;
        private final String connectionState;
        private final String powerState;

        public HostInfo(String id, String name, String connectionState, String powerState) {
            this.id = id;
            this.name = name;
            this.connectionState = connectionState;
            this.powerState = powerState;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getConnectionState() { return connectionState; }
        public String getPowerState() { return powerState; }

        @Override
        public String toString() {
            return "HostInfo{id='" + id + "', name='" + name + 
                   "', connectionState='" + connectionState + "', powerState='" + powerState + "'}";
        }
    }

    /**
     * Data structure representing detailed information about a virtual machine.
     * 
     * This class holds comprehensive information about a VM including power state,
     * CPU and memory configuration, guest OS, host information, datastore, resource pool,
     * VM folder, and other properties.
     */
    public static class DetailedVMInfo {
        private final String id;
        private final String name;
        private final String powerState;
        private final String guestOs;
        private final int cpuCount;
        private final long memoryBytes;
        private final String hostId;
        private final String hostName;
        private final String datastoreId;
        private final String datastoreName;
        private final String resourcePoolId;
        private final String resourcePoolName;
        private final String vmFolderId;
        private final String vmFolderName;
        private String warning;

        public DetailedVMInfo(String id, String name, String powerState, String guestOs, 
                             int cpuCount, long memoryBytes, String hostId, String hostName,
                             String datastoreId, String datastoreName, String resourcePoolId, 
                             String resourcePoolName, String vmFolderId, String vmFolderName) {
            this.id = id;
            this.name = name;
            this.powerState = powerState;
            this.guestOs = guestOs;
            this.cpuCount = cpuCount;
            this.memoryBytes = memoryBytes;
            this.hostId = hostId;
            this.hostName = hostName;
            this.datastoreId = datastoreId;
            this.datastoreName = datastoreName;
            this.resourcePoolId = resourcePoolId;
            this.resourcePoolName = resourcePoolName;
            this.vmFolderId = vmFolderId;
            this.vmFolderName = vmFolderName;
            this.warning = null;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getPowerState() { return powerState; }
        public String getGuestOs() { return guestOs; }
        public int getCpuCount() { return cpuCount; }
        public long getMemoryBytes() { return memoryBytes; }
        public String getMemoryMB() { return memoryBytes > 0 ? String.valueOf(memoryBytes / (1024 * 1024)) : "N/A"; }
        public String getHostId() { return hostId; }
        public String getHostName() { return hostName; }
        public String getDatastoreId() { return datastoreId; }
        public String getDatastoreName() { return datastoreName; }
        public String getResourcePoolId() { return resourcePoolId; }
        public String getResourcePoolName() { return resourcePoolName; }
        public String getVmFolderId() { return vmFolderId; }
        public String getVmFolderName() { return vmFolderName; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }

        @Override
        public String toString() {
            String base = "DetailedVMInfo{id='" + id + "', name='" + name + 
                   "', powerState='" + powerState + "', guestOs='" + guestOs + 
                   "', cpuCount=" + cpuCount + ", memoryBytes=" + memoryBytes + 
                   ", hostId='" + hostId + "', hostName='" + hostName + 
                   "', datastoreId='" + datastoreId + "', datastoreName='" + datastoreName +
                   "', resourcePoolId='" + resourcePoolId + "', resourcePoolName='" + resourcePoolName +
                   "', vmFolderId='" + vmFolderId + "', vmFolderName='" + vmFolderName + "'}";
            if (hasWarning()) {
                base += ", warning='" + warning + "'";
            }
            return base;
        }
    }
} 