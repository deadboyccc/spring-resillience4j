package dev.dead.resillience4jpatternsplayground;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

@TestConfiguration
public class TestConfig {

    @Bean
    public WebTestClient webTestClient(
            org.springframework.boot.test.web.server.LocalServerPort port) {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }
}
