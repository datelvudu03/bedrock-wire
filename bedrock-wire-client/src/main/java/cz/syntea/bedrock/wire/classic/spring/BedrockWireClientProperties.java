package cz.syntea.bedrock.wire.classic.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for {@code bedrock-wire-client}.
 *
 * <p>Binds to the {@code bedrock.wire.client} namespace in the Spring Environment.
 *
 * <p>Example (application.yml):
 * <pre>
 * bedrock.wire.client:
 *   max-clients: 100
 *   tls:
 *     payments-tls:
 *       client-cert: /certs/client.p12
 *       client-cert-password: secret
 *       client-cert-type: PKCS12
 *   clients:
 *     payments:
 *       base-url: https://payments.example.com:8443
 *       connect-timeout: 3s
 *       response-timeout: 10s
 *       tls-profile: payments-tls
 *       headers:
 *         Content-Type: text/xml
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

    /**
     * TLS profile definitions keyed by profile name.
     *
     * <p>Each entry maps a profile name to its TLS configuration.
     * Clients reference profiles via {@link ClientProperties#getTlsProfile()}.
     *
     * @since 1.1
     */
    private Map<String, TlsProfileProperties> tls = new LinkedHashMap<>();

    /**
     * HTTP client definitions keyed by client ID.
     *
     * <p>Each entry is auto-registered in the {@code HttpClientRegistry} at startup
     * and exposed as a named Spring bean. Auto-registration is disabled when
     * {@code MonitorTransport} is present (the monitor manages its own clients).
     *
     * @since 1.1
     */
    private Map<String, ClientProperties> clients = new LinkedHashMap<>();

}