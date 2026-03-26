package cz.syntea.bedrock.wire.classic.spring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

/**
 * Registers one {@link HttpClientFactoryBean} bean definition per client defined
 * in the Spring Environment under {@code bedrock.wire.client.clients.*}.
 *
 * <p>Each client is exposed as a named bean (bean name = client key from properties).
 * The factory bean lazily creates the {@link cz.syntea.bedrock.wire.classic.registry.HttpClient}
 * via the {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry} on first access.
 *
 * <p>This registrar runs at {@code BeanDefinition} registration time — before dependency
 * resolution — so {@code @Autowired @Qualifier("payments") HttpClient} works correctly.
 *
 * <p>Activation is controlled by {@link BedrockWireClientAutoConfiguration}, which guards
 * the {@code @Import} of this registrar with {@code @ConditionalOnMissingBean(MonitorTransport)}.
 *
 * @since 1.1
 */
@Slf4j
public class WireClientBeanDefinitionRegistrar
        implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private Environment environment;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        BedrockWireClientProperties properties = Binder.get(environment)
                .bind("bedrock.wire.client", BedrockWireClientProperties.class)
                .orElse(new BedrockWireClientProperties());

        Map<String, ClientProperties> clients = properties.getClients();
        Map<String, TlsProfileProperties> tlsProfiles = properties.getTls();

        if (clients.isEmpty()) {
            log.debug("No clients defined in bedrock.wire.client.clients — "
                    + "skipping auto-registration from Spring Environment");
            return;
        }

        log.info("Auto-registering {} HttpClient bean(s) from Spring Environment: {}",
                clients.size(), clients.keySet());

        for (Map.Entry<String, ClientProperties> entry : clients.entrySet()) {
            String clientId = entry.getKey();
            ClientProperties clientProps = entry.getValue();

            // Resolve the TLS profile if referenced
            TlsProfileProperties tlsProps = null;
            if (clientProps.getTlsProfile() != null) {
                tlsProps = tlsProfiles.get(clientProps.getTlsProfile());
                if (tlsProps == null) {
                    throw new IllegalArgumentException(
                            "Client '" + clientId + "' references TLS profile '"
                                    + clientProps.getTlsProfile()
                                    + "' which is not defined in bedrock.wire.client.tls");
                }
            }

            if (registry.containsBeanDefinition(clientId)) {
                log.warn("Bean '{}' already defined — skipping auto-registration for HttpClient",
                        clientId);
                continue;
            }

            AbstractBeanDefinition beanDef = BeanDefinitionBuilder
                    .genericBeanDefinition(HttpClientFactoryBean.class)
                    .addConstructorArgValue(clientId)
                    .addConstructorArgValue(clientProps)
                    .addConstructorArgValue(tlsProps)
                    .addPropertyReference("registry", "httpClientRegistry")
                    .setLazyInit(false)
                    .getBeanDefinition();

            registry.registerBeanDefinition(clientId, beanDef);
            log.debug("Registered HttpClientFactoryBean '{}' (baseUrl={})",
                    clientId, clientProps.getBaseUrl());
        }
    }

}