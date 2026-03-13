package cz.syntea.bedrock.wire.classic.config;

import lombok.Builder;
import lombok.Value;

/**
 * Global configuration for
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry}.
 */
@Value
@Builder(toBuilder = true)
public class HttpClientRegistryConfig {

    /**
     * Maximum number of distinct {@link cz.syntea.bedrock.wire.classic.registry.HttpClient}
     * instances (identified by {@code clientId}) that the registry may hold.
     * Default: 100.
     */
    @Builder.Default
    int maxClients = 100;

    /**
     * Returns an instance with all defaults applied.
     */
    public static HttpClientRegistryConfig defaults() {
        return HttpClientRegistryConfig.builder().build();
    }
}