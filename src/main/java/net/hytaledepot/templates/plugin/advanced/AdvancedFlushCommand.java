package net.hytaledepot.templates.plugin.advanced;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.concurrent.CompletableFuture;

public final class AdvancedFlushCommand extends AbstractCommand {
  private final AdvancedDemoService demoService;

  public AdvancedFlushCommand(AdvancedDemoService demoService) {
    super("hdadvancedflush", "Flushes advanced runtime snapshot.");
    setAllowsExtraArguments(true);
    this.demoService = demoService;
  }

  @Override
  protected CompletableFuture<Void> execute(CommandContext ctx) {
    demoService.flushSnapshot();
    ctx.sendMessage(
        Message.raw(
            "[Advanced] Snapshot flushed. "
                + demoService.diagnostics()
                + ", latestAudit="
                + demoService.latestAuditEntry()));
    return CompletableFuture.completedFuture(null);
  }
}
