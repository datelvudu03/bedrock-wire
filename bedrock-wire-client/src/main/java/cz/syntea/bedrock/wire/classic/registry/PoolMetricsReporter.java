package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import lombok.NonNull;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Periodically samples Reactor Netty connection-pool state and forwards it to
 * {@link WireMetricsCollector#recordPoolState}.
 *
 * <h3>How pool metrics are obtained</h3>
 * The actual Reactor Netty metrics API is a push callback, not a pull API.
 * {@link ConnectionProvider.Builder#metrics(boolean, java.util.function.Supplier)} accepts
 * a {@link ConnectionProvider.MeterRegistrar} whose
 * {@link ConnectionProvider.MeterRegistrar#registerMetrics registerMetrics} method is called
 * once per pool creation and receives a live {@link ConnectionPoolMetrics} handle.
 *
 * <p>This class captures those handles at creation time via
 * {@link #createRegistrarFor(TransportTarget)}. The background scheduler then polls the
 * stored handles every {@link #DEFAULT_INTERVAL} seconds and forwards the snapshot to
 * {@link WireMetricsCollector#recordPoolState}.
 *
 * <h3>Integration with HttpClientRegistryImpl</h3>
 * For each new pool, the registry calls {@link #createRegistrarFor(TransportTarget)} and
 * passes the returned registrar to {@code ConnectionProvider.Builder.metrics(true, supplier)}.
 * No further coordination is needed — the metrics handle is captured automatically when
 * Reactor Netty initialises the pool.
 *
 * <h3>Lifecycle</h3>
 * {@link #start()} is called in the registry constructor.
 * {@link #stop()} is called in {@link cz.syntea.bedrock.wire.classic.client.HttpClientRegistry#close()}.
 * Both are idempotent.
 *
 * <h3>Thread safety</h3>
 * {@code poolMetrics} is a {@link ConcurrentHashMap}; the daemon executor thread only reads it.
 * The executor is a single-threaded daemon and does not prevent JVM shutdown.
 */
public final class PoolMetricsReporter {

    /**
     * Default sampling interval.
     */
    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Live {@link ConnectionPoolMetrics} handles keyed by {@link TransportTarget}.
     * Populated via the {@link ConnectionProvider.MeterRegistrar} callback when a pool is created.
     */
    private final ConcurrentHashMap<TransportTarget, ConnectionPoolMetrics> poolMetrics =
            new ConcurrentHashMap<>();

    private final WireMetricsCollector metricsCollector;
    private final Duration interval;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bedrock-pool-metrics");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> scheduledTask;

    public PoolMetricsReporter(WireMetricsCollector metricsCollector, Duration interval) {
        this.metricsCollector = metricsCollector;
        this.interval = interval;
    }

    public PoolMetricsReporter(WireMetricsCollector metricsCollector) {
        this(metricsCollector, DEFAULT_INTERVAL);
    }

    // ── MeterRegistrar factory ────────────────────────────────────────────────

    /**
     * Returns a {@link ConnectionProvider.MeterRegistrar} that captures the
     * {@link ConnectionPoolMetrics} handle for {@code target} when Reactor Netty
     * calls {@code registerMetrics} at pool initialisation time.
     *
     * <p>Pass the returned registrar to
     * {@code ConnectionProvider.Builder.metrics(true, () -> registrar)} when building
     * the pool for {@code target}.
     *
     * <p>If the pool is later disposed and a new one is created for the same target
     * (e.g. after a registry restart) the handle is simply replaced in the map.
     */
    public ConnectionProvider.MeterRegistrar createRegistrarFor(TransportTarget target) {
        return new ConnectionProvider.MeterRegistrar() {
            @Override
            public void registerMetrics(@NonNull String poolName,
                                        @NonNull String id,
                                        @NonNull SocketAddress remoteAddress,
                                        @NonNull ConnectionPoolMetrics metrics) {
                poolMetrics.put(target, metrics);
            }

            @Override
            public void deRegisterMetrics(@NonNull String poolName,
                                          @NonNull String id,
                                          @NonNull SocketAddress remoteAddress) {
                poolMetrics.remove(target);
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the background sampling task. Idempotent and thread-safe.
     */
    public synchronized void start() {
        if (scheduledTask != null) {
            return;
        }
        long millis = interval.toMillis();
        scheduledTask = executor.scheduleAtFixedRate(
                this::sampleAll, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the background task and shuts down the executor. Idempotent and thread-safe.
     */
    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        executor.shutdown();
    }

    // ── Sampling ──────────────────────────────────────────────────────────────

    private void sampleAll() {
        poolMetrics.forEach((target, metrics) -> {
            try {
                metricsCollector.recordPoolState(
                        target,
                        metrics.acquiredSize(),
                        metrics.pendingAcquireSize()
                );
            } catch (Exception ignored) {
                // Metrics failures MUST NOT affect application threads
            }
        });
    }
}