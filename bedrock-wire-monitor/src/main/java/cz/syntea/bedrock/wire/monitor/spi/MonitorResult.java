package cz.syntea.bedrock.wire.monitor.spi;

import cz.syntea.bedrock.wire.monitor.json.JsonFormat;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Result of a single HTTP transport attempt returned by
 * {@link MonitorTransport#execute(MonitorRequest)}.
 *
 * <p>This object is always returned — {@code execute()} MUST never throw an exception.
 *
 * <h3>Field availability by {@link TransportStatus}</h3>
 * <ul>
 *   <li>{@code RESPONSE_RECEIVED}: all fields populated (httpStatus, responseBody, headers).</li>
 *   <li>All other statuses: {@code httpStatus} is {@code 0}, {@code responseBody} is empty,
 *       {@code headers} is an empty map, and {@code errorMessage} is non-null with a
 *       descriptive error distinguishing the specific failure type.</li>
 * </ul>
 */
@Value
@Builder(toBuilder = true)
public class MonitorResult {

    /**
     * Transport-level outcome of the HTTP attempt.
     */
    TransportStatus transportStatus;

    /**
     * HTTP status code (e.g. 200, 404). {@code 0} if
     * {@code transportStatus != RESPONSE_RECEIVED}.
     */
    int httpStatus;

    /**
     * Response body decoded as UTF-8. Empty string if
     * {@code transportStatus != RESPONSE_RECEIVED}.
     * Never {@code null}.
     */
    @Builder.Default
    String responseBody = "";

    /**
     * Response headers. Empty map if
     * {@code transportStatus != RESPONSE_RECEIVED}.
     * Never {@code null}.
     */
    @Builder.Default
    Map<String, List<String>> headers = Map.of();

    /**
     * Descriptive error message. Non-null when
     * {@code transportStatus != RESPONSE_RECEIVED}.
     * MUST distinguish the specific type of error (e.g. "ResponseTimeout",
     * "ConnectionRefused", "PoolExhausted").
     */
    String errorMessage;

    /**
     * Duration of the last HTTP attempt, measured by the transport implementation.
     */
    Duration transportDuration;

    @Override
    public String toString() {
        return JsonFormat.toJson(this);
    }
}