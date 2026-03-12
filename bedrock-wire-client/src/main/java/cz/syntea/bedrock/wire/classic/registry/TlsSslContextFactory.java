package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.exception.TlsConfigurationException;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;

/**
 * Internal factory that converts a {@link TlsConfig} into a Netty {@link SslContext}.
 *
 * <p>Fail-fast: any configuration error (bad certificate, wrong password, missing alias)
 * throws {@link TlsConfigurationException} immediately.
 *
 * <h3>Trust-all detection</h3>
 * After building the {@code TrustManagerFactory}, each produced {@code TrustManager}
 * is checked for a known trust-all pattern (empty {@code getAcceptedIssuers()} is
 * used as an heuristic). Trust-all managers are rejected with a
 * {@link TlsConfigurationException}.
 *
 * <h3>Protocol defaults</h3>
 * When {@code enabledProtocols} is empty/null, only {@code TLSv1.2} and
 * {@code TLSv1.3} are permitted. TLS 1.0 and 1.1 are never negotiated.
 */
public final class TlsSslContextFactory {

    private static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.2", "TLSv1.3");

    private TlsSslContextFactory() {
    }

    public static SslContext build(TlsConfig config) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();

            // ── Client certificate (mTLS) ─────────────────────────────────────
            if (config.getClientCert() != null) {
                KeyManagerFactory kmf = buildKeyManagerFactory(config);
                builder.keyManager(kmf);
            }

            // ── Custom trust store ────────────────────────────────────────────
            if (config.getTrustStore() != null) {
                TrustManagerFactory tmf = buildTrustManagerFactory(config);
                validateNotTrustAll(tmf, config.getConfigName());
                builder.trustManager(tmf);
            }

            // ── TLS protocols ─────────────────────────────────────────────────
            List<String> protocols = (config.getEnabledProtocols() != null && !config.getEnabledProtocols().isEmpty())
                    ? config.getEnabledProtocols()
                    : DEFAULT_PROTOCOLS;
            builder.protocols(protocols.toArray(new String[0]));

            // ── Cipher suites ─────────────────────────────────────────────────
            if (config.getEnabledCipherSuites() != null && !config.getEnabledCipherSuites().isEmpty()) {
                builder.ciphers(config.getEnabledCipherSuites());
            }

            return builder.build();

        } catch (TlsConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsConfigurationException(
                    "Failed to build TLS context for configName=" + config.getConfigName(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static KeyManagerFactory buildKeyManagerFactory(TlsConfig config) throws Exception {
        String type = config.getClientCertType() != null ? config.getClientCertType() : "PKCS12";
        KeyStore ks = KeyStore.getInstance(type);

        char[] password = config.getClientCertPassword() != null
                ? config.getClientCertPassword().toCharArray()
                : new char[0];

        try (InputStream is = Files.newInputStream(config.getClientCert())) {
            ks.load(is, password);
        }

        // Validate alias existence if specified
        if (config.getClientCertAlias() != null) {
            if (!ks.containsAlias(config.getClientCertAlias())) {
                throw new TlsConfigurationException(
                        "Alias '" + config.getClientCertAlias() + "' not found in keystore: "
                                + config.getClientCert());
            }
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        // If a specific alias was requested, wrap the KMF to enforce it
        if (config.getClientCertAlias() != null) {
            return wrapWithAlias(kmf, config.getClientCertAlias());
        }
        return kmf;
    }

    private static TrustManagerFactory buildTrustManagerFactory(TlsConfig config) throws Exception {
        String type = config.getTrustStoreType() != null ? config.getTrustStoreType() : "PKCS12";
        KeyStore ts = KeyStore.getInstance(type);

        char[] password = config.getTrustStorePassword() != null
                ? config.getTrustStorePassword().toCharArray()
                : new char[0];

        try (InputStream is = Files.newInputStream(config.getTrustStore())) {
            ts.load(is, password);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }

    /**
     * Heuristic trust-all detection: a {@code TrustManager} whose
     * {@code getAcceptedIssuers()} returns an empty array is typically a trust-all
     * implementation (the JDK PKIXTrustManager always returns its configured CA list).
     */
    private static void validateNotTrustAll(TrustManagerFactory tmf, String configName) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                if (x509.getAcceptedIssuers() != null && x509.getAcceptedIssuers().length == 0) {
                    throw new TlsConfigurationException(
                            "Trust-all TrustManager detected for configName=" + configName
                                    + ". Custom trust-all managers are not permitted.");
                }
            }
        }
    }

    /**
     * Wraps an existing {@link KeyManagerFactory} so that {@code chooseClientAlias}
     * always returns the pinned {@code alias}, forcing mTLS to use the specified certificate.
     */
    private static KeyManagerFactory wrapWithAlias(KeyManagerFactory delegate, String alias) {
        X509KeyManager[] kms = Arrays.stream(delegate.getKeyManagers())
                .filter(X509KeyManager.class::isInstance)
                .map(km -> (X509KeyManager) km)
                .toArray(X509KeyManager[]::new);

        if (kms.length == 0) {
            throw new TlsConfigurationException(
                    "Cannot pin alias '" + alias
                            + "': no X509KeyManager found in KeyManagerFactory");
        }

        X509KeyManager aliasKm = new AliasPinningKeyManager(kms[0], alias);

        // Return a no-init factory that exposes our wrapped manager
        try {
            return new KeyManagerFactory(
                    new KeyManagerFactorySpi() {
                        @Override
                        protected void engineInit(KeyStore ks, char[] pw) {
                        }

                        @Override
                        protected void engineInit(ManagerFactoryParameters spec) {
                        }

                        @Override
                        protected KeyManager[] engineGetKeyManagers() {
                            return new KeyManager[]{aliasKm};
                        }
                    },
                    delegate.getProvider(),
                    delegate.getAlgorithm()
            ) {
            };
        } catch (Exception e) {
            throw new TlsConfigurationException(
                    "Failed to wrap KeyManagerFactory for alias pinning: alias='" + alias + "'", e);
        }
    }
}
