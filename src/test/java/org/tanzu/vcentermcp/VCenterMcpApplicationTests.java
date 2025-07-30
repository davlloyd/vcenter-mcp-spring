package org.tanzu.vcentermcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "VCENTER_HOST=test-vcenter.example.com",
    "VCENTER_PORT=443",
    "VCENTER_USERNAME=test-user",
    "VCENTER_PASSWORD=test-password",
    "VCENTER_INSECURE=true"
})
class VCenterMcpApplicationTests {

    @Test
    void contextLoads() {
        // This test verifies that the application context can be loaded
        // with test vCenter configuration
    }

}
