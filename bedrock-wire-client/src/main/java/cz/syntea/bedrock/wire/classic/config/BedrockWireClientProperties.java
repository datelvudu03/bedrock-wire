package cz.syntea.bedrock.wire.classic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for {@code bedrock-wire-client}.
 *
 * <p>Example {@code application.yml}:
 * <pre>{@code
 * bedrock:
 *   wire:
 *     client:
 *       max-clients: 50
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "bedrock.wire.client")
public class BedrockWireClientProperties {

    /**
     * Maximum number of distinct {@code HttpClient} instances (by {@code clientId})
     * the registry may hold. Exceeding this limit causes {@code RegistryCapacityException}.
     * Default: 100.
     */
    private int maxClients = 100;
}