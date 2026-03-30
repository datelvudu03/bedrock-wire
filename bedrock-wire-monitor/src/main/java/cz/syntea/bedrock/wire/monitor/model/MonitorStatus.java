package cz.syntea.bedrock.wire.monitor.model;

/**
 * Final status of a monitor check run, derived from
 * {@link cz.syntea.bedrock.wire.monitor.spi.TransportStatus} and
 * {@link ValidationVerdict}.
 *
 * <h3>Mapping rules</h3>
 * <ul>
 *   <li>{@code RESPONSE_RECEIVED} + {@code PASS} → {@code UP}</li>
 *   <li>{@code RESPONSE_RECEIVED} + {@code WARN} → {@code WARN}</li>
 *   <li>{@code RESPONSE_RECEIVED} + {@code FAIL} → {@code DOWN}</li>
 *   <li>{@code TIMEOUT}, {@code CONNECT_ERROR}, {@code IO_ERROR} → {@code DOWN}</li>
 *   <li>{@code POOL_EXHAUSTED} → {@code ERROR}</li>
 *   <li>Internal monitor exception → {@code ERROR}</li>
 * </ul>
 */
public enum MonitorStatus {

    /**
     * Service is healthy. All validators passed.
     */
    UP,

    /**
     * Service responded but at least one validator returned WARN (no FAIL).
     */
    WARN,

    /**
     * Service is unreachable or at least one validator returned FAIL.
     */
    DOWN,

    /**
     * Internal error in the monitor layer or transport resources.
     * Not indicative of the target service state.
     */
    ERROR
}
