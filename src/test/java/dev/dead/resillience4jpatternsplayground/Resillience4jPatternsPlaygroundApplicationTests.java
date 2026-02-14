package dev.dead.resillience4jpatternsplayground;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class Resillience4jPatternsPlaygroundApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully with all Resilience4j configurations
    }

}