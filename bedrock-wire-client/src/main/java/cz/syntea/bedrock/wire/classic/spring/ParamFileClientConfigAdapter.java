package cz.syntea.bedrock.wire.classic.spring;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adapter that parses {@code wire.client.<n>.*} and {@code wire.tls.<n>.*} namespaces
 * from a {@link Properties} instance (typically {@code PropertiesCfg} loaded from a
 * {@code .param} file) into the property-bindable model used by wire-client auto-registration.
 *
 * <p>This adapter is a pure converter with no Spring dependency. It is used as a fallback
 * when no clients are defined in the Spring Environment but a {@code Properties} bean exists.
 *
 * <p><b>Note:</b> The {@link #parseDuration} method mirrors the format supported by
 * {@code bedrock-wire-monitor}'s {@code DurationParser}. Extracting to a shared utility
 * module is future work.
 *
 * @since 1.1
 */
@Slf4j
public class ParamFileClientConfigAdapter {

    private static final String CLIENT_PREFIX = "wire.client.";
    private static final String TLS_PREFIX = "wire.tls.";

    private final Properties properties;

    /**
     * Creates a new adapter for the given properties source.
     *
     * @param properties the properties to parse; must not be {@code null}
     */
    public ParamFileClientConfigAdapter(Properties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Parses a duration string with a time unit suffix.
     *
     * <p>Supported formats: {@code 5s}, {@code 500ms}, {@code 2m}.
     * Bare numbers without units are not supported.
     *
     * @param value      the duration string
     * @param paramName  the parameter name for error messages
     * @param clientName the client name for error messages
     * @return the parsed duration
     * @throws IllegalArgumentException if the format is invalid
     */
    public static Duration parseDuration(String value, String paramName, String clientName) {
        String trimmed = value.trim();
        try {
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()));
            } else if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid duration '" + value + "' for " + paramName
                            + " in client '" + clientName + "'. Expected format: 5s, 500ms, 2m", e);
        }
        throw new IllegalArgumentException(
                "Invalid duration '" + value + "' for " + paramName
                        + " in client '" + clientName + "'. Expected format: 5s, 500ms, 2m");
    }

    /**
     * Parses all TLS profiles from {@code wire.tls.<profile>.*} entries.
     *
     * @return a map of profile name to {@link TlsProfileProperties}; never {@code null}
     */
    public Map<String, TlsProfileProperties> parseTlsProfiles() {
        Map<String, TlsProfileProperties> profiles = new LinkedHashMap<>();
        Set<String> profileNames = extractNames(TLS_PREFIX);

        for (String name : profileNames) {
            String prefix = TLS_PREFIX + name + ".";
            TlsProfileProperties tls = new TlsProfileProperties();
            tls.setClientCert(getProperty(prefix + "clientCert"));
            tls.setClientCertPassword(getProperty(prefix + "clientCertPassword"));
            tls.setClientCertType(getProperty(prefix + "clientCertType"));
            tls.setClientCertAlias(getProperty(prefix + "clientCertAlias"));
            tls.setTrustStore(getProperty(prefix + "trustStore"));
            tls.setTrustStorePassword(getProperty(prefix + "trustStorePassword"));
            tls.setTrustStoreType(getProperty(prefix + "trustStoreType"));

            String hostnameVerification = getProperty(prefix + "hostnameVerification");
            if (hostnameVerification != null) {
                tls.setHostnameVerification(Boolean.parseBoolean(hostnameVerification));
            }

            String allowInsecure = getProperty(prefix + "allowInsecureInProduction");
            if (allowInsecure != null) {
                tls.setAllowInsecureInProduction(Boolean.parseBoolean(allowInsecure));
            }

            profiles.put(name, tls);
            log.debug("Parsed TLS profile '{}' from .param file", name);
        }

        return profiles;
    }

    /**
     * Parses all client definitions from {@code wire.client.<n>.*} entries.
     *
     * @return a map of client ID to {@link ClientProperties}; never {@code null}
     */
    public Map<String, ClientProperties> parseClients() {
        Map<String, ClientProperties> clients = new LinkedHashMap<>();
        Set<String> clientNames = extractNames(CLIENT_PREFIX);

        for (String name : clientNames) {
            String prefix = CLIENT_PREFIX + name + ".";
            ClientProperties client = new ClientProperties();

            client.setBaseUrl(getProperty(prefix + "url"));

            String connectTimeout = getProperty(prefix + "connectionTimeout");
            if (connectTimeout != null) {
                client.setConnectTimeout(parseDuration(connectTimeout, "connectionTimeout", name));
            }

            String responseTimeout = getProperty(prefix + "responseTimeout");
            if (responseTimeout != null) {
                client.setResponseTimeout(parseDuration(responseTimeout, "responseTimeout", name));
            }

            String readTimeout = getProperty(prefix + "readTimeout");
            if (readTimeout != null) {
                client.setReadTimeout(parseDuration(readTimeout, "readTimeout", name));
            }

            String maxResponseBodySize = getProperty(prefix + "maxResponseBodySize");
            if (maxResponseBodySize != null) {
                try {
                    client.setMaxResponseBodySize(Integer.parseInt(maxResponseBodySize.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid integer '" + maxResponseBodySize + "' for maxResponseBodySize"
                                    + " in client '" + name + "'", e);
                }
            }

            String maxConnections = getProperty(prefix + "maxConnections");
            if (maxConnections != null) {
                try {
                    client.setMaxConnections(Integer.parseInt(maxConnections.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid integer '" + maxConnections + "' for maxConnections"
                                    + " in client '" + name + "'", e);
                }
            }

            client.setTlsProfile(getProperty(prefix + "tlsProfile"));

            // Parse headers: wire.client.<n>.header.<headerName> = value
            String headerPrefix = prefix + "header.";
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(headerPrefix)) {
                    String headerName = key.substring(headerPrefix.length());
                    client.getHeaders().put(headerName, properties.getProperty(key));
                }
            }

            clients.put(name, client);
            log.debug("Parsed client '{}' from .param file: baseUrl={}", name, client.getBaseUrl());
        }

        return clients;
    }

    private Set<String> extractNames(String prefix) {
        Set<String> names = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int dot = remainder.indexOf('.');
                if (dot > 0) {
                    names.add(remainder.substring(0, dot));
                }
            }
        }
        return names;
    }

    private String getProperty(String key) {
        String value = properties.getProperty(key);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

}