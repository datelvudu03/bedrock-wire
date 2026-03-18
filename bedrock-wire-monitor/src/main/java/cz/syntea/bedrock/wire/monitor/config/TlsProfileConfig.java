package cz.syntea.bedrock.wire.monitor.config;

import lombok.Builder;
import lombok.Value;

/**
 * Parsed representation of a {@code monitor.tls.<profileName>.*} configuration block.
 *
 * <p>Extracted by the configuration provider and consumed by the transport
 * implementation during {@code init()} to register TLS profiles.
 *
 * <p>This class is transport-agnostic — it mirrors the configuration keys
 * defined in the specification without binding to any specific TLS implementation.
 */
@Value
@Builder(toBuilder = true)
public class TlsProfileConfig {

    /**
     * Unique profile name, referenced by {@code transport.tlsProfile} in service config.
     */
    String profileName;

    /**
     * Path to the client certificate keystore file. {@code null} disables mTLS.
     */
    String clientCert;

    /**
     * Password for the client certificate keystore.
     */
    String clientCertPassword;

    /**
     * Keystore type: {@code "PKCS12"} or {@code "JKS"}.
     */
    String clientCertType;

    /**
     * Alias inside the keystore to use as the client certificate.
     */
    String clientCertAlias;

    /**
     * Path to a custom trust store. {@code null} uses JVM default.
     */
    String trustStore;

    /**
     * Password for the trust store.
     */
    String trustStorePassword;

    /**
     * Trust store type: {@code "PKCS12"} or {@code "JKS"}.
     */
    String trustStoreType;

    /**
     * Whether to verify the server's hostname against certificate CN/SANs.
     * Default: {@code true}.
     */
    @Builder.Default
    boolean hostnameVerification = true;

    /**
     * Explicit opt-in to allow disabled hostname verification.
     * Default: {@code false}.
     */
    @Builder.Default
    boolean allowInsecureInProduction = false;
}
