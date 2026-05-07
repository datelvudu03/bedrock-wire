package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import cz.syntea.bedrock.wire.template.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central monitor orchestrator implementing {@link SmartLifecycle}.
 *
 * <p>Manages the complete lifecycle: configuration loading, transport initialization,
 * check scheduling, virtual thread dispatch, skip-if-running enforcement, and
 * graceful shutdown.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #start()} — loads config, initializes transport, starts scheduler</li>
 *   <li>{@link #stop()} — stops scheduler, waits for in-flight runs, interrupts
 *       remaining virtual threads, closes transport with remaining timeout</li>
 * </ul>
 *
 * <h3>Scheduling</h3>
 * Uses a single-thread {@link ScheduledExecutorService} for timing triggers.
 * Each trigger dispatches the actual check run to a virtual thread via
 * {@link Thread#ofVirtual()}. All spawned virtual threads are tracked for
 * graceful shutdown.
 *
 * <h3>Phase ordering</h3>
 * Runs at phase {@code Integer.MAX_VALUE - 100}: starts late (after wire-client
 * registry), stops early (before wire-client registry closes).
 *
 * <h3>Template rendering</h3>
 * The supplied {@link TemplateRenderer} is forwarded to the internal {@link CheckRunner}
 * and used for all {@code templateFile} body rendering. Resolution policy
 * (user-provided bean vs. internal default) is the responsibility of the
 * Spring auto-configuration.
 */
@Slf4j
public class MonitorEngine implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    private final MonitorConfigProvider configProvider;
    private final MonitorTransport transport;
    private final ValidatorRegistry validatorRegistry;
    private final TemplateRenderer templateRenderer;
    private final List<MonitorResultListener> listeners;
    private final Duration shutdownTimeout;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final Map<String, CheckRunState> checkRunStates = new HashMap<>();
    private final Set<Thread> activeVirtualThreads = ConcurrentHashMap.newKeySet();
    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;

    /**
     * Creates a new monitor engine.
     *
     * @param configProvider    the configuration provider; never {@code null}
     * @param transport         the transport implementation; never {@code null}
     * @param validatorRegistry the validator registry; never {@code null}
     * @param templateRenderer  the template renderer; never {@code null}
     * @param listeners         the result listeners; never {@code null}, may be empty
     * @param shutdownTimeout   grace period for graceful shutdown; never {@code null}
     */
    public MonitorEngine(MonitorConfigProvider configProvider,
                         MonitorTransport transport,
                         ValidatorRegistry validatorRegistry,
                         TemplateRenderer templateRenderer,
                         List<MonitorResultListener> listeners,
                         Duration shutdownTimeout) {
        this.configProvider = configProvider;
        this.transport = transport;
        this.validatorRegistry = validatorRegistry;
        this.templateRenderer = templateRenderer;
        this.listeners = listeners != null ? listeners : List.of();
        this.shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(30);
    }

    // ── SmartLifecycle ──────────────────────────────────────────────────────

    @Override
    public void start() {
        if (running) {
            log.warn("MonitorEngine is already running");
            return;
        }

        log.info("Starting MonitorEngine...");

        List<ServiceConfig> services = configProvider.getServices();
        List<CheckConfig> checks = configProvider.getChecks();

        if (checks.isEmpty()) {
            log.warn("No checks configured; MonitorEngine will start but do nothing");
        }

        // Initialize transport
        transport.init(services);

        // Build service index
        Map<String, ServiceConfig> serviceIndex = new HashMap<>();
        for (ServiceConfig sc : services) {
            serviceIndex.put(sc.getServiceName(), sc);
        }

        // Create check runner
        CheckRunner checkRunner = new CheckRunner(
                transport, validatorRegistry, templateRenderer, listeners);

        // Start scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-scheduler");
            t.setDaemon(true);
            return t;
        });

        for (CheckConfig check : checks) {
            ServiceConfig service = serviceIndex.get(check.getServiceName());
            if (service == null) {
                log.error("Check '{}' references unknown service '{}'; skipping",
                        check.getCheckName(), check.getServiceName());
                continue;
            }

            CheckRunState state = new CheckRunState(
                    new AtomicBoolean(false),
                    new AtomicLong(0),
                    new AtomicReference<>(null)
            );
            checkRunStates.put(check.getCheckName(), state);

            Duration interval = check.getInterval();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> dispatchCheckRun(check, service, checkRunner, state),
                    0,
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            scheduledTasks.put(check.getCheckName(), future);

            log.info("Scheduled check '{}' → service '{}' every {}",
                    check.getCheckName(), service.getServiceName(), interval);
        }

        running = true;
        log.info("MonitorEngine started: {} checks scheduled", checks.size());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping MonitorEngine...");
        Instant shutdownStart = Instant.now();

        // 1. Stop scheduler (no new triggers)
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                long remainingMs = remainingMs(shutdownStart, shutdownTimeout);
                if (!scheduler.awaitTermination(remainingMs, TimeUnit.MILLISECONDS)) {
                    log.warn("Scheduler did not terminate within {}ms; forcing shutdown", remainingMs);
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }

        // 2. Wait for in-flight virtual threads, then interrupt remaining
        if (!activeVirtualThreads.isEmpty()) {
            log.info("Waiting for {} in-flight check run(s) to complete...",
                    activeVirtualThreads.size());

            long remainingMs = remainingMs(shutdownStart, shutdownTimeout);
            long perThreadMs = activeVirtualThreads.isEmpty()
                    ? remainingMs
                    : Math.max(remainingMs / activeVirtualThreads.size(), 100);

            for (Thread vt : activeVirtualThreads) {
                try {
                    vt.join(perThreadMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Interrupt any still alive after grace period
            for (Thread vt : activeVirtualThreads) {
                if (vt.isAlive()) {
                    log.warn("Interrupting check thread '{}' after shutdown grace period", vt.getName());
                    vt.interrupt();
                }
            }
        }

        // 3. Close transport with remaining timeout
        Duration remainingTimeout = remaining(shutdownStart, shutdownTimeout);
        try {
            log.debug("Closing transport with remaining timeout: {}", remainingTimeout);
            transport.close(remainingTimeout);
        } catch (Exception e) {
            log.error("Error closing transport: {}", e.getMessage(), e);
        }

        scheduledTasks.clear();
        checkRunStates.clear();
        activeVirtualThreads.clear();
        running = false;

        log.info("MonitorEngine stopped");
    }

    private static long remainingMs(Instant start, Duration timeout) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        return Math.max(timeout.toMillis() - elapsed, 0);
    }

    private static Duration remaining(Instant start, Duration timeout) {
        return Duration.ofMillis(remainingMs(start, timeout));
    }

    // ── Check dispatch ──────────────────────────────────────────────────────

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── Shutdown helpers ────────────────────────────────────────────────────

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    private void dispatchCheckRun(CheckConfig check,
                                  ServiceConfig service,
                                  CheckRunner checkRunner,
                                  CheckRunState state) {
        String requestId = UUID.randomUUID().toString();

        if (!state.running().compareAndSet(false, true)) {
            long skips = state.skipCount().incrementAndGet();
            String inFlightRequestId = state.currentRequestId().get();
            if (skips == 1) {
                log.warn("Check '{}' is still running (requestId={}); "
                                + "skipping scheduled trigger at {} (first skip)",
                        check.getCheckName(), inFlightRequestId, Instant.now());
            } else if (skips % 10 == 0) {
                log.warn("Check '{}' has been skipped {} times "
                                + "(still running, requestId={})",
                        check.getCheckName(), skips, inFlightRequestId);
            } else {
                log.debug("Check '{}' skip #{} (requestId={})",
                        check.getCheckName(), skips, inFlightRequestId);
            }
            return;
        }

        state.currentRequestId().set(requestId);

        Thread.ofVirtual()
                .name("check-" + check.getCheckName())
                .start(() -> {
                    Thread currentThread = Thread.currentThread();
                    activeVirtualThreads.add(currentThread);
                    try {
                        checkRunner.execute(check, service, requestId);
                    } catch (Exception e) {
                        log.error("Unhandled exception in check '{}': {}",
                                check.getCheckName(), e.getMessage(), e);
                    } finally {
                        activeVirtualThreads.remove(currentThread);
                        state.running().set(false);
                    }
                });
    }

    // ── Internal types ──────────────────────────────────────────────────────

    /**
     * Mutable state for a single check's scheduling lifecycle.
     *
     * @param running          guard flag for skip-if-running
     * @param skipCount        cumulative skip counter (for log aggregation)
     * @param currentRequestId requestId of the currently in-flight check run
     *                         (for skip-if-running diagnostics per spec §2.5.3)
     */
    private record CheckRunState(
            AtomicBoolean running,
            AtomicLong skipCount,
            AtomicReference<String> currentRequestId
    ) {
    }
}