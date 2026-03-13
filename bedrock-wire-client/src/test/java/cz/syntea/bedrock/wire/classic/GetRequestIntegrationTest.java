package cz.syntea.bedrock.wire.classic;


import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for GET requests with query parameters against a public API.
 *
 * <p>Uses <a href="https://jsonplaceholder.typicode.com">JSONPlaceholder</a> — free,
 * no auth, HTTPS, JSON responses, supports filtering via query params.
 *
 */
@SpringBootTest(classes = GetRequestIntegrationTest.TestConfig.class)
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
class GetRequestIntegrationTest {

    @Autowired
    private HttpClientRegistry registry;
    private HttpClient client;

    @BeforeAll
    void setUp() {
        // No TLS — plain HTTPS with JVM default trust
        client = registry.get(HttpClientConfig.builder()
                .clientId("jsonplaceholder")
                .baseUrl(URI.create("https://jsonplaceholder.typicode.com"))
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(10))
                .defaultHeader("Accept", List.of("application/json"))
                .build());
    }

    @Test
    void getWithHardcodedQueryParams() {
        // GET /comments?postId=1 — get all comments for post 1
        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(URI.create("/comments?postId=1"))
                .build()
        ).block();

        log.info("[GET hardcoded] status={} duration={} body={}",
                response.getStatusCode(),
                response.getDuration(),
                response.getResponseBody());

        assertThat(response.getStatusCode())
                .as("Expected 200 from /comments?postId=1")
                .isEqualTo(200);

        assertThat(response.getResponseBody())
                .as("Response should contain comments for post 1")
                .contains("\"postId\": 1");
    }

    // ── Case 1: Hardcoded query params ───────────────────────────────────────

    @Test
    void getWithDynamicQueryParams() {
        // Simulate dynamic values — in real code these come from method arguments
        int userId = 1;
        String title = "sunt aut facere";

        URI path = UriComponentsBuilder.fromPath("/posts")
                .queryParam("userId", userId)
                .queryParam("title", title)    // auto-encodes spaces
                .build()
                .toUri();
        // → /posts?userId=1&title=sunt%20aut%20facere

        log.info("[GET dynamic] Requesting: {}", path);

        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(path)
                .build()
        ).block();

        log.info("[GET dynamic] status={} duration={} body={}",
                response.getStatusCode(),
                response.getDuration(),
                response.getResponseBody());

        assertThat(response.getStatusCode())
                .as("Expected 200 from /posts with dynamic params")
                .isEqualTo(200);

    }

    // ── Case 2: Dynamic query params with UriComponentsBuilder ───────────────

    @Test
    void getWithoutParams() {
        // GET /posts/1 — get a single post by ID (path param, not query param)
        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(URI.create("/posts/1"))
                .build()
        ).block();

        log.info("[GET simple] status={} duration={} body={}",
                response.getStatusCode(),
                response.getDuration(),
                response.getResponseBody());

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getResponseBody()).contains("\"id\": 1");
    }

    @Test
    void getWithSpecialCharacters() {
        // Demonstrates why UriComponentsBuilder is safer than String.format
        String searchTerm = "Bret & friends";  // contains &, would break raw string concat

        URI path = UriComponentsBuilder.fromPath("/users")
                .queryParam("username", searchTerm)
                .build()
                .encode()
                .toUri();
        // → /users?username=Bret%20%26%20friends

        log.info("[GET special] Requesting: {}", path);

        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(path)
                .build()
        ).block();

        log.info("[GET special] status={} duration={} body={}",
                response.getStatusCode(),
                response.getDuration(),
                response.getResponseBody());

        // JSONPlaceholder returns empty array for unknown usernames — still 200
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
    }
}