package dev.flipper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import java.util.*;
import java.util.concurrent.*;

/** Commands: /flip on | off | stop | status | auto on|off | purse <n> | sold <item name> */
public class FlipperMod implements ClientModInitializer {
    public static Config cfg;
    static final FlipEngine engine = new FlipEngine();
    static final Budget budget = new Budget();
    static final Bot bot = new Bot();
    static volatile boolean on = false;
    static final Set<String> tried = ConcurrentHashMap.newKeySet();
    static final Set<String> alerted = ConcurrentHashMap.newKeySet();

    @Override public void onInitializeClient() {
        cfg = Config.load();
        budget.load();
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ahflipper-poll"); t.setDaemon(true); return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            if (!on) return;
            try {
                engine.refresh(cfg);
                Set<String> live = new HashSet<>();
                for (FlipEngine.Flip f : engine.flips) live.add(f.a().uuid());
                // Drop old IDs once an auction is no longer a current candidate.
                tried.retainAll(live);
                alerted.retainAll(live);
                alert();
            } catch (Exception e) {
                engine.lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                System.err.println("[AHFlipper] refresh failed: " + engine.lastError);
            }
        }, 5, cfg.pollSeconds, TimeUnit.SECONDS);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!on || mc.player == null) return;
            bot.tick(cfg);
            if (bot.busy() || !cfg.autoExecute || mc.screen != null) return;
            pickNext();
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> bot.onMessage(message.getString()));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!command.equals("flip") && !command.startsWith("flip ")) return true;
            handle(command.length() > 4 ? command.substring(5).trim() : "status");
            return false;
        });
    }

    static void pickNext() {
        if (System.currentTimeMillis() - engine.lastRefreshAt > Math.max(90_000L, cfg.pollSeconds * 2_000L)) return;
        long purse = GameBridge.purse();
        FlipEngine.Flip selected = null;
        // Engine list is already sorted by score. Check affordability BEFORE marking an auction attempted.
        for (FlipEngine.Flip flip : engine.flips) {
            if (tried.contains(flip.a().uuid())) continue;
            if (!budget.allows(cfg, flip, purse)) continue;
            selected = flip;
            break;
        }
        if (selected == null) return;
        tried.add(selected.a().uuid());
        bot.buy(cfg, selected, budget);
    }


    static void alert() {
        if (cfg.autoExecute) return;
        int sent = 0;
        for (FlipEngine.Flip f : engine.flips) {
            if (sent >= 3) break;
            if (!alerted.add(f.a().uuid())) continue;
            long suggested = FlipEngine.suggestedListPrice(cfg, f.fair());
            long net = FlipEngine.estimatedNetProfit(cfg, f.buy(), suggested);
            GameBridge.say(String.format("§aBIN %s: buy %,d -> suggested list %,d; est. net %,d (%.0f%%)  §7/viewauction %s",
                    f.a().name(), f.buy(), suggested, net, 100.0 * net / f.buy(), f.a().uuid()));
            sent++;
        }
    }

    static void handle(String arg) {
        String[] parts = arg.trim().split("\\s+", 2);
        String action = parts.length == 0 ? "status" : parts[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on" -> { tried.clear(); alerted.clear(); on = true; GameBridge.say("BIN scanning enabled. Auto-buy=" + cfg.autoExecute + ". Sales history warms up over time."); }
            case "off" -> { on = false; bot.stop(); GameBridge.closeScreen(); GameBridge.say("Scanning off."); }
            case "stop" -> { bot.stop(); GameBridge.closeScreen(); GameBridge.say("Current GUI task cancelled. Check your inventory if a purchase was in progress."); }
            case "auto" -> {
                String value = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
                if (value.equals("on")) cfg.autoExecute = true;
                else if (value.equals("off")) cfg.autoExecute = false;
                else { GameBridge.say("Usage: /flip auto on|off"); return; }
                cfg.save(); GameBridge.say("Automatic BIN buying " + (cfg.autoExecute ? "enabled" : "disabled") + ".");
            }
            case "purse" -> {
                if (parts.length > 1) try { cfg.purseOverride = Long.parseLong(parts[1].replace(",", "")); cfg.save(); }
                catch (NumberFormatException e) { GameBridge.say("Usage: /flip purse <coin amount>"); return; }
                GameBridge.say("Purse override: " + String.format("%,d", cfg.purseOverride));
            }
            case "sold" -> {
                if (parts.length < 2 || !budget.markSold(parts[1])) GameBridge.say("No tracked purchase matched. Usage: /flip sold <part of item name>");
                else GameBridge.say("Removed matching item from tracked positions.");
            }
            case "positions" -> {
                if (budget.open.isEmpty()) { GameBridge.say("No tracked purchases."); return; }
                for (Budget.Position p : budget.open) GameBridge.say(p.name + " | cost " + String.format("%,d", p.cost)
                        + " | suggested BIN " + String.format("%,d", p.suggestedListPrice)
                        + " | estimated net " + String.format("%,d", p.estimatedNetProfit));
            }
            default -> {
                long age = engine.lastRefreshAt == 0 ? -1 : (System.currentTimeMillis() - engine.lastRefreshAt) / 1000;
                GameBridge.say(String.format("on=%s autoBuy=%s purse=%,d committed=%,d tracked=%d flips=%d refreshAge=%ds",
                        on, cfg.autoExecute, GameBridge.purse(), budget.committed(), budget.open.size(), engine.flips.size(), age));
                if (!engine.lastError.isBlank()) GameBridge.say("Last API error: " + engine.lastError);
                GameBridge.say("Commands: /flip auto on|off, /flip positions, /flip sold <item>");
            }
        }
    }
}
