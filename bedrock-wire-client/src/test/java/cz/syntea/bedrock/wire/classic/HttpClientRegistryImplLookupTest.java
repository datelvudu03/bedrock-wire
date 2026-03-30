package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.config.HttpClientRegistryConfig;
import cz.syntea.bedrock.wire.classic.observability.NoOpWireMetricsCollector;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link HttpClientRegistry} lookup-by-id methods (since 1.1).
 */
class HttpClientRegistryImplLookupTest {

    private HttpClientRegistryImpl registry;

    @BeforeEach
    void setUp() {
        HttpClientRegistryConfig config = HttpClientRegistryConfig.builder()
                .maxClients(10)
                .build();
        registry = new HttpClientRegistryImpl(
                config,
                new NoOpWireMetricsCollector());
    }

    private HttpClientConfig createConfig(String clientId) {
        return HttpClientConfig.builder()
                .clientId(clientId)
                .baseUrl(URI.create("https://example.com"))
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Nested
    @DisplayName("get(String clientId)")
    class GetByClientId {

        @Test
        @DisplayName("returns client when registered via get(config)")
        void returnsRegisteredClient() {
            HttpClient created = registry.get(createConfig("payments"));

            HttpClient retrieved = registry.get("payments");

            assertThat(retrieved).isSameAs(created);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unknown clientId")
        void throwsForUnknownClientId() {
            assertThatThrownBy(() -> registry.get("nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nonexistent")
                    .hasMessageContaining("No HttpClient registered");
        }

        @Test
        @DisplayName("throws NullPointerException for null clientId")
        void throwsForNullClientId() {
            assertThatThrownBy(() -> registry.get((String) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("containsClient(String clientId)")
    class ContainsClient {

        @Test
        @DisplayName("returns true for registered client")
        void returnsTrueForRegistered() {
            registry.get(createConfig("payments"));

            assertThat(registry.containsClient("payments")).isTrue();
        }

        @Test
        @DisplayName("returns false for unregistered client")
        void returnsFalseForUnregistered() {
            assertThat(registry.containsClient("nonexistent")).isFalse();
        }
    }

    @Nested
    @DisplayName("getRegisteredClientIds()")
    class GetRegisteredClientIds {

        @Test
        @DisplayName("returns empty set when no clients registered")
        void returnsEmptySetInitially() {
            assertThat(registry.getRegisteredClientIds()).isEmpty();
        }

        @Test
        @DisplayName("returns all registered client IDs")
        void returnsAllRegisteredIds() {
            registry.get(createConfig("payments"));
            registry.get(createConfig("internal"));

            Set<String> ids = registry.getRegisteredClientIds();

            assertThat(ids).containsExactlyInAnyOrder("payments", "internal");
        }

        @Test
        @DisplayName("returned set is an immutable snapshot")
        void returnedSetIsImmutableSnapshot() {
            registry.get(createConfig("payments"));

            Set<String> ids = registry.getRegisteredClientIds();

            // Snapshot — adding a new client doesn't affect the already-returned set
            registry.get(createConfig("internal"));
            assertThat(ids).containsExactly("payments");

            // Immutable
            assertThatThrownBy(() -> ids.add("hacked"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

}