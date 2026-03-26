package cz.syntea.bedrock.wire.classic.spring;

import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import lombok.Data;

import java.nio.file.Path;

/**
 * Property-bindable TLS profile configuration.
 *
 * <p>Maps 1:1 to {@link TlsConfig} fields. Bound from either Spring Environment
 * ({@code bedrock.wire.client.tls.<profile>.*}) or parsed from {@code .param} file
 * ({@code wire.tls.<profile>.*}).
 *
 * @since 1.1
 */
@Data
public class TlsProfileProperties {

    /**
     * Path to the client certificate keystore.
     */
    private String clientCert;

    /**
     * Password for the client certificate keystore.
     */
    private String clientCertPassword;

    /**
     * Keystore type: {@code PKCS12} or {@code JKS}.
     */
    private String clientCertType;

    /**
     * Alias to select within the keystore.
     */
    private String clientCertAlias;

    /**
     * Path to the custom truststore; {@code null} uses JVM default.
     */
    private String trustStore;

    /**
     * Password for the truststore.
     */
    private String trustStorePassword;

    /**
     * Truststore type: {@code PKCS12} or {@code JKS}.
     */
    private String trustStoreType;

    /**
     * Whether to verify the server hostname against the certificate. Default: {@code true}.
     */
    private boolean hostnameVerification = true;

    /**
     * Must be {@code true} to allow disabling hostname verification. Default: {@code false}.
     */
    private boolean allowInsecureInProduction = false;

    /**
     * Converts this property object to a {@link TlsConfig} domain object.
     *
     * @param profileName the TLS profile name used as the config name
     * @return the corresponding {@link TlsConfig}; never {@code null}
     */
    public TlsConfig toTlsConfig(String profileName) {
        TlsConfig.TlsConfigBuilder builder = TlsConfig.builder()
                .configName(profileName)
                .hostnameVerification(hostnameVerification)
                .allowInsecureInProduction(allowInsecureInProduction);

        if (clientCert != null) {
            builder.clientCert(Path.of(clientCert));
        }
        if (clientCertPassword != null) {
            builder.clientCertPassword(clientCertPassword);
        }
        if (clientCertType != null) {
            builder.clientCertType(clientCertType);
        }
        if (clientCertAlias != null) {
            builder.clientCertAlias(clientCertAlias);
        }
        if (trustStore != null) {
            builder.trustStore(Path.of(trustStore));
        }
        if (trustStorePassword != null) {
            builder.trustStorePassword(trustStorePassword);
        }
        if (trustStoreType != null) {
            builder.trustStoreType(trustStoreType);
        }

        return builder.build();
    }

}