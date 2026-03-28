package cz.syntea.bedrock.wire.classic.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for {@code bedrock-wire-client}.
 *
 * <p>Binds to the {@code bedrock.wire.client} namespace in the Spring Environment.
 *
 * <p>Client and TLS configuration is loaded from the {@code .param} file
 * ({@code wire.client.*} and {@code wire.tls.*} namespaces) via
 * {@link ParamFileClientConfigAdapter}, not from Spring properties.
 *
 * <p>Example:
 * <pre>
 * bedrock.wire.client.max-clients=100
 * </pre>
 *
 * @since 1.0
 */
@Data
@ConfigurationProperties(prefix = "bedrock.wire.client")
public class BedrockWireClientProperties {

    /**
     * Maximum number of HttpClient instances the registry can hold.
     */
    private int maxClients = 100;

}