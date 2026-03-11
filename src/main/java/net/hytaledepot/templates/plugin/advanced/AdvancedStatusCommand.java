package net.hytaledepot.templates.plugin.advanced;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class AdvancedStatusCommand extends AbstractCommand {
  private final AdvancedPluginState state;
  private final AdvancedDemoService demoService;
  private final AtomicLong heartbeatTicks;
  private final LongSupplier uptimeSeconds;
  private final BooleanSupplier heartbeatActive;
  private final BooleanSupplier maintenanceActive;

  public AdvancedStatusCommand(
      AdvancedPluginState state,
      AdvancedDemoService demoService,
      AtomicLong heartbeatTicks,
      LongSupplier uptimeSeconds,
      BooleanSupplier heartbeatActive,
      BooleanSupplier maintenanceActive) {
    super("hdadvancedstatus", "Shows status for the advanced template plugin.");
    setAllowsExtraArguments(true);
    this.state = state;
    this.demoService = demoService;
    this.heartbeatTicks = heartbeatTicks;
    this.uptimeSeconds = uptimeSeconds;
    this.heartbeatActive = heartbeatActive;
    this.maintenanceActive = maintenanceActive;
  }

  @Override
  protected CompletableFuture<Void> execute(CommandContext ctx) {
    state.incrementStatusRequests();
    String sender = String.valueOf(ctx.sender().getDisplayName());

    ctx.sendMessage(
        Message.raw(
            "[Advanced] lifecycle="
                + state.getLifecycle()
                + ", uptime="
                + uptimeSeconds.getAsLong()
                + "s"
                + ", heartbeatTicks="
                + heartbeatTicks.get()
                + ", heartbeatActive="
                + heartbeatActive.getAsBoolean()
                + ", maintenanceActive="
                + maintenanceActive.getAsBoolean()
                + ", setupCompleted="
                + state.isSetupCompleted()
                + ", demoFlag="
                + state.isDemoFlagEnabled()
                + ", commands="
                + state.getCommandRequests()
                + ", statusCalls="
                + state.getStatusRequests()
                + ", errors="
                + state.getErrorCount()));

    ctx.sendMessage(
        Message.raw(
            "[Advanced] sender="
                + sender
                + ", lastAction="
                + demoService.describeLastAction(sender)
                + ", "
                + demoService.diagnostics()));

    ctx.sendMessage(Message.raw("[Advanced] latestAudit=" + demoService.latestAuditEntry()));
    return CompletableFuture.completedFuture(null);
  }
}
