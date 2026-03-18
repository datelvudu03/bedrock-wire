package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.model.MonitorExecutionResult;
import cz.syntea.bedrock.wire.monitor.model.MonitorStatus;
import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import cz.syntea.bedrock.wire.monitor.validation.Validator;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executes a single check run: builds the request, handles retries,
 * runs validators, maps the final status, and notifies listeners.
 *
 * <p>This class is stateless and thread-safe. Each check run is executed
 * on a virtual thread.
 */
@Slf4j
public class CheckRunner {

    private final MonitorTransport transport;
    private final ValidatorRegistry validatorRegistry;
    private final TemplateProcessor templateProcessor;
    private final List<MonitorResultListener> listeners;

    /**
     * Creates a new check runner.
     *
     * @param transport         the transport implementation; never {@code null}
     * @param validatorRegistry the validator registry; never {@code null}
     * @param templateProcessor the template processor; never {@code null}
     * @param listeners         the result listeners; never {@code null}, may be empty
     */
    public CheckRunner(MonitorTransport transport,
                       ValidatorRegistry validatorRegistry,
                       TemplateProcessor templateProcessor,
                       List<MonitorResultListener> listeners) {
        this.transport = transport;
        this.validatorRegistry = validatorRegistry;
        this.templateProcessor = templateProcessor;
        this.listeners = listeners;
    }

    /**
     * Builds a relative URI from path and query.
     * Normalizes path to ensure leading {@code /}.
     */
    static URI buildRelativeUrl(String path, String query) {
        String normalizedPath = (path == null || path.isBlank()) ? "/" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        if (query != null && !query.isBlank()) {
            return URI.create(normalizedPath + "?" + query.trim());
        }
        return URI.create(normalizedPath);
    }

    /**
     * Executes a complete check run.
     *
     * @param checkConfig   the check configuration; never {@code null}
     * @param serviceConfig the service configuration; never {@code null}
     * @return the execution result; never {@code null}
     */
    public MonitorExecutionResult execute(CheckConfig checkConfig, ServiceConfig serviceConfig) {
        String requestId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        log.debug("Starting check run: check={}, service={}, requestId={}",
                checkConfig.getCheckName(), serviceConfig.getServiceName(), requestId);

        MonitorExecutionResult result;
        try {
            result = doExecute(checkConfig, serviceConfig, requestId, startedAt);
        } catch (TemplateProcessor.TemplateException e) {
            log.error("Template error in check '{}': {}", checkConfig.getCheckName(), e.getMessage());
            result = buildErrorResult(checkConfig, serviceConfig, requestId,
                    startedAt, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in check '{}': {}", checkConfig.getCheckName(), e.getMessage(), e);
            result = buildErrorResult(checkConfig, serviceConfig, requestId,
                    startedAt, "Internal error: " + e.getMessage());
        }

        notifyListeners(result);
        return result;
    }

    // ── Request building ────────────────────────────────────────────────────

    private MonitorExecutionResult doExecute(CheckConfig checkConfig,
                                             ServiceConfig serviceConfig,
                                             String requestId,
                                             Instant startedAt) {
        MonitorRequest request = buildRequest(checkConfig, serviceConfig);
        MonitorResult lastResult = null;
        int attempts = 0;
        int maxAttempts = checkConfig.getRetryCount() + 1;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                sleep(checkConfig.getRetryDelay(), checkConfig.getCheckName(), attempt);
            }

            attempts++;
            lastResult = transport.execute(request);

            log.debug("Check '{}' attempt {}/{}: transportStatus={}",
                    checkConfig.getCheckName(), attempts, maxAttempts,
                    lastResult.getTransportStatus());

            if (!shouldRetry(lastResult.getTransportStatus(), attempt, maxAttempts)) {
                break;
            }
        }

