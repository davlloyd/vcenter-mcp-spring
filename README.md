# vCenter MCP Server

A Spring Boot-based Model Context Protocol (MCP) server that provides read-only access to VMware vCenter Server. This server exposes vCenter operations as MCP tools, allowing AI assistants to interact with vCenter infrastructure.

## Features

- **vCenter Integration**: Connect to VMware vCenter Server using vAPI
- **MCP Tools**: Expose vCenter operations as MCP tools for AI assistants
- **Cloud Foundry Ready**: Deployable to Cloud Foundry with service binding
- **VMware SDK-Style**: Follows VMware SDK patterns for consistency
- **VM Management**: Read operations for inventory and write operations for VM power and migration control

## MCP Tools

The server provides the following MCP tools:

### Basic Operations
1. **`getClusters()`** - List all clusters in vCenter
2. **`getResourcePoolsInCluster(String clusterName)`** - List resource pools in a specific cluster
3. **`getVMsInCluster(String clusterName)`** - List VMs in a specific cluster
4. **`getVMsInResourcePool(String resourcePoolName)`** - List VMs in a specific resource pool
5. **`listAllVirtualMachines()`** - List ALL VMs across the entire vCenter (no parameters required)
6. **`getVMDetails(String vmName)`** - Get detailed information about a specific virtual machine

### Extended Operations
7. **`listDataCenters()`** - List all datacenters in the vCenter
8. **`getVCenterVersion()`** - Get vCenter version information
9. **`listDataStoresWithCapacity()`** - List all datastores with capacity and consumption information
10. **`listClusterResources()`** - Get detailed resource information for all clusters including CPU and RAM
11. **`listHosts()`** - List all hosts in the vCenter with their connection and power state

### VM Power Management (Write Operations)
⚠️ **Note**: These operations require write permissions to vCenter.

12. **`powerOnVM(String vmName)`** - Power on a virtual machine
13. **`powerOffVM(String vmName)`** - Power off a virtual machine (hard power off)
14. **`resetVM(String vmName)`** - Reset a virtual machine (hard reset)
15. **`restartVM(String vmName)`** - Restart the guest OS of a virtual machine (soft restart)
16. **`shutdownVM(String vmName)`** - Shut down the guest OS of a virtual machine (soft shutdown)
17. **`migrateVM(String vmName, String targetHostName)`** - Migrate a virtual machine to a different host

## Architecture

### Efficient Configuration Management

The application uses **Spring Boot's `@ConfigurationProperties`** for efficient configuration management:

- **`VCenterConfig`**: Spring Boot configuration properties class with `@ConfigurationProperties(prefix = "vcenter")`
- **`VCenterConfigProcessor`**: Processes VCAP_SERVICES and updates configuration at runtime
- **Automatic Binding**: Environment variables and properties are automatically bound to the configuration class

### VMware SDK-Style vAPI Implementation

The application uses a **VMware SDK-style vAPI approach** with the following components:

- **`VapiClient`**: Core vAPI client that follows VMware SDK patterns with service interfaces
- **`VCenterService`**: Service layer that exposes vAPI operations as MCP tools
- **`WebClientConfig`**: Configuration for SSL and HTTP client setup

### vAPI Service Interfaces

The implementation follows **VMware SDK patterns** with service interfaces:

```java
// VMware SDK-style usage
vapiClient.clusters().list()           // List all clusters
vapiClient.resourcePools().list(clusterId)  // List resource pools in cluster
vapiClient.vms().list(clusterId, resourcePoolId)  // List VMs with filters
```

**vAPI Services:**
- **`ClusterService`**: `clusters().list()`
- **`ResourcePoolService`**: `resourcePools().list(clusterId)`
- **`VmService`**: `vms().list(clusterId, resourcePoolId)`
- **`DataCenterService`**: `datacenters().list()`
- **`HostService`**: `hosts().list()`
- **`DatastoreService`**: `datastores().list()` and `datastores().get(datastoreId)`
- **`ApplianceService`**: `appliance().getVersion()`

