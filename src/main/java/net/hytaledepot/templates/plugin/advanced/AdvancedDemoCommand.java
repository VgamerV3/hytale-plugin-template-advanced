package net.hytaledepot.templates.plugin.advanced;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedDemoCommand extends AbstractCommand {
  private final AdvancedPluginState state;
  private final AdvancedDemoService demoService;
  private final AtomicLong heartbeatTicks;

  public AdvancedDemoCommand(
      AdvancedPluginState state, AdvancedDemoService demoService, AtomicLong heartbeatTicks) {
    super("hdadvanceddemo", "Runs an advanced demo action.");
    setAllowsExtraArguments(true);
    this.state = state;
    this.demoService = demoService;
    this.heartbeatTicks = heartbeatTicks;
  }

  @Override
  protected CompletableFuture<Void> execute(CommandContext ctx) {
    state.incrementCommandRequests();
    ParsedInput input = ParsedInput.parse(ctx.getInputString());
    String sender = String.valueOf(ctx.sender().getDisplayName());

    String result =
        demoService.applyAction(
            state,
            sender,
            input.action,
            input.arguments,
            heartbeatTicks.get());
    ctx.sendMessage(Message.raw(result));
    return CompletableFuture.completedFuture(null);
  }

  private static final class ParsedInput {
    private final String action;
    private final String[] arguments;

    private ParsedInput(String action, String[] arguments) {
      this.action = action;
      this.arguments = arguments;
    }

    private static ParsedInput parse(String rawInput) {
      String normalized = String.valueOf(rawInput == null ? "" : rawInput).trim();
      if (normalized.isEmpty()) {
        return new ParsedInput("info", new String[0]);
      }

      String[] parts = normalized.split("\\s+");
      if (parts.length == 0) {
        return new ParsedInput("info", new String[0]);
      }

      String first = parts[0].toLowerCase();
      if (first.startsWith("/")) {
        first = first.substring(1);
      }

      int actionIndex = first.startsWith("hd") ? 1 : 0;
      if (actionIndex >= parts.length) {
        return new ParsedInput("info", new String[0]);
      }

      String action = parts[actionIndex].toLowerCase();
      String[] args =
          actionIndex + 1 < parts.length
              ? Arrays.copyOfRange(parts, actionIndex + 1, parts.length)
              : new String[0];
      return new ParsedInput(action, args);
    }
  }
}
