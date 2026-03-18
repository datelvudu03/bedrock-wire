package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li>{@link #stop()} — stops scheduler, waits for in-flight runs, closes transport</li>
 * </ul>
 *
 * <h3>Scheduling</h3>
 * Uses a single-thread {@link ScheduledExecutorService} for timing triggers.
 * Each trigger dispatches the actual check run to a virtual thread via
 * {@link Thread#ofVirtual()}.
 *
 * <h3>Phase ordering</h3>
 * Runs at phase {@code Integer.MAX_VALUE - 100}: starts late (after wire-client
 * registry), stops early (before wire-client registry closes).
 */
@Slf4j
public class MonitorEngine implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    private final MonitorConfigProvider configProvider;
    private final MonitorTransport transport;
    private final ValidatorRegistry validatorRegistry;
    private final TemplateProcessor templateProcessor;
    private final List<MonitorResultListener> listeners;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final Map<String, AtomicBoolean> runningFlags = new HashMap<>();
    private final Map<String, AtomicLong> skipCounters = new HashMap<>();
    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;

    /**
     * Creates a new monitor engine.
     *
     * @param configProvider    the configuration provider; never {@code null}
     * @param transport         the transport implementation; never {@code null}
     * @param validatorRegistry the validator registry; never {@code null}
     * @param templateProcessor the template processor; never {@code null}
     * @param listeners         the result listeners; never {@code null}, may be empty
     */
    public MonitorEngine(MonitorConfigProvider configProvider,
                         MonitorTransport transport,
                         ValidatorRegistry validatorRegistry,
                         TemplateProcessor templateProcessor,
                         List<MonitorResultListener> listeners) {
        this.configProvider = configProvider;
        this.transport = transport;
        this.validatorRegistry = validatorRegistry;
        this.templateProcessor = templateProcessor;
        this.listeners = listeners != null ? listeners : List.of();
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
                transport, validatorRegistry, templateProcessor, listeners);

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

            AtomicBoolean runningFlag = new AtomicBoolean(false);
            AtomicLong skipCounter = new AtomicLong(0);
            runningFlags.put(check.getCheckName(), runningFlag);
            skipCounters.put(check.getCheckName(), skipCounter);

            Duration interval = check.getInterval();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> dispatchCheckRun(check, service, checkRunner, runningFlag, skipCounter),
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
        Duration shutdownTimeout = configProvider.getShutdownTimeout();

        // 1. Stop scheduler (no new triggers)
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Scheduler did not terminate within {}; forcing shutdown", shutdownTimeout);
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }

        // 2. Close transport
        try {
            transport.close(shutdownTimeout);
        } catch (Exception e) {
            log.error("Error closing transport: {}", e.getMessage(), e);
        }

        scheduledTasks.clear();
        runningFlags.clear();
        skipCounters.clear();
        running = false;

        log.info("MonitorEngine stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    // ── Check dispatch ──────────────────────────────────────────────────────

    private void dispatchCheckRun(CheckConfig check,
                                  ServiceConfig service,
                                  CheckRunner checkRunner,
                                  AtomicBoolean runningFlag,
                                  AtomicLong skipCounter) {
        if (!runningFlag.compareAndSet(false, true)) {
            long skips = skipCounter.incrementAndGet();
            if (skips == 1) {
                log.warn("Check '{}' is still running; skipping scheduled trigger (first skip)",
                        check.getCheckName());
            } else if (skips % 10 == 0) {
                log.warn("Check '{}' has been skipped {} times (still running)",
                        check.getCheckName(), skips);
            } else {
                log.debug("Check '{}' skip #{}", check.getCheckName(), skips);
            }
            return;
        }

        Thread.ofVirtual()
                .name("check-" + check.getCheckName())
                .start(() -> {
                    try {
                        checkRunner.execute(check, service);
                    } catch (Exception e) {
                        log.error("Unhandled exception in check '{}': {}",
                                check.getCheckName(), e.getMessage(), e);
                    } finally {
                        runningFlag.set(false);
                    }
                });
    }
}