### vAPI Protocol Structure

The implementation uses the **vAPI protocol** which provides:

- **Service-based architecture**: Following VMware SDK patterns
- **Method invocation**: `list()` operations on vAPI services
- **Structured requests/responses**: JSON-based protocol with proper error handling
- **Session management**: Secure session-based authentication

### vAPI Endpoints

- **Session**: `/api/session` - Create vAPI session
- **Clusters**: `/api/vcenter/cluster` - Cluster operations
- **Resource Pools**: `/api/vcenter/resource-pool` - Resource pool operations
- **Virtual Machines**: `/api/vcenter/vm` - VM operations
- **Datacenters**: `/api/vcenter/datacenter` - Datacenter operations
- **Hosts**: `/api/vcenter/host` - Host operations
- **Datastores**: `/api/vcenter/datastore` - Datastore operations
- **Version**: `/api/appliance/system/version` - System version information

## Configuration

### Environment Variables

The application supports configuration via environment variables:

```bash
VCENTER_HOST=vcenter.example.com
VCENTER_PORT=443
VCENTER_USERNAME=admin@vsphere.local
VCENTER_PASSWORD=password
VCENTER_INSECURE=true
```

### Cloud Foundry Service Binding

For Cloud Foundry deployment, create a user-provided service with vCenter credentials:

```bash
cf create-user-provided-service vcenter-service \
  -p '{"host":"vcenter.example.com","port":443,"username":"admin@vsphere.local","password":"password","insecure":true}'
```

Then bind the service to your application:

```bash
cf bind-service vcenter-mcp-server vcenter-service
```

### Configuration Properties

The application uses Spring Boot's `@ConfigurationProperties` for efficient configuration:

```properties
# vCenter Configuration (can be overridden by environment variables or VCAP_SERVICES)
vcenter.host=${VCENTER_HOST:}
vcenter.port=${VCENTER_PORT:443}
vcenter.username=${VCENTER_USERNAME:}
vcenter.password=${VCENTER_PASSWORD:}
vcenter.insecure=${VCENTER_INSECURE:true}
```

## Deployment

### Local Development

1. Set environment variables for vCenter connection
2. Run the application: `mvn spring-boot:run`

### Cloud Foundry Deployment

1. Build the application: `mvn clean package`
2. Deploy to Cloud Foundry: `cf push`
3. Bind vCenter service: `cf bind-service vcenter-mcp-server vcenter-service`

## Authentication

The application uses **vAPI session-based authentication**:

1. **Session Creation**: POST credentials to `/api/session`
2. **Session Token**: Uses `vmware-api-session-id` header for subsequent requests
3. **Automatic Renewal**: Session tokens are cached and reused

## Logging

The application provides detailed logging for debugging:

- **vAPI Calls**: All vAPI method invocations are logged
- **Configuration**: Configuration loading and VCAP_SERVICES processing
- **SSL/TLS**: SSL context configuration and certificate handling
- **Session Management**: Session creation and token management

## Security

- **SSL/TLS**: Configurable SSL validation (insecure mode for development)
- **Credential Management**: Credentials managed via Cloud Foundry service binding
- **Session Security**: Secure session-based authentication with vAPI
- **Write Operations**: VM power and migration operations require appropriate vCenter permissions

## Development

### Building

```bash
mvn clean package
```

### Testing

```bash
mvn test
```

### Running Locally

```bash
mvn spring-boot:run
```

## Troubleshooting

### Common Issues

1. **SSL Certificate Errors**: Set `VCENTER_INSECURE=true` for development
2. **Authentication Failures**: Verify vCenter credentials in service binding
3. **Connection Issues**: Check vCenter host and port configuration
4. **VCAP_SERVICES**: Ensure service binding is properly configured

### Logs

Check application logs for detailed information:

```bash
cf logs vcenter-mcp-server --recent
```

Look for:
- Configuration processing messages
- vAPI session creation
- vAPI method invocations
- Error messages and stack traces