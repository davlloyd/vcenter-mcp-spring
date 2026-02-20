package org.tanzu.vcentermcp.vcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service class that provides MCP (Model Context Protocol) tools for vCenter operations.
 *
 * <p>This service acts as the bridge between the MCP server and the vCenter vAPI client.
 * It exposes vCenter operations as MCP tools that can be consumed by AI assistants
 * and other MCP clients. Tools accept friendly names (e.g. VM name, cluster name)
 * and resolve them to vCenter IDs internally; duplicate-name warnings are returned
 * when applicable.
 *
 * <p><b>Inventory and listing:</b>
 * <ul>
 *   <li>{@link #getClusters()}, {@link #listDataCenters()}, {@link #listHosts()}</li>
 *   <li>{@link #getResourcePoolsInCluster(String)}, {@link #listDataStoresWithCapacity()}, {@link #listClusterResources()}</li>
 *   <li>{@link #getVMsInCluster(String)}, {@link #getVMsInResourcePool(String)}, {@link #listAllVirtualMachines()}</li>
 * </ul>
 *
 * <p><b>VM details and placement:</b>
 * <ul>
 *   <li>{@link #getVMResourceSummary(String)} – configured resources and power state</li>
 *   <li>{@link #getVMLocationDetails(String)} – datacenter, cluster, resource pool, datastore, host</li>
 *   <li>{@link #getVMResourcePool(String)} – resource pool and cluster for a VM</li>
 * </ul>
 *
 * <p><b>Version and host:</b>
 * <ul>
 *   <li>{@link #getVCenterVersion()}, {@link #getHostVersion(String)}</li>
 * </ul>
 *
 * <p><b>VM power and migration (write operations):</b>
 * <ul>
 *   <li>{@link #powerOnVM(String)}, {@link #powerOffVM(String)}, {@link #resetVM(String)}</li>
 *   <li>{@link #restartVM(String)}, {@link #shutdownVM(String)}, {@link #migrateVM(String, String)}</li>
 * </ul>
 *
 * <p>Tool methods include logging and consistent error handling; name resolution
 * uses retries with exponential backoff where appropriate.
 */
@Service
public class VCenterService {

    private static final Logger logger = LoggerFactory.getLogger(VCenterService.class);

    /** Maximum retries for VM details and VM name resolution when API calls are transiently failing. */
    private static final int MAX_RETRIES = 3;
    /** Initial delay in ms before first retry; doubled on each subsequent retry (exponential backoff). */
    private static final int RETRY_DELAY_MS = 500;

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
            
            // Parse version information - handle different response formats
            String version = "";
            String build = "";
            String vendor = "VMware";
            
            if (versionNode != null && versionNode.isObject()) {
                // Try different possible field names and structures
                if (versionNode.has("version")) {
                    version = versionNode.get("version").asText();
                } else if (versionNode.has("value") && versionNode.get("value").has("version")) {
                    version = versionNode.get("value").get("version").asText();
                }
                
                if (versionNode.has("build")) {
                    build = versionNode.get("build").asText();
                } else if (versionNode.has("value") && versionNode.get("value").has("build")) {
                    build = versionNode.get("value").get("build").asText();
                }
                
                // Log the full response for debugging
                logger.debug("Version response structure: {}", versionNode.toString());
            } else {
                logger.warn("Version response is not a valid object: {}", versionNode != null ? versionNode.toString() : "null");
            }
            
            if (version.isEmpty() && build.isEmpty()) {
                logger.warn("Could not parse version information from response. Response: {}", versionNode != null ? versionNode.toString() : "null");
                // Return a default response rather than failing
                return new VersionInfo("Unknown", "Unknown", vendor);
            }
            
            logger.info("Retrieved vCenter version: {} (build: {})", version, build);
            return new VersionInfo(version, build, vendor);
        } catch (Exception e) {
            logger.error("Failed to retrieve version information via vAPI: {}", e.getMessage(), e);
            logger.error("Exception type: {}, cause: {}", e.getClass().getName(), e.getCause() != null ? e.getCause().getMessage() : "none");
            
            // Check if this is a 404 error (endpoint not available)
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            if (errorMsg.contains("404") || errorMsg.contains("Not Found") || 
                (e.getCause() != null && e.getCause().getMessage() != null && 
                 (e.getCause().getMessage().contains("404") || e.getCause().getMessage().contains("Not Found")))) {
                logger.warn("Version endpoint not available on this vCenter instance. This may be due to vCenter version or permissions.");
                // Return a message indicating version info is not available
                return new VersionInfo("Not Available", "Version endpoint not accessible on this vCenter instance", "VMware");
            }
            
            // Provide a more user-friendly error message for other errors
            String errorMessage = "Unable to retrieve version information. ";
            if (errorMsg.contains("401") || errorMsg.contains("Unauthorized")) {
                errorMessage += "Authentication failed. Please check vCenter credentials.";
            } else if (errorMsg.contains("Connection") || errorMsg.contains("timeout") || 
                       errorMsg.contains("Connection issue")) {
                errorMessage += "Connection issue. Please ensure the vCenter server is accessible.";
            } else {
                errorMessage += "Error: " + errorMsg;
            }
            errorMessage += " Please ensure that the vCenter server is accessible and try again later.";
            throw new RuntimeException(errorMessage, e);
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
     * MCP tool: Provides configured resources and current power status for a VM.
     *
     * @param vmName The friendly name of the virtual machine
     * @return VMResourceSummary containing CPU, memory, guest OS, and power information
     */
    @Tool(description = "Get configured resources and current power status of a virtual machine. Parameter: vmName (String) - the friendly name of the virtual machine")
    public VMResourceSummary getVMResourceSummary(String vmName) {
        logger.info("=== MCP TOOL CALLED: getVMResourceSummary({}) ===", vmName);
        try {
            VmDetailsSnapshot snapshot = fetchVmDetailsSnapshot(vmName);
            VMResourceSummary summary = new VMResourceSummary(
                snapshot.getVmId(),
                snapshot.getName(),
                snapshot.getPowerState(),
                snapshot.getGuestOs(),
                snapshot.getCpuCount(),
                snapshot.getMemoryBytes()
            );
            if (snapshot.hasWarning()) {
                summary.setWarning(snapshot.getWarning());
            }
            logger.info("Successfully retrieved resource summary for VM: {}", vmName);
            return summary;
        } catch (Exception e) {
            logger.error("Failed to get VM resource summary for '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to get VM resource summary for '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Provides hosting information (datacenter, cluster, resource pool, datastore) for a VM.
     *
     * @param vmName The friendly name of the virtual machine
     * @return VMLocationDetails containing infrastructure placement data
     */
    @Tool(description = "Get hosting information for a virtual machine, including datacenter, cluster, resource pool, and datastore. Parameter: vmName (String) - the friendly name of the virtual machine")
    public VMLocationDetails getVMLocationDetails(String vmName) {
        logger.info("=== MCP TOOL CALLED: getVMLocationDetails({}) ===", vmName);
        try {
            VmDetailsSnapshot snapshot = fetchVmDetailsSnapshot(vmName);

            String datacenterName = resolveDatacenterNameById(snapshot.getDatacenterId());
            String clusterName = resolveClusterNameById(snapshot.getClusterId());
            String resourcePoolName = resolveResourcePoolNameById(snapshot.getResourcePoolId());
            String hostName = resolveHostNameById(snapshot.getHostId());
            List<String> datastoreNames = resolveDatastoreNames(snapshot.getDatastoreIds());

            VMLocationDetails details = new VMLocationDetails(
                snapshot.getVmId(),
                snapshot.getName(),
                snapshot.getDatacenterId(),
                datacenterName,
                snapshot.getClusterId(),
                clusterName,
                snapshot.getResourcePoolId(),
                resourcePoolName,
                snapshot.getDatastoreIds(),
                datastoreNames,
                snapshot.getHostId(),
                hostName
            );
            details.setPlacementDataAvailable(snapshot.isPlacementAvailable());
            if (snapshot.hasWarning()) {
                details.setWarning(snapshot.getWarning());
            }

            logger.info("Successfully retrieved location details for VM: {}", vmName);
            return details;
        } catch (Exception e) {
            logger.error("Failed to get VM location details for '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to get VM location details for '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Provides hypervisor version/build information for a host.
     *
     * @param hostName Friendly name of the ESXi host
     * @return HostVersionInfo containing product and hardware details
     */
    @Tool(description = "Get version/build information for an ESXi host. Parameter: hostName (String) - the friendly name of the host")
    public HostVersionInfo getHostVersion(String hostName) {
        logger.info("=== MCP TOOL CALLED: getHostVersion({}) ===", hostName);
        try {
            IdResolutionResult resolution = getHostIdByName(hostName);
            if (resolution.getId() == null) {
                throw new RuntimeException("Host not found: " + hostName);
            }

            if (resolution.hasWarning()) {
                logger.warn("Duplicate host name detected: {}", resolution.getWarning());
            }

            JsonNode hostDetails = vapiClient.hosts().get(resolution.getId());
            String resolvedName = hostDetails.has("name") ? hostDetails.get("name").asText() : hostName;
            String connectionState = hostDetails.has("connection_state") ? hostDetails.get("connection_state").asText() : "unknown";
            String powerState = hostDetails.has("power_state") ? hostDetails.get("power_state").asText() : "unknown";

            String productName = "unknown";
            String productVersion = "unknown";
            String productBuild = "unknown";
            if (hostDetails.has("product")) {
                JsonNode product = hostDetails.get("product");
                if (product.has("name")) {
                    productName = product.get("name").asText();
                }
                if (product.has("version")) {
                    productVersion = product.get("version").asText();
                } else if (product.has("full_version")) {
                    productVersion = product.get("full_version").asText();
                }
                if (product.has("build")) {
                    productBuild = product.get("build").asText();
                }
            } else if (hostDetails.has("version")) {
                productVersion = hostDetails.get("version").asText();
            }

            JsonNode hardware = hostDetails.path("hardware");
            String vendor = hardware.isMissingNode() ? "unknown" : hardware.path("vendor").asText("unknown");
            String model = hardware.isMissingNode() ? "unknown" : hardware.path("model").asText("unknown");

            HostVersionInfo info = new HostVersionInfo(
                resolution.getId(),
                resolvedName,
                productName,
                productVersion,
                productBuild,
                vendor,
                model,
                connectionState,
                powerState
            );

            if (resolution.hasWarning()) {
                info.setWarning(resolution.getWarning());
            }

            logger.info("Successfully retrieved host version info for {}", hostName);
            return info;
        } catch (Exception e) {
            logger.error("Failed to get host version for '{}': {}", hostName, e.getMessage(), e);
            throw new RuntimeException("Failed to get host version for '" + hostName + "': " + e.getMessage(), e);
        }
    }

    /**
     * MCP tool: Reports the resource pool a VM belongs to.
     *
     * @param vmName The friendly name of the virtual machine
     * @return VMResourcePoolInfo containing the pool ID and friendly name
     */
    @Tool(description = "Identify which resource pool a virtual machine belongs to. Parameter: vmName (String) - the friendly name of the virtual machine")
    public VMResourcePoolInfo getVMResourcePool(String vmName) {
        logger.info("=== MCP TOOL CALLED: getVMResourcePool({}) ===", vmName);
        try {
            VmDetailsSnapshot snapshot = fetchVmDetailsSnapshot(vmName);
            String resourcePoolId = snapshot.getResourcePoolId();
            String resourcePoolName = resolveResourcePoolNameById(resourcePoolId);
            String clusterId = snapshot.getClusterId();
            String clusterName = resolveClusterNameById(clusterId);

            VMResourcePoolInfo info = new VMResourcePoolInfo(
                snapshot.getVmId(),
                snapshot.getName(),
                resourcePoolId,
                resourcePoolName,
                clusterId,
                clusterName
            );
            info.setPlacementDataAvailable(snapshot.isPlacementAvailable());
            if (snapshot.hasWarning()) {
                info.setWarning(snapshot.getWarning());
            }

            logger.info("Successfully retrieved resource pool info for VM: {}", vmName);
            return info;
        } catch (Exception e) {
            logger.error("Failed to get VM resource pool for '{}': {}", vmName, e.getMessage(), e);
            throw new RuntimeException("Failed to get VM resource pool for '" + vmName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the VM by name and builds a snapshot of its details from vAPI/REST,
     * with retries and fallbacks to list-based data when detail endpoints fail.
     *
     * @param vmName friendly name of the VM
     * @return populated VmDetailsSnapshot (never null)
     * @throws RuntimeException if VM not found or basic data cannot be determined
     */
    private VmDetailsSnapshot fetchVmDetailsSnapshot(String vmName) {
        IdResolutionResult resolution = getVmIdByName(vmName);
        if (resolution.getId() == null) {
            throw new RuntimeException("VM not found: " + vmName);
        }

        VmDetailsSnapshot snapshot = new VmDetailsSnapshot(resolution.getId(), vmName, resolution.getWarning());
        int retryDelayMs = RETRY_DELAY_MS;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JsonNode rawDetails = vapiClient.vms().get(resolution.getId());
                JsonNode normalizedNode = normalizeVmDetailsNode(rawDetails);
                if (normalizedNode != null) {
                    snapshot.populateFromDetailedNode(normalizedNode);
                    logger.info("Retrieved detailed VM data on attempt {}", attempt);
                    break;
                } else {
                    logger.warn("Detailed VM get response was empty on attempt {}", attempt);
                }
            } catch (Exception e) {
                lastException = e;
                logger.warn("Failed to retrieve detailed VM info on attempt {}: {}", attempt, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrieving VM details", ie);
                    }
                }
            }
        }

        if (snapshot.isBasicDataIncomplete() && resolution.getMetadata() != null) {
            snapshot.populateBasicFromSummaryNode(resolution.getMetadata());
        }

        if (snapshot.isBasicDataIncomplete()) {
            try {
                JsonNode vmsNode = vapiClient.vms().list(null, null);
                for (JsonNode vm : vmsNode) {
                    if (vm.has("vm") && resolution.getId().equals(vm.get("vm").asText())) {
                        snapshot.populateBasicFromSummaryNode(vm);
                        break;
                    }
                }
            } catch (Exception e) {
                lastException = e;
                logger.warn("Failed to retrieve VM list for fallback: {}", e.getMessage());
            }
        }

        if (snapshot.isBasicDataIncomplete()) {
            String errorMessage = "Unable to retrieve configured resources for VM '" + vmName + "'.";
            if (lastException != null) {
                errorMessage += " Last error: " + lastException.getMessage();
            }
            throw new RuntimeException(errorMessage, lastException);
        }

        return snapshot;
    }

    /**
     * Normalizes VM details from vAPI/REST into a single object node: unwraps
     * arrays (first element) and a "value" wrapper so callers see a consistent shape.
     *
     * @param vmDetailsNode raw response from VM get (array or object, possibly with "value")
     * @return single object node or null if empty/missing
     */
    private JsonNode normalizeVmDetailsNode(JsonNode vmDetailsNode) {
        if (vmDetailsNode == null || vmDetailsNode.isNull()) {
            return null;
        }
        JsonNode candidate = vmDetailsNode;
        if (candidate.isArray()) {
            if (candidate.size() == 0) {
                return null;
            }
            candidate = candidate.get(0);
        }
        if (candidate.has("value") && candidate.get("value").isObject()) {
            candidate = candidate.get("value");
        }
        return candidate;
    }

    private String resolveHostNameById(String hostId) {
        if (hostId == null || hostId.isEmpty()) {
            return "";
        }
        try {
            JsonNode hostsNode = vapiClient.hosts().list();
            return resolveInventoryName(hostsNode, "host", hostId, "name");
        } catch (Exception e) {
            logger.debug("Failed to resolve host name for '{}': {}", hostId, e.getMessage());
            return hostId;
        }
    }

    private String resolveDatacenterNameById(String datacenterId) {
        if (datacenterId == null || datacenterId.isEmpty()) {
            return "";
        }
        try {
            JsonNode datacentersNode = vapiClient.datacenters().list();
            return resolveInventoryName(datacentersNode, "datacenter", datacenterId, "name");
        } catch (Exception e) {
            logger.debug("Failed to resolve datacenter name for '{}': {}", datacenterId, e.getMessage());
            return datacenterId;
        }
    }

    private String resolveClusterNameById(String clusterId) {
        if (clusterId == null || clusterId.isEmpty()) {
            return "";
        }
        try {
            JsonNode clustersNode = vapiClient.clusters().list();
            return resolveInventoryName(clustersNode, "cluster", clusterId, "name");
        } catch (Exception e) {
            logger.debug("Failed to resolve cluster name for '{}': {}", clusterId, e.getMessage());
            return clusterId;
        }
    }

    private String resolveResourcePoolNameById(String resourcePoolId) {
        if (resourcePoolId == null || resourcePoolId.isEmpty()) {
            return "";
        }
        try {
            JsonNode clustersNode = vapiClient.clusters().list();
            for (JsonNode cluster : clustersNode) {
                String clusterId = cluster.get("cluster").asText();
                JsonNode pools = vapiClient.resourcePools().list(clusterId);
                for (JsonNode pool : pools) {
                    if (pool.has("resource_pool") && resourcePoolId.equals(pool.get("resource_pool").asText())) {
                        return pool.has("name") ? pool.get("name").asText() : resourcePoolId;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve resource pool name for '{}': {}", resourcePoolId, e.getMessage());
        }
        return resourcePoolId;
    }

    private List<String> resolveDatastoreNames(List<String> datastoreIds) {
        if (datastoreIds == null || datastoreIds.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode datastoresNode = vapiClient.datastores().list();
            List<String> names = new ArrayList<>();
            for (String datastoreId : datastoreIds) {
                names.add(resolveDatastoreNameById(datastoreId, datastoresNode));
            }
            return names;
        } catch (Exception e) {
            logger.debug("Failed to resolve datastore names: {}", e.getMessage());
            return new ArrayList<>(datastoreIds);
        }
    }

    private String resolveDatastoreNameById(String datastoreId, JsonNode datastoresNode) {
        if (datastoresNode == null) {
            return datastoreId;
        }
        return resolveInventoryName(datastoresNode, "datastore", datastoreId, "name");
    }

    /**
     * Finds an item in a list-style JsonNode by ID and returns its name field.
     *
     * @param items array of objects with idKey and nameKey
     * @param idKey field holding the ID (e.g. "host", "datacenter")
     * @param targetId ID to match
     * @param nameKey field holding the display name
     * @return name if found, otherwise targetId
     */
    private String resolveInventoryName(JsonNode items, String idKey, String targetId, String nameKey) {
        if (targetId == null || targetId.isEmpty()) {
            return "";
        }
        if (items != null) {
            for (JsonNode item : items) {
                if (item.has(idKey) && targetId.equals(item.get(idKey).asText())) {
                    return item.has(nameKey) ? item.get(nameKey).asText() : targetId;
                }
            }
        }
        return targetId;
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
        private final JsonNode metadata;
        
        public IdResolutionResult(String id, String warning, JsonNode metadata) {
            this.id = id;
            this.warning = warning;
            this.metadata = metadata;
        }
        
        public String getId() { return id; }
        public String getWarning() { return warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }
        public JsonNode getMetadata() { return metadata; }
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
                return new IdResolutionResult(null, null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d clusters with the same name '%s'. Using the first match.", matchCount, clusterName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning, null);
        } catch (Exception e) {
            logger.error("Failed to get cluster ID for name '{}': {}", clusterName, e.getMessage());
            return new IdResolutionResult(null, null, null);
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
                return new IdResolutionResult(null, null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d resource pools with the same name '%s'. Using the first match.", matchCount, resourcePoolName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning, null);
        } catch (Exception e) {
            logger.error("Failed to get resource pool ID for name '{}': {}", resourcePoolName, e.getMessage());
            return new IdResolutionResult(null, null, null);
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
        int retryDelayMs = RETRY_DELAY_MS;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                logger.info("Attempting to list VMs to find '{}' (attempt {}/{})", vmName, attempt, MAX_RETRIES);
                JsonNode vmsNode = vapiClient.vms().list(null, null);
                String foundId = null;
                int matchCount = 0;
                JsonNode matchedVmNode = null;
                
                for (JsonNode vm : vmsNode) {
                    if (vm.has("name") && vm.get("name").asText().equals(vmName)) {
                        if (foundId == null) {
                            foundId = vm.get("vm").asText();
                            matchedVmNode = vm.deepCopy();
                        }
                        matchCount++;
                    }
                }
                
                if (foundId == null) {
                    logger.info("VM '{}' not found in list", vmName);
                    return new IdResolutionResult(null, null, null);
                }
                
                String warning = null;
                if (matchCount > 1) {
                    warning = String.format("WARNING: Found %d VMs with the same name '%s'. Using the first match.", matchCount, vmName);
                    logger.warn(warning);
                }
                
                logger.info("Successfully found VM '{}' with ID {} on attempt {}", vmName, foundId, attempt);
                return new IdResolutionResult(foundId, warning, matchedVmNode);
            } catch (Exception e) {
                lastException = e;
                logger.warn("Failed to get VM ID for name '{}' on attempt {}: {}", vmName, attempt, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("Interrupted during retry for VM '{}'", vmName);
                        return new IdResolutionResult(null, null, null);
                    }
                }
            }
        }
        
        logger.error("Failed to get VM ID for name '{}' after {} attempts", vmName, MAX_RETRIES);
        if (lastException != null) {
            logger.error("Last exception: {}", lastException.getMessage(), lastException);
        }
        return new IdResolutionResult(null, null, null);
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
                return new IdResolutionResult(null, null, null);
            }
            
            String warning = null;
            if (matchCount > 1) {
                warning = String.format("WARNING: Found %d hosts with the same name '%s'. Using the first match.", matchCount, hostName);
                logger.warn(warning);
            }
            
            return new IdResolutionResult(foundId, warning, null);
        } catch (Exception e) {
            logger.error("Failed to get host ID for name '{}': {}", hostName, e.getMessage());
            return new IdResolutionResult(null, null, null);
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
     * Data structure representing configured resources for a virtual machine.
     */
    public static class VMResourceSummary {
        private final String id;
        private final String name;
        private final String powerState;
        private final String guestOs;
        private final int cpuCount;
        private final long memoryBytes;
        private final long memoryMB;
        private String warning;

        public VMResourceSummary(String id, String name, String powerState, String guestOs, int cpuCount, long memoryBytes) {
            this.id = id;
            this.name = name;
            this.powerState = powerState;
            this.guestOs = guestOs;
            this.cpuCount = cpuCount;
            this.memoryBytes = memoryBytes;
            this.memoryMB = memoryBytes > 0 ? memoryBytes / (1024 * 1024) : -1;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getPowerState() { return powerState; }
        public String getGuestOs() { return guestOs; }
        public int getCpuCount() { return cpuCount; }
        public long getMemoryBytes() { return memoryBytes; }
        public long getMemoryMB() { return memoryMB; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }

        @Override
        public String toString() {
            String base = "VMResourceSummary{id='" + id + "', name='" + name +
                   "', powerState='" + powerState + "', guestOs='" + guestOs +
                   "', cpuCount=" + cpuCount + ", memoryBytes=" + memoryBytes + "}";
            if (hasWarning()) {
                base += ", warning='" + warning + "'";
            }
            return base;
        }
    }

    /**
     * Data structure representing hosting information for a virtual machine.
     */
    public static class VMLocationDetails {
        private final String id;
        private final String name;
        private final String datacenterId;
        private final String datacenterName;
        private final String clusterId;
        private final String clusterName;
        private final String resourcePoolId;
        private final String resourcePoolName;
        private final List<String> datastoreIds;
        private final List<String> datastoreNames;
        private final String hostId;
        private final String hostName;
        private boolean placementDataAvailable;
        private String warning;

        public VMLocationDetails(
            String id,
            String name,
            String datacenterId,
            String datacenterName,
            String clusterId,
            String clusterName,
            String resourcePoolId,
            String resourcePoolName,
            List<String> datastoreIds,
            List<String> datastoreNames,
            String hostId,
            String hostName
        ) {
            this.id = id;
            this.name = name;
            this.datacenterId = datacenterId;
            this.datacenterName = datacenterName;
            this.clusterId = clusterId;
            this.clusterName = clusterName;
            this.resourcePoolId = resourcePoolId;
            this.resourcePoolName = resourcePoolName;
            this.datastoreIds = datastoreIds == null ? List.of() : new ArrayList<>(datastoreIds);
            this.datastoreNames = datastoreNames == null ? List.of() : new ArrayList<>(datastoreNames);
            this.hostId = hostId;
            this.hostName = hostName;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDatacenterId() { return datacenterId; }
        public String getDatacenterName() { return datacenterName; }
        public String getClusterId() { return clusterId; }
        public String getClusterName() { return clusterName; }
        public String getResourcePoolId() { return resourcePoolId; }
        public String getResourcePoolName() { return resourcePoolName; }
        public List<String> getDatastoreIds() { return new ArrayList<>(datastoreIds); }
        public List<String> getDatastoreNames() { return new ArrayList<>(datastoreNames); }
        public String getHostId() { return hostId; }
        public String getHostName() { return hostName; }
        public boolean isPlacementDataAvailable() { return placementDataAvailable; }
        public void setPlacementDataAvailable(boolean placementDataAvailable) { this.placementDataAvailable = placementDataAvailable; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }

        @Override
        public String toString() {
            String base = "VMLocationDetails{id='" + id + "', name='" + name +
                   "', datacenterId='" + datacenterId + "', datacenterName='" + datacenterName +
                   "', clusterId='" + clusterId + "', clusterName='" + clusterName +
                   "', resourcePoolId='" + resourcePoolId + "', resourcePoolName='" + resourcePoolName +
                   "', datastoreIds=" + datastoreIds + ", datastoreNames=" + datastoreNames +
                   ", hostId='" + hostId + "', hostName='" + hostName +
                   "', placementDataAvailable=" + placementDataAvailable + "}";
            if (hasWarning()) {
                base += ", warning='" + warning + "'";
            }
            return base;
        }
    }

    /**
     * Data structure representing host version/build information.
     */
    public static class HostVersionInfo {
        private final String hostId;
        private final String hostName;
        private final String productName;
        private final String productVersion;
        private final String productBuild;
        private final String hardwareVendor;
        private final String hardwareModel;
        private final String connectionState;
        private final String powerState;
        private String warning;

        public HostVersionInfo(String hostId, String hostName, String productName, String productVersion, String productBuild,
                               String hardwareVendor, String hardwareModel, String connectionState, String powerState) {
            this.hostId = hostId;
            this.hostName = hostName;
            this.productName = productName;
            this.productVersion = productVersion;
            this.productBuild = productBuild;
            this.hardwareVendor = hardwareVendor;
            this.hardwareModel = hardwareModel;
            this.connectionState = connectionState;
            this.powerState = powerState;
        }

        public String getHostId() { return hostId; }
        public String getHostName() { return hostName; }
        public String getProductName() { return productName; }
        public String getProductVersion() { return productVersion; }
        public String getProductBuild() { return productBuild; }
        public String getHardwareVendor() { return hardwareVendor; }
        public String getHardwareModel() { return hardwareModel; }
        public String getConnectionState() { return connectionState; }
        public String getPowerState() { return powerState; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }

        @Override
        public String toString() {
            String base = "HostVersionInfo{hostId='" + hostId + "', hostName='" + hostName +
                "', productName='" + productName + "', productVersion='" + productVersion +
                "', productBuild='" + productBuild + "', hardwareVendor='" + hardwareVendor +
                "', hardwareModel='" + hardwareModel + "', connectionState='" + connectionState +
                "', powerState='" + powerState + "'}";
            if (hasWarning()) {
                base += ", warning='" + warning + "'";
            }
            return base;
        }
    }

    /**
     * Internal snapshot that consolidates VM information from different endpoints.
     * Populated from detailed get responses (with placement) and/or list/summary responses.
     * Tracks basic config (name, power, guest OS, CPU, memory) and placement (host, cluster,
     * datacenter, resource pool, datastores) with defensive handling of varying JSON shapes.
     */
    private static class VmDetailsSnapshot {
        private final String vmId;
        private final String warning;
        private String name;
        private String powerState = "unknown";
        private String guestOs = "unknown";
        private int cpuCount = -1;
        private long memoryBytes = -1;
        private String hostId = "";
        private String clusterId = "";
        private String datacenterId = "";
        private String resourcePoolId = "";
        private final List<String> datastoreIds = new ArrayList<>();
        private boolean placementAvailable = false;

        VmDetailsSnapshot(String vmId, String defaultName, String warning) {
            this.vmId = vmId;
            this.name = defaultName;
            this.warning = warning;
        }

        void populateFromDetailedNode(JsonNode node) {
            if (node == null) {
                return;
            }
            updateBasicInfo(node);
            updatePlacementInfo(node);
        }

        void populateBasicFromSummaryNode(JsonNode node) {
            if (node == null) {
                return;
            }
            updateBasicInfo(node);
        }

        private void updateBasicInfo(JsonNode node) {
            if (node.has("name")) {
                this.name = node.get("name").asText();
            }
            if (node.has("power_state")) {
                this.powerState = node.get("power_state").asText();
            }
            if (node.has("guest_OS")) {
                this.guestOs = node.get("guest_OS").asText();
            } else if (node.has("guest_os")) {
                this.guestOs = node.get("guest_os").asText();
            }
            if (node.has("cpu") && node.get("cpu").isObject() && node.get("cpu").has("count")) {
                this.cpuCount = node.get("cpu").get("count").asInt();
            } else if (node.has("cpu_count")) {
                this.cpuCount = node.get("cpu_count").asInt();
            }
            if (node.has("memory") && node.get("memory").isObject() && node.get("memory").has("size_MiB")) {
                this.memoryBytes = node.get("memory").get("size_MiB").asLong() * 1024 * 1024;
            } else if (node.has("memory_size_MiB")) {
                this.memoryBytes = node.get("memory_size_MiB").asLong() * 1024 * 1024;
            }
        }

        private void updatePlacementInfo(JsonNode node) {
            JsonNode placementNode = node.has("placement") ? node.get("placement") : null;
            boolean fieldFound = false;

            fieldFound |= assignIfPresent(placementNode, node, "host", value -> this.hostId = value);
            fieldFound |= assignIfPresent(placementNode, node, "cluster", value -> this.clusterId = value);
            fieldFound |= assignIfPresent(placementNode, node, "datacenter", value -> this.datacenterId = value);
            fieldFound |= assignIfPresent(placementNode, node, "resource_pool", value -> this.resourcePoolId = value);

            fieldFound |= addDatastoresFromNode(placementNode != null ? placementNode.get("datastore") : null);
            fieldFound |= addDatastoresFromNode(placementNode != null ? placementNode.get("datastores") : null);
            fieldFound |= addDatastoresFromNode(node.get("datastore"));
            fieldFound |= addDatastoresFromNode(node.get("datastores"));

            if (fieldFound) {
                this.placementAvailable = true;
            }
        }

        private boolean assignIfPresent(JsonNode preferred, JsonNode fallback, String key, Consumer<String> setter) {
            JsonNode candidate = preferred != null && preferred.has(key) ? preferred.get(key)
                : fallback != null && fallback.has(key) ? fallback.get(key) : null;
            if (candidate == null || candidate.isNull()) {
                return false;
            }
            String value = extractTextValue(candidate);
            if (value.isEmpty()) {
                return false;
            }
            setter.accept(value);
            return true;
        }

        private boolean addDatastoresFromNode(JsonNode datastoreNode) {
            if (datastoreNode == null || datastoreNode.isNull()) {
                return false;
            }
            boolean added = false;
            if (datastoreNode.isArray()) {
                for (JsonNode ds : datastoreNode) {
                    added |= addDatastoreIdInternal(extractId(ds));
                }
            } else {
                added = addDatastoreIdInternal(extractId(datastoreNode));
            }
            return added;
        }

        private String extractId(JsonNode node) {
            return extractTextValue(node);
        }

        private boolean addDatastoreIdInternal(String datastoreId) {
            if (datastoreId == null || datastoreId.isEmpty()) {
                return false;
            }
            if (!datastoreIds.contains(datastoreId)) {
                datastoreIds.add(datastoreId);
                return true;
            }
            return false;
        }

        public String getVmId() { return vmId; }
        public String getWarning() { return warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }
        public String getName() { return name; }
        public String getPowerState() { return powerState; }
        public String getGuestOs() { return guestOs; }
        public int getCpuCount() { return cpuCount; }
        public long getMemoryBytes() { return memoryBytes; }
        public String getHostId() { return hostId; }
        public String getClusterId() { return clusterId; }
        public String getDatacenterId() { return datacenterId; }
        public String getResourcePoolId() { return resourcePoolId; }
        public List<String> getDatastoreIds() { return new ArrayList<>(datastoreIds); }
        public boolean isPlacementAvailable() { return placementAvailable; }
        public boolean isBasicDataIncomplete() { return cpuCount < 0 && memoryBytes < 0; }
    }

    /**
     * Extracts a string identifier from a JsonNode that may be a primitive, or an object
     * with "value", "id", "datastore", "host", "cluster", "resource_pool", or "datacenter".
     * Used when parsing vAPI placement and reference fields.
     *
     * @param node JSON node (possibly nested)
     * @return non-null string (possibly empty)
     */
    private static String extractTextValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.has("value")) {
            return extractTextValue(node.get("value"));
        }
        if (node.has("id")) {
            return extractTextValue(node.get("id"));
        }
        if (node.has("datastore")) {
            return extractTextValue(node.get("datastore"));
        }
        if (node.has("host")) {
            return extractTextValue(node.get("host"));
        }
        if (node.has("cluster")) {
            return extractTextValue(node.get("cluster"));
        }
        if (node.has("resource_pool")) {
            return extractTextValue(node.get("resource_pool"));
        }
        if (node.has("datacenter")) {
            return extractTextValue(node.get("datacenter"));
        }
        return "";
    }

    /**
     * Data structure describing the resource pool membership of a VM.
     */
    public static class VMResourcePoolInfo {
        private final String vmId;
        private final String vmName;
        private final String resourcePoolId;
        private final String resourcePoolName;
        private final String clusterId;
        private final String clusterName;
        private boolean placementDataAvailable;
        private String warning;

        public VMResourcePoolInfo(String vmId, String vmName, String resourcePoolId, String resourcePoolName,
                                  String clusterId, String clusterName) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.resourcePoolId = resourcePoolId;
            this.resourcePoolName = resourcePoolName;
            this.clusterId = clusterId;
            this.clusterName = clusterName;
        }

        public String getVmId() { return vmId; }
        public String getVmName() { return vmName; }
        public String getResourcePoolId() { return resourcePoolId; }
        public String getResourcePoolName() { return resourcePoolName; }
        public String getClusterId() { return clusterId; }
        public String getClusterName() { return clusterName; }
        public boolean isPlacementDataAvailable() { return placementDataAvailable; }
        public void setPlacementDataAvailable(boolean placementDataAvailable) { this.placementDataAvailable = placementDataAvailable; }
        public String getWarning() { return warning; }
        public void setWarning(String warning) { this.warning = warning; }
        public boolean hasWarning() { return warning != null && !warning.isEmpty(); }

        @Override
        public String toString() {
            String base = "VMResourcePoolInfo{vmId='" + vmId + "', vmName='" + vmName +
                "', resourcePoolId='" + resourcePoolId + "', resourcePoolName='" + resourcePoolName +
                "', clusterId='" + clusterId + "', clusterName='" + clusterName +
                "', placementDataAvailable=" + placementDataAvailable + "}";
            if (hasWarning()) {
                base += ", warning='" + warning + "'";
            }
            return base;
        }
    }
} 