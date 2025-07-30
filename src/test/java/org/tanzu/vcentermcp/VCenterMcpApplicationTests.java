package org.tanzu.vcentermcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.tanzu.vcentermcp.vcenter.VCenterService;
import org.tanzu.vcentermcp.vcenter.VCenterService.ClusterInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "vcenter.host=test-vcenter.example.com",
    "vcenter.port=443",
    "vcenter.username=test-user",
    "vcenter.password=test-password",
    "vcenter.insecure=true"
})
class VCenterMcpApplicationTests {

    @Autowired
    private VCenterService vCenterService;

    @Test
    void contextLoads() {
        // This test verifies that the Spring context loads successfully
    }

    @Test
    void testGetClustersMethod() {
        // This test will help diagnose the issue with getClusters not returning values
        try {
            System.out.println("=== Testing getClusters method ===");
            List<ClusterInfo> clusters = vCenterService.getClusters();
            System.out.println("getClusters returned: " + clusters);
            System.out.println("Number of clusters: " + (clusters != null ? clusters.size() : "null"));
            
            if (clusters != null && !clusters.isEmpty()) {
                for (ClusterInfo cluster : clusters) {
                    System.out.println("Cluster: " + cluster);
                }
            }
        } catch (Exception e) {
            System.err.println("Exception in getClusters: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
