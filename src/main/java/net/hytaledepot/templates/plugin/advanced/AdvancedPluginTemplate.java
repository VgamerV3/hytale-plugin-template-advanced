package net.hytaledepot.templates.plugin.advanced;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedPluginTemplate extends JavaPlugin {
  // Rolling quota window used by command anti-spam checks.
  private static final long RATE_WINDOW_SECONDS = 5;
  private static final int RATE_WINDOW_MAX_CALLS = 6;
  // Cache license decisions briefly to avoid repeated network validation calls.
  private static final long LICENSE_CACHE_SECONDS = 45;
  // Periodic maintenance intervals.
  private static final long SNAPSHOT_INTERVAL_SECONDS = 30;
  private static final long SWEEP_INTERVAL_SECONDS = 20;

  // Sender-scoped command windows for rate limiting.
  private final Map<String, Deque<Long>> commandWindows = new ConcurrentHashMap<>();
  // Keyed by asset|license|server to avoid duplicate validations.
  private final Map<String, LicenseCacheEntry> licenseCache = new ConcurrentHashMap<>();
  // Runtime counters exposed via status command and snapshot output.
  private final Map<String, AtomicLong> metrics = new ConcurrentHashMap<>();
  // Lightweight in-memory audit ring for operator diagnostics.
  private final Deque<String> auditTrail = new ArrayDeque<>();

  private final AtomicBoolean snapshotInProgress = new AtomicBoolean(false);
  // Dedicated worker used by heartbeat, sweeper, and snapshot jobs.
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-advanced-plugin-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile ScheduledFuture<?> snapshotTask;
  private volatile ScheduledFuture<?> sweepTask;
  private Path snapshotPath;

  public AdvancedPluginTemplate(JavaPluginInit init) {
    super(init);
  }

  @Override
  public CompletableFuture<Void> preLoad() {
    getLogger().atInfo().log("[%s] preLoad -> %s", getClass().getSimpleName(), getIdentifier());
    return CompletableFuture.completedFuture(null);
  }

  @Override
  protected void setup() {
    snapshotPath = getDataDirectory().resolve("advanced-plugin-state.properties");

    ensureMetric("commandAllowed");
    ensureMetric("commandBlocked");
    ensureMetric("licenseChecks");
    ensureMetric("licenseAllowed");
    ensureMetric("licenseDenied");
    ensureMetric("licenseCacheHit");
    ensureMetric("heartbeats");

    restoreSnapshot();
    appendAudit("plugin.setup", "snapshotPath=" + snapshotPath);

    getCommandRegistry().registerCommand(new AdvancedStatusCommand());
    getCommandRegistry().registerCommand(new AdvancedFlushCommand());
  }

  @Override
  protected void start() {
    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              long beats = incrementMetric("heartbeats");
              if (beats % 60 == 0) {
                appendAudit("heartbeat", "count=" + beats);
                getLogger().atInfo().log("[Advanced] heartbeat=%d", beats);
              }
            },
            0,
            1,
            TimeUnit.SECONDS);

    snapshotTask =
        scheduler.scheduleAtFixedRate(
            this::flushSnapshot,
            SNAPSHOT_INTERVAL_SECONDS,
            SNAPSHOT_INTERVAL_SECONDS,
            TimeUnit.SECONDS);

    sweepTask =
        scheduler.scheduleAtFixedRate(
            this::sweepExpiredState,
            SWEEP_INTERVAL_SECONDS,
            SWEEP_INTERVAL_SECONDS,
            TimeUnit.SECONDS);

    // Kept as explicit lifecycle marker so startup workflows can hook it if needed.
    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
    appendAudit("plugin.start", "scheduler_started=true");
  }

  @Override
  protected void shutdown() {
    cancelTask(heartbeatTask);
    cancelTask(snapshotTask);
    cancelTask(sweepTask);

    flushSnapshot();
    scheduler.shutdownNow();

    commandWindows.clear();
    licenseCache.clear();
    appendAudit("plugin.shutdown", "state_cleared=true");
    synchronized (auditTrail) {
      auditTrail.clear();
    }
  }

  public boolean tryConsumeCommandQuota(String source, long nowEpochSeconds) {
    String sender = normalizeSource(source);
    Deque<Long> window = commandWindows.computeIfAbsent(sender, key -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && nowEpochSeconds - window.peekFirst() > RATE_WINDOW_SECONDS) {
        window.pollFirst();
      }
      if (window.size() >= RATE_WINDOW_MAX_CALLS) {
        incrementMetric("commandBlocked");
        appendAudit("quota.blocked", "source=" + sender + ", windowSize=" + window.size());
        return false;
      }
      window.addLast(nowEpochSeconds);
      incrementMetric("commandAllowed");
      appendAudit("quota.allowed", "source=" + sender + ", windowSize=" + window.size());
      return true;
    }
  }

  public LicenseDecision validateLicense(String assetId, String licenseKey, String serverIpv4, long nowEpochSeconds) {
    incrementMetric("licenseChecks");

    String cacheKey = String.format("%s|%s|%s", String.valueOf(assetId), String.valueOf(licenseKey), String.valueOf(serverIpv4));
    LicenseCacheEntry cached = licenseCache.get(cacheKey);
    if (cached != null && cached.expiresAtEpochSeconds >= nowEpochSeconds) {
      incrementMetric("licenseCacheHit");
      appendAudit("license.cache_hit", "asset=" + assetId + ", server=" + serverIpv4);
      if (cached.decision.allowed) {
        incrementMetric("licenseAllowed");
      } else {
        incrementMetric("licenseDenied");
      }
      return cached.decision;
    }

    String nonce = Long.toHexString(System.nanoTime());
    String payload = buildLicensePayload(assetId, licenseKey, serverIpv4, nonce, nowEpochSeconds);
    String rawResponse = emulateLicenseApiResponse(payload, licenseKey, nowEpochSeconds);

    LicenseDecision decision = parseLicenseDecision(rawResponse, nowEpochSeconds);
    long cacheExpiry = Math.max(nowEpochSeconds, decision.expiresAtEpochSeconds) + LICENSE_CACHE_SECONDS;
    licenseCache.put(cacheKey, new LicenseCacheEntry(decision, cacheExpiry));
    appendAudit(
        "license.validated",
        "asset=" + assetId + ", allowed=" + decision.allowed + ", reason=" + decision.reasonCode);

    if (decision.allowed) {
      incrementMetric("licenseAllowed");
    } else {
      incrementMetric("licenseDenied");
    }

    return decision;
  }

  private String buildLicensePayload(String assetId, String licenseKey, String serverIpv4, String nonce, long nowEpochSeconds) {
    return "{"
        + "\"asset_id\":\"" + String.valueOf(assetId) + "\"," 
        + "\"license_key\":\"" + String.valueOf(licenseKey) + "\"," 
        + "\"server_ip\":\"" + String.valueOf(serverIpv4) + "\"," 
        + "\"timestamp\":" + nowEpochSeconds + ","
        + "\"nonce\":\"" + nonce + "\""
        + "}";
  }

  private String emulateLicenseApiResponse(String payload, String licenseKey, long nowEpochSeconds) {
    boolean formatOk = String.valueOf(licenseKey).startsWith("HD-") && String.valueOf(licenseKey).length() >= 12;
    boolean hasPayload = payload.contains("\"asset_id\"") && payload.contains("\"server_ip\"") && payload.contains("\"nonce\"");

    boolean allowed = formatOk && hasPayload;
    String reasonCode = allowed ? "ok" : "invalid_license_key";
    long expires = nowEpochSeconds + (allowed ? 300 : 30);

    return "{"
        + "\"allowed\":" + allowed + ","
        + "\"reason_code\":\"" + reasonCode + "\"," 
        + "\"expires_epoch\":" + expires
        + "}";
  }

  private LicenseDecision parseLicenseDecision(String response, long fallbackNowEpochSeconds) {
    boolean allowed = response.contains("\"allowed\":true");
    String reasonCode = extractJsonString(response, "reason_code", allowed ? "ok" : "unknown");
    long expiresEpoch = extractJsonLong(response, "expires_epoch", fallbackNowEpochSeconds + 60);
    return new LicenseDecision(allowed, reasonCode, expiresEpoch, response);
  }

  private static String extractJsonString(String json, String key, String fallback) {
    String marker = "\"" + key + "\":\"";
    int start = json.indexOf(marker);
    if (start < 0) {
      return fallback;
    }
    int valueStart = start + marker.length();
    int valueEnd = json.indexOf('"', valueStart);
    if (valueEnd < 0) {
      return fallback;
    }
    return json.substring(valueStart, valueEnd);
  }

  private static long extractJsonLong(String json, String key, long fallback) {
    String marker = "\"" + key + "\":";
    int start = json.indexOf(marker);
    if (start < 0) {
      return fallback;
    }
    int valueStart = start + marker.length();
    int valueEnd = valueStart;
    while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
      valueEnd++;
    }
    if (valueEnd <= valueStart) {
      return fallback;
    }
    try {
      return Long.parseLong(json.substring(valueStart, valueEnd));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private void sweepExpiredState() {
    long now = Instant.now().getEpochSecond();

    licenseCache.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochSeconds < now);

    commandWindows.entrySet().removeIf(
        entry -> {
          Deque<Long> window = entry.getValue();
          synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > RATE_WINDOW_SECONDS) {
              window.pollFirst();
            }
            return window.isEmpty();
          }
        });
    appendAudit("state.sweep", "windows=" + commandWindows.size() + ", cache=" + licenseCache.size());
  }

  private void flushSnapshot() {
    if (!snapshotInProgress.compareAndSet(false, true)) {
      return;
    }

    try {
      if (snapshotPath == null) {
        return;
      }
      Files.createDirectories(snapshotPath.getParent());

      Properties props = new Properties();
      for (Map.Entry<String, AtomicLong> entry : metrics.entrySet()) {
        props.setProperty("metric." + entry.getKey(), String.valueOf(entry.getValue().get()));
      }
      props.setProperty("runtime.commandWindows", String.valueOf(commandWindows.size()));
      props.setProperty("runtime.licenseCache", String.valueOf(licenseCache.size()));
      props.setProperty("runtime.auditEntries", String.valueOf(auditSize()));
      props.setProperty("runtime.updatedAt", String.valueOf(Instant.now().getEpochSecond()));

      try (OutputStream out =
          Files.newOutputStream(
              snapshotPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        props.store(out, "Advanced plugin runtime snapshot");
      }
      appendAudit("snapshot.flush", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.flush", "ok=false reason=" + exception.getMessage());
      getLogger().atInfo().log("[Advanced] snapshot write failed: %s", exception.getMessage());
    } finally {
      snapshotInProgress.set(false);
    }
  }

  private void restoreSnapshot() {
    if (snapshotPath == null || !Files.exists(snapshotPath)) {
      return;
    }

    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(snapshotPath)) {
      props.load(in);
      for (String key : props.stringPropertyNames()) {
        if (!key.startsWith("metric.")) {
          continue;
        }
        String metricKey = key.substring("metric.".length());
        long value = parseLong(props.getProperty(key), 0L);
        metrics.computeIfAbsent(metricKey, ignored -> new AtomicLong()).set(value);
      }
      appendAudit("snapshot.restore", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.restore", "ok=false reason=" + exception.getMessage());
      getLogger().atInfo().log("[Advanced] snapshot read failed: %s", exception.getMessage());
    }
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String normalizeSource(String value) {
    String source = String.valueOf(value).trim();
    if (source.isEmpty()) {
      return "anonymous";
    }
    return source;
  }

  private AtomicLong ensureMetric(String key) {
    return metrics.computeIfAbsent(key, ignored -> new AtomicLong());
  }

  private long incrementMetric(String key) {
    return ensureMetric(key).incrementAndGet();
  }

  private long metricValue(String key) {
    return ensureMetric(key).get();
  }

  private static void cancelTask(ScheduledFuture<?> task) {
    if (task != null) {
      task.cancel(true);
    }
  }

  private void appendAudit(String action, String details) {
    String line = Instant.now().getEpochSecond() + " " + action + " " + String.valueOf(details);
    synchronized (auditTrail) {
      auditTrail.addLast(line);
      while (auditTrail.size() > 120) {
        auditTrail.pollFirst();
      }
    }
  }

  private int auditSize() {
    synchronized (auditTrail) {
      return auditTrail.size();
    }
  }

  private String latestAuditEntry() {
    synchronized (auditTrail) {
      return auditTrail.peekLast() == null ? "none" : auditTrail.peekLast();
    }
  }

  private final class AdvancedStatusCommand extends AbstractCommand {
    private AdvancedStatusCommand() {
      super("hdadvancedstatus", "Shows advanced runtime, limiter, and license state.");
    setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
      String sender = normalizeSource(ctx.sender().getDisplayName());
      long now = Instant.now().getEpochSecond();

      if (!tryConsumeCommandQuota(sender, now)) {
        ctx.sendMessage(Message.raw("[Advanced] Rate limit active for sender=" + sender));
        return CompletableFuture.completedFuture(null);
      }

      String demoKey = "HD-DEMO-" + Math.abs(sender.hashCode());
      LicenseDecision decision = validateLicense("advanced-demo", demoKey, "127.0.0.1", now);

      String status =
          "[Advanced] sender=" + sender
              + ", allowed=" + decision.allowed
              + ", reason=" + decision.reasonCode
              + ", expires=" + decision.expiresAtEpochSeconds
              + ", commandAllowed=" + metricValue("commandAllowed")
              + ", commandBlocked=" + metricValue("commandBlocked")
              + ", licenseChecks=" + metricValue("licenseChecks")
              + ", cacheHits=" + metricValue("licenseCacheHit")
              + ", windows=" + commandWindows.size()
              + ", cache=" + licenseCache.size()
              + ", auditEntries=" + auditSize()
              + ", latestAudit=" + latestAuditEntry();

      ctx.sendMessage(Message.raw(status));
      return CompletableFuture.completedFuture(null);
    }
  }

  private final class AdvancedFlushCommand extends AbstractCommand {
    private AdvancedFlushCommand() {
      super("hdadvancedflush", "Writes an immediate runtime snapshot for diagnostics.");
    setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
      flushSnapshot();
      ctx.sendMessage(
          Message.raw(
              "[Advanced] Snapshot flushed. metrics="
                  + metrics.size()
                  + ", windows="
                  + commandWindows.size()
                  + ", cache="
                  + licenseCache.size()
                  + ", auditEntries="
                  + auditSize()));
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class LicenseCacheEntry {
    private final LicenseDecision decision;
    private final long expiresAtEpochSeconds;

    private LicenseCacheEntry(LicenseDecision decision, long expiresAtEpochSeconds) {
      this.decision = decision;
      this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }
  }

  public static final class LicenseDecision {
    private final boolean allowed;
    private final String reasonCode;
    private final long expiresAtEpochSeconds;
    private final String rawResponse;

    private LicenseDecision(boolean allowed, String reasonCode, long expiresAtEpochSeconds, String rawResponse) {
      this.allowed = allowed;
      this.reasonCode = reasonCode;
      this.expiresAtEpochSeconds = expiresAtEpochSeconds;
      this.rawResponse = rawResponse;
    }

    public boolean isAllowed() {
      return allowed;
    }

    public String getReasonCode() {
      return reasonCode;
    }

    public long getExpiresAtEpochSeconds() {
      return expiresAtEpochSeconds;
    }

    public String getRawResponse() {
      return rawResponse;
    }
  }
}
