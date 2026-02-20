# vCenter MCP Server

A Spring Boot-based Model Context Protocol (MCP) server that provides read-only access to VMware vCenter Server. This server exposes vCenter operations as MCP tools, allowing AI assistants to interact with vCenter infrastructure.

## Features

- **vCenter Integration**: Connect to VMware vCenter Server using vAPI
- **MCP Tools**: Expose vCenter operations as MCP tools for AI assistants
- **Cloud Foundry Ready**: Deployable to Cloud Foundry with service binding
- **Tanzu Marketplace (10.3)**: Publish to the platform marketplace with `cf publish-service` and `service.yml` (see [Publishing to the Tanzu Platform Marketplace (10.3)](#publishing-to-the-tanzu-platform-marketplace-103))
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
6. **`getVMResourceSummary(String vmName)`** - Get configured resources (CPU, RAM, guest OS) and power status for a VM
7. **`getVMLocationDetails(String vmName)`** - Get hosting information for a VM (datacenter, cluster, resource pool, datastore, host)
8. **`getVMResourcePool(String vmName)`** - Identify which resource pool and cluster a VM belongs to
9. **`getHostVersion(String hostName)`** - Retrieve ESXi host version/build, vendor, and model information

### Extended Operations
10. **`listDataCenters()`** - List all datacenters in the vCenter
11. **`getVCenterVersion()`** - Get vCenter version information
12. **`listDataStoresWithCapacity()`** - List all datastores with capacity and consumption information
13. **`listClusterResources()`** - Get detailed resource information for all clusters including CPU and RAM
14. **`listHosts()`** - List all hosts in the vCenter with their connection and power state

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

1. **Target your foundation and select org/space**
   ```bash
   cf api https://api.<your-system-domain>
   cf login
   cf target -o <org> -s <space>
   ```

2. **Create the vCenter user-provided service** (so the app can connect to vCenter)
   ```bash
   cf create-user-provided-service vcenter-service \
     -p '{"host":"vcenter.example.com","port":443,"username":"admin@vsphere.local","password":"<password>","insecure":true}'
   ```
   Replace `vcenter.example.com`, `admin@vsphere.local`, and `<password>` with your vCenter details.

3. **Build the application**
   ```bash
   mvn clean package -DskipTests
   ```

4. **Push the app** (this creates the app on Cloud Foundry)
   ```bash
   cf push -f manifest.yml
   ```
   The app name is set in `manifest.yml` (e.g. `vcenter-mcp-server`). The push creates the app, stages the buildpack, and binds the `vcenter-service` instance listed under `services:` in the manifest.

5. **Restage so the binding is applied** (if you created the user-provided service after the first push)
   ```bash
   cf restage vcenter-mcp-server
   ```

6. **Confirm the app is running**
   ```bash
   cf apps
   cf logs vcenter-mcp-server --recent
   ```

   For **publishing this app to the Tanzu Platform marketplace**, the app must be created on the **private network** (no public route). See [Publishing to the Tanzu Platform Marketplace (10.3)](#publishing-to-the-tanzu-platform-marketplace-103) and use `cf push -f manifest.yml --no-route` when following that procedure.

## Publishing to the Tanzu Platform Marketplace (10.3)

On **Tanzu Platform 10.3**, the vCenter MCP server can be published to the platform marketplace so other teams can consume it as a first-class service using `cf create-service` and `cf bind-service`. Publishing uses the **`cf publish-service`** command and the `service.yml` definition in this repository.

**Important:** The app must be created and run on the **private network** (no public route). That way the MCP server is only reachable by apps that bind the service, via the platform’s internal networking.

### What’s in `service.yml`

The `service.yml` file defines the marketplace offering:

- **Offering identity**: `name` (`vcenter-mcp-service`), `description`, and `metadata` (display name, long description, provider, documentation URL).
- **Tags**: e.g. `mcp`, `ai-tools` so users can find the offering in the catalog.
- **Plans**: e.g. a `standard` plan (free) with description and bullets.

This format is used by Tanzu Platform 10.3 when you run `cf publish-service`.

### Prerequisites

- Tanzu Platform **10.3** (or a compatible release that supports `cf publish-service`).
- CF CLI installed, targeted at your foundation, and logged in.
- vCenter host, credentials, and port (e.g. 443) for the user-provided service.

### Full procedure: Create the app (private network), push, then publish

Follow these steps in order. The app is created with **no public route** so it runs only on the private network.

1. **Target the foundation and select org and space**
   ```bash
   cf api https://api.<your-system-domain>
   cf login
   cf target -o <org> -s <space>
   ```

2. **Create the vCenter user-provided service**
   The MCP server needs vCenter credentials at runtime. Create a user-provided service with your vCenter details:
   ```bash
   cf create-user-provided-service vcenter-service \
     -p '{"host":"<vcenter-host>","port":443,"username":"<vcenter-user>","password":"<vcenter-password>","insecure":true}'
   ```
   Replace `<vcenter-host>`, `<vcenter-user>`, and `<vcenter-password>` with your vCenter server hostname (or IP), username, and password.

3. **Build the application**
   ```bash
   mvn clean package -DskipTests
   ```

4. **Push the app so it is created on the private network**
   Push the app **with no public route** so it runs only on the private network and is reachable by other apps via the platform’s internal service binding:
   ```bash
   cf push -f manifest.yml --no-route
   ```
   This command:
   - Creates the app (name from `manifest.yml`, e.g. `vcenter-mcp-server`).
   - Stages and runs the app with no HTTP route (private network only).
   - Binds the `vcenter-service` instance listed under `services:` in the manifest.
   Use the same app name in the next steps (e.g. `vcenter-mcp-server`).

5. **Restage the app** (so the service binding and env are applied)
   ```bash
   cf restage vcenter-mcp-server
   ```

6. **Confirm the app is running**
   ```bash
   cf app vcenter-mcp-server
   cf logs vcenter-mcp-server --recent
   ```
   The app should show no routes and be running.

7. **Publish the service to the marketplace**
   From the directory that contains `service.yml`, run:
   ```bash
   cf publish-service vcenter-mcp-server -c service.yml
   ```
   This registers the running app as the **vcenter-mcp-service** offering in the Tanzu marketplace using the plans and metadata from `service.yml`.

8. **Enable service access** (so orgs/spaces can see the offering)
   ```bash
   cf enable-service-access vcenter-mcp-service -p standard
   ```
   To enable all plans for the offering:
   ```bash
   cf enable-service-access vcenter-mcp-service
   ```

9. **Verify the offering in the marketplace**
   ```bash
   cf marketplace
   ```
   You should see **vcenter-mcp-service** (or the display name **vCenter AI Tools**) and the **standard** plan.

After this, the offering appears in the Tanzu Platform marketplace (e.g. in Apps Manager). Users create and bind the service to their apps; the platform provides the MCP server endpoint over the private network via the binding.

### Consuming the service after it’s published

Developers can use the service from the marketplace with standard CF commands:

1. **Create a service instance**

   ```bash
   cf create-service vcenter-mcp-service standard my-vcenter-mcp
   ```

2. **Bind the instance to an application**

   ```bash
   cf bind-service <app-name> my-vcenter-mcp
   cf restage <app-name>
   ```

3. The app receives binding details (e.g. in `VCAP_SERVICES`), including the MCP server endpoint and any credentials the platform injects, and can connect to the vCenter MCP tools.

### Updating the marketplace listing

**To change the description, plans, or metadata** (what users see in the marketplace):

1. Edit `service.yml` (e.g. `description`, `metadata.displayName`, `metadata.longDescription`, `plans`).
2. Run `cf publish-service` again with the updated file:
   ```bash
   cf publish-service vcenter-mcp-server -c service.yml
   ```
3. If your platform requires it, run `cf enable-service-access` again for the offering or plan.

**To redeploy the app** (e.g. after a new build) while keeping it on the private network:

1. Build and push again with no route:
   ```bash
   mvn clean package -DskipTests
   cf push -f manifest.yml --no-route
   cf restage vcenter-mcp-server
   ```
2. Re-run `cf publish-service vcenter-mcp-server -c service.yml` if the platform requires re-publishing after app updates.

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