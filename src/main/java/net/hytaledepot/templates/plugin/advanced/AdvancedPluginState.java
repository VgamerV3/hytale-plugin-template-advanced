package net.hytaledepot.templates.plugin.advanced;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedPluginState {
  private volatile AdvancedPluginLifecycle lifecycle = AdvancedPluginLifecycle.NEW;
  private volatile String templateName = "Advanced";
  private volatile String dataDirectory = "unset";

  private final AtomicBoolean setupCompleted = new AtomicBoolean(false);
  private final AtomicBoolean demoFlagEnabled = new AtomicBoolean(false);
  private final AtomicLong commandRequests = new AtomicLong();
  private final AtomicLong statusRequests = new AtomicLong();
  private final AtomicLong errorCount = new AtomicLong();

  public AdvancedPluginLifecycle getLifecycle() {
    return lifecycle;
  }

  public void setLifecycle(AdvancedPluginLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  public String getTemplateName() {
    return templateName;
  }

  public void setTemplateName(String templateName) {
    this.templateName = String.valueOf(templateName == null ? "" : templateName).trim();
  }

  public String getDataDirectory() {
    return dataDirectory;
  }

  public void setDataDirectory(String dataDirectory) {
    this.dataDirectory = String.valueOf(dataDirectory == null ? "" : dataDirectory).trim();
  }

  public boolean isSetupCompleted() {
    return setupCompleted.get();
  }

  public void markSetupCompleted() {
    setupCompleted.set(true);
  }

  public boolean isDemoFlagEnabled() {
    return demoFlagEnabled.get();
  }

  public boolean toggleDemoFlag() {
    boolean next = !demoFlagEnabled.get();
    demoFlagEnabled.set(next);
    return next;
  }

  public long incrementCommandRequests() {
    return commandRequests.incrementAndGet();
  }

  public long incrementStatusRequests() {
    return statusRequests.incrementAndGet();
  }

  public long incrementErrorCount() {
    return errorCount.incrementAndGet();
  }

  public long getCommandRequests() {
    return commandRequests.get();
  }

  public long getStatusRequests() {
    return statusRequests.get();
  }

  public long getErrorCount() {
    return errorCount.get();
  }
}
