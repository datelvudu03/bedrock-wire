package cz.syntea.bedrock.wire.classic.model;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable TLS configuration for a named security profile.
 *
 * <p>MUST be constructed via {@link #builder()} — direct field mutation is not permitted.
 * Register instances with
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry#registerTlsConfig(TlsConfig)}
 * before any {@code get()} call that references this {@code configName}.
 *
 * <h3>mTLS</h3>
 * Activated by setting {@code clientCert}. Supported formats: {@code PKCS12}, {@code JKS}.
 *
 * <h3>Hostname verification</h3>
 * Disabled via {@code hostnameVerification=false}; this requires {@code allowInsecureInProduction=true}
 * as an explicit opt-in, and triggers a {@code WARN} log at registration time.
 * MUST NOT be used in production.
 *
 * <h3>Certificate rotation</h3>
 * Not supported at runtime. Replacing a {@code TlsConfig} requires application restart
 * and full re-creation of {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry}.
 *
 * <h3>Protocol defaults</h3>
 * When {@code enabledProtocols} is {@code null}, the library enforces TLS 1.2+ and
 * explicitly disables TLS 1.0 and 1.1 regardless of JVM defaults.
 */
@Value
@Builder(toBuilder = true)
public class TlsConfig {

    /**
     * Unique name used to reference this profile from {@link HttpClientConfig#getTlsConfigName()}. Required.
     */
    String configName;

    /**
     * Path to keystore file. {@code null} = no client certificate (mTLS disabled).
     */
    Path clientCert;

    /**
     * Keystore password.
     * Ignored if {@code clientCert} is {@code null}.
     * If the keystore is password-protected and this is absent, initialization fails fast.
     */
    String clientCertPassword;

    /**
     * Keystore type: {@code "PKCS12"} or {@code "JKS"}.
     * Ignored if {@code clientCert} is {@code null}.
     */
    String clientCertType;

    /**
     * Alias inside the keystore to use as the client certificate.
     * Ignored if {@code clientCert} is {@code null}.
     * If specified and the alias does not exist in the keystore,
     * initialization MUST fail with
     * {@link cz.syntea.bedrock.wire.classic.exception.TlsConfigurationException}.
     */
    String clientCertAlias;

    /**
     * Custom trust store path. {@code null} = JVM default trust store.
     */
    Path trustStore;

    /**
     * Trust store password. Ignored if {@code trustStore} is {@code null}.
     */
    String trustStorePassword;

    /**
     * Trust store type: {@code "PKCS12"} or {@code "JKS"}.
     * Ignored if {@code trustStore} is {@code null}.
     */
    String trustStoreType;

    /**
     * Whether to verify the server's hostname against the certificate CN/SANs.
     * Default: {@code true}.
     * Setting to {@code false} requires {@code allowInsecureInProduction=true}.
     */
    @Builder.Default
    boolean hostnameVerification = true;

    /**
     * Explicit opt-in to allow {@code hostnameVerification=false}.
     * Produces a {@code WARN} log at registration time.
     * MUST NOT be used in production.
     * Default: {@code false}.
     */
    @Builder.Default
    boolean allowInsecureInProduction = false;

    /**
     * Allowed TLS protocol versions (e.g. {@code ["TLSv1.2", "TLSv1.3"]}).
     * {@code null} = library default: TLS 1.2 and 1.3 only (TLS 1.0 and 1.1 are always disabled).
     */
    @Singular("enabledProtocol")
    List<String> enabledProtocols;

    /**
     * Allowed cipher suites.
     * {@code null} / empty = JVM default cipher suites.
     */
    @Singular("enabledCipherSuite")
    List<String> enabledCipherSuites;
}