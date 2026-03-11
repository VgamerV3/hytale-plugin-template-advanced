package net.hytaledepot.templates.plugin.advanced;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedDemoService {
  private static final long RATE_WINDOW_SECONDS = 5;
  private static final int RATE_WINDOW_MAX_ACTIONS = 8;
  private static final int JOB_RETRY_LIMIT = 3;
  private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
  private static final long CIRCUIT_OPEN_SECONDS = 30;
  private static final long LICENSE_CACHE_SECONDS = 45;
  private static final int AUDIT_TRAIL_LIMIT = 240;

  private final Map<String, Deque<Long>> actionWindows = new ConcurrentHashMap<>();
  private final Map<String, JobTicket> jobsById = new ConcurrentHashMap<>();
  private final Deque<String> pendingJobOrder = new ArrayDeque<>();
  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private final Map<String, String> lastActionBySender = new ConcurrentHashMap<>();
  private final Deque<String> auditTrail = new ArrayDeque<>();

  private final Map<String, String> licenseBindingByKey = new ConcurrentHashMap<>();
  private final Map<String, LicenseCacheEntry> licenseCache = new ConcurrentHashMap<>();

  private final AtomicLong jobSequence = new AtomicLong();
  private final AtomicLong providerFailures = new AtomicLong();
  private final AtomicLong circuitOpenUntilEpochSeconds = new AtomicLong();

  private volatile Path snapshotPath;

  public void initialize(Path dataDirectory) {
    snapshotPath = dataDirectory.resolve("advanced-plugin-state.properties");

    ensureCounter("heartbeat");
    ensureCounter("jobsQueued");
    ensureCounter("jobsCompleted");
    ensureCounter("jobsRetried");
    ensureCounter("jobsDropped");
    ensureCounter("quotaBlocked");
    ensureCounter("licenseChecks");
    ensureCounter("licenseAllowed");
    ensureCounter("licenseDenied");
    ensureCounter("licenseCacheHit");
    ensureCounter("snapshotWrites");

    restoreSnapshot();
    appendAudit("service.initialize", "snapshotPath=" + snapshotPath);
  }

  public void onHeartbeat(long tick) {
    incrementCounter("heartbeat");
    if (tick % 20 == 0) {
      processSingleJob(Instant.now().getEpochSecond());
    }
  }

  public void maintenanceSweep() {
    long now = Instant.now().getEpochSecond();

    actionWindows.entrySet().removeIf(entry -> {
      Deque<Long> window = entry.getValue();
      synchronized (window) {
        while (!window.isEmpty() && now - window.peekFirst() > RATE_WINDOW_SECONDS) {
          window.pollFirst();
        }
        return window.isEmpty();
      }
    });

    licenseCache.entrySet().removeIf(entry -> entry.getValue().cachedUntilEpochSeconds < now);

    long openUntil = circuitOpenUntilEpochSeconds.get();
    if (openUntil > 0 && openUntil <= now) {
      circuitOpenUntilEpochSeconds.set(0);
      providerFailures.set(0);
      appendAudit("provider.circuit_closed", "closed_at=" + now);
    }
  }

  public String applyAction(
      AdvancedPluginState state,
      String sender,
      String action,
      String[] args,
      long heartbeatTicks) {
    long now = Instant.now().getEpochSecond();
    String normalizedSender = normalizeSender(sender);
    String normalizedAction = normalizeAction(action);

    if (!isReadOnlyAction(normalizedAction) && !consumeQuota(normalizedSender, now)) {
      incrementCounter("quotaBlocked");
      return "[Advanced] Rate limit active for sender="
          + normalizedSender
          + " (max "
          + RATE_WINDOW_MAX_ACTIONS
          + " actions / "
          + RATE_WINDOW_SECONDS
          + "s).";
    }

    lastActionBySender.put(normalizedSender, normalizedAction);

    switch (normalizedAction) {
      case "info":
      case "status":
        return "[Advanced] " + diagnostics();
      case "toggle":
        boolean enabled = state.toggleDemoFlag();
        appendAudit("state.toggle", "demoFlag=" + enabled);
        return "[Advanced] demoFlag=" + enabled + ", heartbeatTicks=" + heartbeatTicks;
      case "queue-job":
      case "queue":
        return "[Advanced] queued " + queueJob("generic", normalizedSender, String.join(" ", args), now);
      case "queue-fragile":
        return "[Advanced] queued " + queueJob("fragile", normalizedSender, String.join(" ", args), now);
      case "process-job":
        return "[Advanced] " + processSingleJob(now);
      case "drain-jobs":
        return "[Advanced] " + drainJobs(now, 3);
      case "simulate-provider-failure":
        registerProviderFailure(now, "manual");
        return "[Advanced] provider failure registered. failures=" + providerFailures.get();
      case "recover-provider":
        circuitOpenUntilEpochSeconds.set(0);
        providerFailures.set(0);
        appendAudit("provider.recovered", "manual=true");
        return "[Advanced] provider circuit reset.";
      case "validate-license":
        return "[Advanced] " + runLicenseValidation(args, now);
      case "bind-license":
        return "[Advanced] " + bindLicense(args);
      case "flush-snapshot":
      case "flush":
        flushSnapshot();
        return "[Advanced] snapshot flushed.";
      case "audit":
        return "[Advanced] latestAudit=" + latestAuditEntry();
      default:
        return "[Advanced] unknown action='"
            + normalizedAction
            + "' (try: info, toggle, queue-job, queue-fragile, process-job, drain-jobs, "
            + "simulate-provider-failure, recover-provider, validate-license, bind-license, flush-snapshot, audit)";
    }
  }

  public String describeLastAction(String sender) {
    return lastActionBySender.getOrDefault(normalizeSender(sender), "none");
  }

  public String diagnostics() {
    long pendingCount;
    synchronized (pendingJobOrder) {
      pendingCount = pendingJobOrder.size();
    }

    long openUntil = circuitOpenUntilEpochSeconds.get();
    long now = Instant.now().getEpochSecond();
    String breakerState = openUntil > now ? ("OPEN(until=" + openUntil + ")") : "CLOSED";

    return "jobsQueued="
        + counterValue("jobsQueued")
        + ", jobsCompleted="
        + counterValue("jobsCompleted")
        + ", jobsRetried="
        + counterValue("jobsRetried")
        + ", jobsDropped="
        + counterValue("jobsDropped")
        + ", pendingJobs="
        + pendingCount
        + ", breaker="
        + breakerState
        + ", providerFailures="
        + providerFailures.get()
        + ", licenseBindings="
        + licenseBindingByKey.size()
        + ", licenseCache="
        + licenseCache.size()
        + ", quotaBlocked="
        + counterValue("quotaBlocked");
  }

  public String latestAuditEntry() {
    synchronized (auditTrail) {
      return auditTrail.peekLast() == null ? "none" : auditTrail.peekLast();
    }
  }

  public void flushSnapshot() {
    if (snapshotPath == null) {
      return;
    }

    try {
      Files.createDirectories(snapshotPath.getParent());

      Properties props = new Properties();
      for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
        props.setProperty("counter." + entry.getKey(), String.valueOf(entry.getValue().get()));
      }
      props.setProperty("runtime.pendingJobs", String.valueOf(pendingJobCount()));
      props.setProperty("runtime.providerFailures", String.valueOf(providerFailures.get()));
      props.setProperty("runtime.circuitOpenUntil", String.valueOf(circuitOpenUntilEpochSeconds.get()));
      props.setProperty("runtime.bindings", String.valueOf(licenseBindingByKey.size()));
      props.setProperty("runtime.cache", String.valueOf(licenseCache.size()));
      props.setProperty("runtime.updatedAt", String.valueOf(Instant.now().getEpochSecond()));

      try (OutputStream out =
          Files.newOutputStream(
              snapshotPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        props.store(out, "Advanced plugin snapshot");
      }
      incrementCounter("snapshotWrites");
      appendAudit("snapshot.write", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.write", "ok=false reason=" + exception.getMessage());
    }
  }

  public void shutdown() {
    flushSnapshot();
    actionWindows.clear();
    jobsById.clear();
    synchronized (pendingJobOrder) {
      pendingJobOrder.clear();
    }
    licenseCache.clear();
    lastActionBySender.clear();
  }

  private String runLicenseValidation(String[] args, long now) {
    String licenseKey = args.length >= 1 ? args[0] : "HD-DEMO-LICENSE";
    String serverIpv4 = args.length >= 2 ? args[1] : "127.0.0.1";

    LicenseDecision decision = validateLicense("advanced-template", licenseKey, serverIpv4, now);
    return "license allowed="
        + decision.allowed
        + ", reason="
        + decision.reasonCode
        + ", expires="
        + decision.expiresAtEpochSeconds;
  }

  private String bindLicense(String[] args) {
    String licenseKey = args.length >= 1 ? args[0] : "HD-DEMO-LICENSE";
    String serverIpv4 = args.length >= 2 ? args[1] : "127.0.0.1";

    if (!isLicenseKeyFormatValid(licenseKey)) {
      return "bind failed: invalid key format.";
    }

    String existing = licenseBindingByKey.get(licenseKey);
    if (existing != null && !existing.equals(serverIpv4)) {
      return "bind failed: already bound to " + existing;
    }

    licenseBindingByKey.put(licenseKey, serverIpv4);
    appendAudit("license.bind", "key=" + licenseKey + " server=" + serverIpv4);
    return "license bound key=" + licenseKey + " server=" + serverIpv4;
  }

  private LicenseDecision validateLicense(
      String assetId,
      String licenseKey,
      String serverIpv4,
      long nowEpochSeconds) {
    incrementCounter("licenseChecks");
    String cacheKey = assetId + "|" + licenseKey + "|" + serverIpv4;

    LicenseCacheEntry cached = licenseCache.get(cacheKey);
    if (cached != null && cached.cachedUntilEpochSeconds >= nowEpochSeconds) {
      incrementCounter("licenseCacheHit");
      if (cached.decision.allowed) {
        incrementCounter("licenseAllowed");
      } else {
        incrementCounter("licenseDenied");
      }
      appendAudit("license.cache_hit", "key=" + licenseKey);
      return cached.decision;
    }

    String reasonCode = "ok";
    boolean allowed = true;

    if (!isLicenseKeyFormatValid(licenseKey)) {
      allowed = false;
      reasonCode = "invalid_format";
    } else if (licenseKey.toUpperCase().contains("EXPIRED")) {
      allowed = false;
      reasonCode = "expired";
    } else {
      String boundServer = licenseBindingByKey.get(licenseKey);
      if (boundServer != null && !boundServer.equals(serverIpv4)) {
        allowed = false;
        reasonCode = "bound_to_other_server";
      }
    }

    long expiresAt = nowEpochSeconds + (allowed ? 300 : 45);
    LicenseDecision decision = new LicenseDecision(allowed, reasonCode, expiresAt);
    long cacheUntil = Math.max(expiresAt, nowEpochSeconds + LICENSE_CACHE_SECONDS);
    licenseCache.put(cacheKey, new LicenseCacheEntry(decision, cacheUntil));

    if (allowed) {
      incrementCounter("licenseAllowed");
    } else {
      incrementCounter("licenseDenied");
    }
    appendAudit("license.validate", "key=" + licenseKey + " allowed=" + allowed + " reason=" + reasonCode);
    return decision;
  }

  private String queueJob(String topic, String sender, String payload, long nowEpochSeconds) {
    long sequence = jobSequence.incrementAndGet();
    String id = String.format("job-%05d", sequence);
    JobTicket ticket =
        new JobTicket(
            id,
            normalizeAction(topic),
            sender,
            String.valueOf(payload == null ? "" : payload).trim(),
            nowEpochSeconds);
    jobsById.put(id, ticket);
    synchronized (pendingJobOrder) {
      pendingJobOrder.addLast(id);
    }
    incrementCounter("jobsQueued");
    appendAudit("job.queue", id + " topic=" + ticket.topic + " sender=" + sender);
    return id + " topic=" + ticket.topic;
  }

  private String drainJobs(long nowEpochSeconds, int maxJobs) {
    int limit = Math.max(1, maxJobs);
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < limit; i++) {
      String result = processSingleJob(nowEpochSeconds);
      if (i > 0) {
        builder.append(" | ");
      }
      builder.append(result);
      if (result.startsWith("no pending jobs")) {
        break;
      }
    }
    return builder.toString();
  }

  private String processSingleJob(long nowEpochSeconds) {
    long circuitOpenUntil = circuitOpenUntilEpochSeconds.get();
    if (circuitOpenUntil > nowEpochSeconds) {
      return "provider circuit open until " + circuitOpenUntil;
    }

    String jobId;
    synchronized (pendingJobOrder) {
      jobId = pendingJobOrder.pollFirst();
    }
    if (jobId == null) {
      return "no pending jobs";
    }

    JobTicket ticket = jobsById.get(jobId);
    if (ticket == null) {
      return "job " + jobId + " was already removed";
    }

    ticket.attempts++;
    if ("fragile".equals(ticket.topic) && ticket.attempts == 1) {
      registerProviderFailure(nowEpochSeconds, "fragile-first-attempt");
      if (ticket.attempts < JOB_RETRY_LIMIT) {
        synchronized (pendingJobOrder) {
          pendingJobOrder.addLast(ticket.id);
        }
        incrementCounter("jobsRetried");
        appendAudit("job.retry", ticket.id + " attempt=" + ticket.attempts);
        return "job " + ticket.id + " failed once and was queued for retry";
      }
    }

    if (ticket.attempts > JOB_RETRY_LIMIT) {
      jobsById.remove(ticket.id);
      incrementCounter("jobsDropped");
      appendAudit("job.drop", ticket.id + " attempts=" + ticket.attempts);
      return "job " + ticket.id + " dropped after retries";
    }

    jobsById.remove(ticket.id);
    incrementCounter("jobsCompleted");
    appendAudit("job.complete", ticket.id + " attempts=" + ticket.attempts);
    return "job " + ticket.id + " completed";
  }

  private void registerProviderFailure(long nowEpochSeconds, String reason) {
    long failures = providerFailures.incrementAndGet();
    if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
      long openUntil = nowEpochSeconds + CIRCUIT_OPEN_SECONDS;
      circuitOpenUntilEpochSeconds.set(openUntil);
      appendAudit("provider.circuit_open", "openUntil=" + openUntil + " failures=" + failures + " reason=" + reason);
      return;
    }
    appendAudit("provider.failure", "failures=" + failures + " reason=" + reason);
  }

  private boolean consumeQuota(String sender, long nowEpochSeconds) {
    Deque<Long> window = actionWindows.computeIfAbsent(sender, ignored -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && nowEpochSeconds - window.peekFirst() > RATE_WINDOW_SECONDS) {
        window.pollFirst();
      }
      if (window.size() >= RATE_WINDOW_MAX_ACTIONS) {
        return false;
      }
      window.addLast(nowEpochSeconds);
      return true;
    }
  }

  private int pendingJobCount() {
    synchronized (pendingJobOrder) {
      return pendingJobOrder.size();
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
        if (!key.startsWith("counter.")) {
          continue;
        }
        String counterKey = key.substring("counter.".length());
        long value = parseLong(props.getProperty(key), 0L);
        ensureCounter(counterKey).set(value);
      }
      providerFailures.set(parseLong(props.getProperty("runtime.providerFailures"), 0L));
      circuitOpenUntilEpochSeconds.set(parseLong(props.getProperty("runtime.circuitOpenUntil"), 0L));
      appendAudit("snapshot.restore", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.restore", "ok=false reason=" + exception.getMessage());
    }
  }

  private AtomicLong ensureCounter(String key) {
    return counters.computeIfAbsent(key, ignored -> new AtomicLong());
  }

  private long incrementCounter(String key) {
    return ensureCounter(key).incrementAndGet();
  }

  private long counterValue(String key) {
    return ensureCounter(key).get();
  }

  private void appendAudit(String action, String detail) {
    String line = Instant.now().getEpochSecond() + " " + action + " " + String.valueOf(detail);
    synchronized (auditTrail) {
      auditTrail.addLast(line);
      while (auditTrail.size() > AUDIT_TRAIL_LIMIT) {
        auditTrail.pollFirst();
      }
    }
  }

  private static boolean isReadOnlyAction(String action) {
    return Arrays.asList("info", "status", "audit").contains(normalizeAction(action));
  }

  private static boolean isLicenseKeyFormatValid(String key) {
    String value = String.valueOf(key == null ? "" : key).trim();
    return value.startsWith("HD-") && value.length() >= 12;
  }

  private static String normalizeSender(String sender) {
    String normalized = String.valueOf(sender == null ? "" : sender).trim();
    return normalized.isEmpty() ? "unknown" : normalized;
  }

  private static String normalizeAction(String action) {
    String normalized = String.valueOf(action == null ? "" : action).trim().toLowerCase();
    return normalized.isEmpty() ? "info" : normalized;
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static final class JobTicket {
    private final String id;
    private final String topic;
    private final String sender;
    private final String payload;
    private final long createdAtEpochSeconds;
    private int attempts;

    private JobTicket(
        String id,
        String topic,
        String sender,
        String payload,
        long createdAtEpochSeconds) {
      this.id = id;
      this.topic = topic;
      this.sender = sender;
      this.payload = payload;
      this.createdAtEpochSeconds = createdAtEpochSeconds;
      this.attempts = 0;
    }
  }

  private static final class LicenseCacheEntry {
    private final LicenseDecision decision;
    private final long cachedUntilEpochSeconds;

    private LicenseCacheEntry(LicenseDecision decision, long cachedUntilEpochSeconds) {
      this.decision = decision;
      this.cachedUntilEpochSeconds = cachedUntilEpochSeconds;
    }
  }

  private static final class LicenseDecision {
    private final boolean allowed;
    private final String reasonCode;
    private final long expiresAtEpochSeconds;

    private LicenseDecision(boolean allowed, String reasonCode, long expiresAtEpochSeconds) {
      this.allowed = allowed;
      this.reasonCode = reasonCode;
      this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }
  }
}
