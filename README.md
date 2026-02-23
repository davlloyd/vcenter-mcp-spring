# vCenter MCP Server

Spring Boot MCP server that exposes VMware vCenter operations as tools for AI assistants. Connects via vAPI; supports inventory, VM details, and VM power/migration (with write permissions).

## Features

- **vCenter vAPI** – Session-based auth, VMware SDK-style services
- **MCP tools** – Clusters, resource pools, VMs, datacenters, hosts, datastores, version; VM power and migration
- **Cloud Foundry** – Deploy with service binding; optional [Tanzu Marketplace](#marketplace-tanzu-platform-103) publishing

## MCP Tools

| Category | Tools |
|----------|--------|
| **Inventory** | `getClusters`, `getResourcePoolsInCluster`, `getVMsInCluster`, `getVMsInResourcePool`, `listAllVirtualMachines` |
| **VM details** | `getVMResourceSummary`, `getVMLocationDetails`, `getVMResourcePool`, `getHostVersion` |
| **Extended** | `listDataCenters`, `getVCenterVersion`, `listDataStoresWithCapacity`, `listClusterResources`, `listHosts` |
| **Power / migration** | `powerOnVM`, `powerOffVM`, `resetVM`, `restartVM`, `shutdownVM`, `migrateVM` (requires vCenter write) |

All tools accept friendly names (e.g. VM name); duplicate-name warnings are returned when applicable.

## Configuration

- **Env vars**: `VCENTER_HOST`, `VCENTER_PORT`, `VCENTER_USERNAME`, `VCENTER_PASSWORD`, `VCENTER_INSECURE`
- **CF**: Use a user-provided service with keys `host`, `port`, `username`, `password`, `insecure` (see [Deployment](#deployment)).
- **Priority**: VCAP_SERVICES (from binding) overrides env and `application.properties`.

---

## Deployment

How to run the app (local or Cloud Foundry). For publishing to the Tanzu marketplace, see [Marketplace](#marketplace-tanzu-platform-103).

### Local

```bash
export VCENTER_HOST=vcenter.example.com VCENTER_USERNAME=user VCENTER_PASSWORD=pass  # etc.
mvn spring-boot:run
```

### Cloud Foundry

App is deployed with **no public route** and a **private route** on `apps.internal` so it is reachable only on the internal network.

1. **Target**  
   `cf api https://api.<system-domain>` → `cf login` → `cf target -o <org> -s <space>`

2. **vCenter credentials (user-provided service)**  
   ```bash
   cf create-user-provided-service vcenter-service \
     -p '{"host":"<host>","port":443,"username":"<user>","password":"<password>","insecure":true}'
   ```

3. **Build and push (no public route)**  
   ```bash
   mvn clean package -DskipTests
   cf push -f manifest.yml --no-route
   ```

4. **Private route** (so other apps can reach it on the internal network)  
   ```bash
   cf map-route vcenter-mcp-server apps.internal --hostname vcenter-mcp-server
   ```

5. **Restage and verify**  
   ```bash
   cf restage vcenter-mcp-server
   cf app vcenter-mcp-server && cf logs vcenter-mcp-server --recent
   ```

App is reachable at `vcenter-mcp-server.apps.internal`; no public URL.

---

## Marketplace (Tanzu Platform 10.3)

How to **publish** the deployed app as a marketplace offering, **consume** it, and **update** the listing. Requires the app to be [deployed](#deployment) first (private network, no public route).

### Publish the offering

1. From the repo (with `service.yml` present):  
   ```bash
   cf publish-service vcenter-mcp-server -f service.yml
   ```
2. Enable access:  
   ```bash
   cf enable-service-access vcenter-mcp-service -p standard
   ```
3. Confirm:  
   ```bash
   cf marketplace
   ```
   You should see **vcenter-mcp-service** (display name: vCenter AI Tools) and the **standard** plan.

### Consume the service

```bash
cf create-service vcenter-mcp-service standard my-vcenter-mcp
cf bind-service <app-name> my-vcenter-mcp
cf restage <app-name>
```

The bound app gets connection details (e.g. in `VCAP_SERVICES`) and can use the vCenter MCP tools over the private network.

### Update the listing

- **Change description/plans/metadata**: Edit `service.yml`, then run  
  `cf publish-service vcenter-mcp-server -f service.yml` again (and `cf enable-service-access` if needed).
- **Redeploy the app** (new build): Follow [Deployment](#deployment) (build, push `--no-route`, map-route, restage), then run `cf publish-service vcenter-mcp-server -f service.yml` again if your platform requires it.

---

## Architecture (concise)

- **VCenterConfig** / **VCenterConfigProcessor** – `vcenter.*` properties and VCAP_SERVICES binding.
- **VapiClient** – vAPI session, WebClient, service facades (clusters, resourcePools, vms, datacenters, hosts, datastores, appliance).
- **VCenterService** – MCP `@Tool` methods; name→ID resolution and duplicate-name warnings.
- **Endpoints**: `/api/session`, `/api/vcenter/{cluster|resource-pool|vm|datacenter|host|datastore}`, `/api/appliance/system/version`.

## Security

- Credentials via env or CF user-provided service binding.
- SSL: `VCENTER_INSECURE` for dev (skip verification).
- VM power and migration require vCenter write permissions.

## Development

```bash
mvn clean package
mvn test
mvn spring-boot:run
```

## Troubleshooting

- **SSL errors** – Set `VCENTER_INSECURE=true` for dev.
- **Auth/connection** – Check vCenter host, port, and credentials in binding or env.
- **Logs** – `cf logs vcenter-mcp-server --recent` (vAPI calls, config, session).
