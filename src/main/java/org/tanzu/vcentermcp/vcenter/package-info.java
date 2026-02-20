/**
 * vCenter integration layer for the MCP server.
 *
 * <p>This package contains:
 * <ul>
 *   <li>{@link org.tanzu.vcentermcp.vcenter.VapiClient} – low-level client for vCenter vAPI/REST (session, HTTP, JSON).</li>
 *   <li>{@link org.tanzu.vcentermcp.vcenter.VCenterService} – MCP tool implementations that use the client and expose vCenter operations by friendly name.</li>
 * </ul>
 *
 * <p>All tools accept user-friendly names (e.g. VM name, cluster name) and resolve them to vCenter IDs internally.
 * Duplicate-name warnings are returned when multiple entities share the same name.
 */
package org.tanzu.vcentermcp.vcenter;
