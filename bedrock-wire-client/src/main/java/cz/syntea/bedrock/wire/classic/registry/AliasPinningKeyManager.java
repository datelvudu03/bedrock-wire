package cz.syntea.bedrock.wire.classic.registry;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * {@link X509ExtendedKeyManager} that always returns a fixed {@code alias}
 * for {@code chooseClientAlias}, ensuring mTLS uses the configured certificate
 * regardless of what the server requests.
 */
final class AliasPinningKeyManager extends X509ExtendedKeyManager {

    private final X509KeyManager delegate;
    private final String pinnedAlias;

    AliasPinningKeyManager(X509KeyManager delegate, String pinnedAlias) {
        this.delegate = delegate;
        this.pinnedAlias = pinnedAlias;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return pinnedAlias;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return pinnedAlias;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.getCertificateChain(alias);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.getPrivateKey(alias);
    }
}