        return buildResult(checkConfig, serviceConfig, requestId, startedAt, attempts, lastResult);
    }

    private MonitorRequest buildRequest(CheckConfig checkConfig, ServiceConfig serviceConfig) {
        URI relativeUrl = buildRelativeUrl(checkConfig.getPath(), checkConfig.getQuery());
        String body = loadBody(checkConfig);

        Map<String, List<String>> headers = checkConfig.getHeaders().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> List.of(e.getValue())
                ));

        return MonitorRequest.builder()
                .serviceName(serviceConfig.getServiceName())
                .method(checkConfig.getMethod())
                .url(relativeUrl)
                .headers(headers)
                .body(body)
                .build();
    }

    private String loadBody(CheckConfig checkConfig) {
        if (checkConfig.getTemplateFile() == null || checkConfig.getTemplateFile().isBlank()) {
            return null;
        }
        if (checkConfig.getMethod() == HttpMethod.GET) {
            log.debug("Check '{}': templateFile defined but method is GET; body will be set but transport SHOULD ignore it",
                    checkConfig.getCheckName());
        }
        return templateProcessor.process(checkConfig.getTemplateFile(), checkConfig.getTemplateParams());
    }

    // ── Retry logic ─────────────────────────────────────────────────────────

    private boolean shouldRetry(TransportStatus status, int currentAttempt, int maxAttempts) {
        if (currentAttempt + 1 >= maxAttempts) {
            return false;
        }
        return switch (status) {
            case TIMEOUT, CONNECT_ERROR -> true;
            case IO_ERROR -> false; // default: no retry for IO_ERROR
            case RESPONSE_RECEIVED, POOL_EXHAUSTED -> false;
        };
    }

    private void sleep(Duration delay, String checkName, int attempt) {
        try {
            log.debug("Check '{}': retry delay {}ms before attempt {}",
                    checkName, delay.toMillis(), attempt + 1);
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Check '{}': retry sleep interrupted", checkName);
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private MonitorExecutionResult buildResult(CheckConfig checkConfig,
                                               ServiceConfig serviceConfig,
                                               String requestId,
                                               Instant startedAt,
                                               int attempts,
                                               MonitorResult transportResult) {
        Instant finishedAt = Instant.now();
        MonitorStatus status;
        String message;

        if (transportResult.getTransportStatus() == TransportStatus.RESPONSE_RECEIVED) {
            ValidationOutcome outcome = runValidators(checkConfig, transportResult);
            status = mapStatus(transportResult.getTransportStatus(), outcome.overallVerdict);
            message = outcome.firstMessage;
        } else if (transportResult.getTransportStatus() == TransportStatus.POOL_EXHAUSTED) {
            status = MonitorStatus.ERROR;
            message = transportResult.getErrorMessage();
        } else {
            status = MonitorStatus.DOWN;
            message = transportResult.getErrorMessage();
        }

        return MonitorExecutionResult.builder()
                .checkName(checkConfig.getCheckName())
                .serviceName(serviceConfig.getServiceName())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .executionDuration(Duration.between(startedAt, finishedAt))
                .attempts(attempts)
                .requestId(requestId)
                .status(status)
                .message(message)
                .transport(transportResult)
                .build();
    }

    private ValidationOutcome runValidators(CheckConfig checkConfig, MonitorResult result) {
        ValidationVerdict overallVerdict = ValidationVerdict.PASS;
        String firstFailMessage = null;
        String firstWarnMessage = null;

        for (String alias : checkConfig.getValidators()) {
            Validator validator;
            try {
                validator = validatorRegistry.get(alias);
            } catch (IllegalArgumentException e) {
                log.error("Unknown validator alias '{}' in check '{}'",
                        alias, checkConfig.getCheckName());
                return new ValidationOutcome(ValidationVerdict.FAIL,
                        "Unknown validator: " + alias);
            }

            ValidationResult vr;
            try {
                vr = validator.validate(result, checkConfig.getValidationParams());
            } catch (Exception e) {
                log.error("Validator '{}' threw exception in check '{}': {}",
                        alias, checkConfig.getCheckName(), e.getMessage(), e);
                return new ValidationOutcome(ValidationVerdict.FAIL,
                        "Validator '" + alias + "' error: " + e.getMessage());
            }

            if (vr.getVerdict() == ValidationVerdict.FAIL) {
                overallVerdict = ValidationVerdict.FAIL;
                if (firstFailMessage == null) {
                    firstFailMessage = vr.getMessage();
                }
            } else if (vr.getVerdict() == ValidationVerdict.WARN
                    && overallVerdict != ValidationVerdict.FAIL) {
                overallVerdict = ValidationVerdict.WARN;
                if (firstWarnMessage == null) {
                    firstWarnMessage = vr.getMessage();
                }
            }
        }

        String message = (firstFailMessage != null) ? firstFailMessage : firstWarnMessage;
        return new ValidationOutcome(overallVerdict, message);
    }

    private MonitorStatus mapStatus(TransportStatus transportStatus, ValidationVerdict verdict) {
        if (transportStatus == TransportStatus.RESPONSE_RECEIVED) {
            return switch (verdict) {
                case PASS -> MonitorStatus.UP;
                case WARN -> MonitorStatus.WARN;
                case FAIL -> MonitorStatus.DOWN;
            };
        }
        if (transportStatus == TransportStatus.POOL_EXHAUSTED) {
            return MonitorStatus.ERROR;
        }
        return MonitorStatus.DOWN;
    }

    private MonitorExecutionResult buildErrorResult(CheckConfig checkConfig,
                                                    ServiceConfig serviceConfig,
                                                    String requestId,
                                                    Instant startedAt,
                                                    String message) {
        Instant finishedAt = Instant.now();
        MonitorResult emptyResult = MonitorResult.builder()
                .transportStatus(TransportStatus.IO_ERROR)
                .httpStatus(0)
                .errorMessage(message)
                .build();

        return MonitorExecutionResult.builder()
                .checkName(checkConfig.getCheckName())
                .serviceName(serviceConfig.getServiceName())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .executionDuration(Duration.between(startedAt, finishedAt))
                .attempts(0)
                .requestId(requestId)
                .status(MonitorStatus.ERROR)
                .message(message)
                .transport(emptyResult)
                .build();
    }

    // ── Listener notification ───────────────────────────────────────────────

    private void notifyListeners(MonitorExecutionResult result) {
        for (MonitorResultListener listener : listeners) {
            try {
                listener.onResult(result);
            } catch (Exception e) {
                log.error("Listener {} threw exception for check '{}': {}",
                        listener.getClass().getSimpleName(), result.getCheckName(), e.getMessage(), e);
            }
        }
    }

    // ── Internal types ──────────────────────────────────────────────────────

    private record ValidationOutcome(ValidationVerdict overallVerdict, String firstMessage) {
    }
}
