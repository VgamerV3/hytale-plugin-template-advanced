package net.hytaledepot.templates.plugin.advanced;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedPluginTemplate extends JavaPlugin {
  private static final long MAINTENANCE_INTERVAL_SECONDS = 8;

  private final AdvancedPluginState state = new AdvancedPluginState();
  private final AdvancedDemoService demoService = new AdvancedDemoService();
  private final AtomicLong heartbeatTicks = new AtomicLong();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-advanced-plugin-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile ScheduledFuture<?> maintenanceTask;
  private volatile long startedAtEpochMillis;

  public AdvancedPluginTemplate(JavaPluginInit init) {
    super(init);
  }

  @Override
  public CompletableFuture<Void> preLoad() {
    state.setLifecycle(AdvancedPluginLifecycle.PRELOADING);
    getLogger().atInfo().log("[Advanced] preLoad -> %s", getIdentifier());
    return CompletableFuture.completedFuture(null);
  }

  @Override
  protected void setup() {
    state.setLifecycle(AdvancedPluginLifecycle.SETTING_UP);
    state.setTemplateName("Advanced");
    state.setDataDirectory(getDataDirectory().toString());

    demoService.initialize(getDataDirectory());
    state.markSetupCompleted();

    getCommandRegistry()
        .registerCommand(
            new AdvancedStatusCommand(
                state,
                demoService,
                heartbeatTicks,
                this::uptimeSeconds,
                this::isHeartbeatActive,
                this::isMaintenanceActive));
    getCommandRegistry().registerCommand(new AdvancedDemoCommand(state, demoService, heartbeatTicks));
    getCommandRegistry().registerCommand(new AdvancedFlushCommand(demoService));

    state.setLifecycle(AdvancedPluginLifecycle.READY);
  }

  @Override
  protected void start() {
    state.setLifecycle(AdvancedPluginLifecycle.RUNNING);
    startedAtEpochMillis = System.currentTimeMillis();

    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                long tick = heartbeatTicks.incrementAndGet();
                demoService.onHeartbeat(tick);
                if (tick % 60 == 0) {
                  getLogger().atInfo().log("[Advanced] heartbeat=%d", tick);
                }
              } catch (Exception exception) {
                state.incrementErrorCount();
                state.setLifecycle(AdvancedPluginLifecycle.FAILED);
                getLogger().atInfo().log("[Advanced] heartbeat failed: %s", exception.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    maintenanceTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                demoService.maintenanceSweep();
              } catch (Exception exception) {
                state.incrementErrorCount();
                getLogger().atInfo().log("[Advanced] maintenance sweep failed: %s", exception.getMessage());
              }
            },
            MAINTENANCE_INTERVAL_SECONDS,
            MAINTENANCE_INTERVAL_SECONDS,
            TimeUnit.SECONDS);

    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
  }

  @Override
  protected void shutdown() {
    state.setLifecycle(AdvancedPluginLifecycle.STOPPING);

    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }
    if (maintenanceTask != null) {
      maintenanceTask.cancel(true);
    }

    demoService.flushSnapshot();
    scheduler.shutdownNow();
    demoService.shutdown();
    state.setLifecycle(AdvancedPluginLifecycle.STOPPED);
  }

  private long uptimeSeconds() {
    if (startedAtEpochMillis <= 0L) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAtEpochMillis) / 1000L);
  }

  private boolean isHeartbeatActive() {
    return heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone();
  }

  private boolean isMaintenanceActive() {
    return maintenanceTask != null && !maintenanceTask.isCancelled() && !maintenanceTask.isDone();
  }
}
