package cz.syntea.bedrock.wire.monitor.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry that maps validator aliases to {@link Validator} instances.
 *
 * <p>Pre-loaded with the five built-in validators ({@code httpStatus},
 * {@code contains}, {@code regex}, {@code maxDuration}, {@code xpath}).
 * Additional custom validators can be registered via {@link #register(Validator)}.
 *
 * <p>Thread-safe after construction (the internal map is not modified after
 * all validators are registered during initialization).
 */
public class ValidatorRegistry {

    private final Map<String, Validator> validators = new LinkedHashMap<>();

    /**
     * Creates a registry pre-loaded with all built-in validators.
     */
    public ValidatorRegistry() {
        register(new HttpStatusValidator());
        register(new ContainsValidator());
        register(new RegexValidator());
        register(new MaxDurationValidator());
        register(new XPathValidator());
    }

    /**
     * Creates a registry pre-loaded with built-in validators and additional
     * custom validators.
     *
     * @param customValidators additional validators to register; may be {@code null} or empty
     */
    public ValidatorRegistry(List<Validator> customValidators) {
        this();
        if (customValidators != null) {
            for (Validator v : customValidators) {
                register(v);
            }
        }
    }

    /**
     * Registers a validator. If a validator with the same alias already exists,
     * it is replaced (custom overrides built-in).
     *
     * @param validator the validator to register; must not be {@code null}
     */
    public void register(Validator validator) {
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }
        String alias = validator.alias();
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Validator alias must not be null or blank");
        }
        validators.put(alias, validator);
    }

    /**
     * Resolves a validator by its alias.
     *
     * @param alias the alias to look up; must not be {@code null}
     * @return the validator; never {@code null}
     * @throws IllegalArgumentException if no validator is registered for the alias
     */
    public Validator get(String alias) {
        Validator validator = validators.get(alias);
        if (validator == null) {
            throw new IllegalArgumentException(
                    "Unknown validator alias: '" + alias + "'. Known aliases: " + getAliases());
        }
        return validator;
    }

    /**
     * Returns the set of all registered validator aliases.
     *
     * @return unmodifiable set of aliases; never {@code null}
     */
    public Set<String> getAliases() {
        return Collections.unmodifiableSet(validators.keySet());
    }
}
