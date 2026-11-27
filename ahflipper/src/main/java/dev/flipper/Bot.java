package dev.flipper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Tick-driven BIN purchase runner. Never bids, claims, or creates listings. */
public final class Bot {
    static final class Abort extends RuntimeException { private static final long serialVersionUID = 1L; Abort(String message) { super(message); } }
    interface Step { boolean run(); }
    record Named(String name, Step step) {}

    final Deque<Named> steps = new ArrayDeque<>();
    final Queue<String> messages = new ConcurrentLinkedQueue<>();
    int wait, stepTicks;
    String label = "";
    Runnable onDone, onFail;

    public boolean busy() { return !steps.isEmpty(); }
    public void stop() { steps.clear(); messages.clear(); onDone = null; onFail = null; }
    public void onMessage(String message) { messages.add(message); while (messages.size() > 60) messages.poll(); }

    void start(String label, List<Named> sequence, Runnable done, Runnable fail) {
        this.label = label; steps.clear(); steps.addAll(sequence); onDone = done; onFail = fail; wait = 0; stepTicks = 0;
    }

    public void tick(Config c) {
        if (steps.isEmpty()) return;
        if (wait > 0) { wait--; return; }
        Named current = steps.peek();
        try {
            if (current.step().run()) {
                steps.poll(); stepTicks = 0;
                wait = (c.actionDelayMs + (c.actionJitterMs == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(c.actionJitterMs + 1))) / 50;
                if (steps.isEmpty() && onDone != null) onDone.run();
            } else if (++stepTicks > c.stepTimeoutSec * 20) throw new Abort("timed out at " + current.name());
        } catch (Abort e) {
            steps.clear();
            GameBridge.say("§c" + label + " aborted: " + e.getMessage() + ". Check your inventory/AH before retrying.");
            GameBridge.closeScreen();
            if (onFail != null) onFail.run();
        } catch (Throwable e) {
            steps.clear();
            GameBridge.say("§c" + label + " stopped: " + e.getClass().getSimpleName());
            GameBridge.closeScreen();
            if (onFail != null) onFail.run();
        }
    }

    Named cmd(String command) { return new Named("/" + command, () -> { GameBridge.cmd(command); return true; }); }
    Named title(String part) { return new Named("wait '" + part + "'", () -> GameBridge.title().contains(part)); }
    Named click(String name) { return new Named("click '" + name + "'", () -> GameBridge.clickByName(name)); }
    Named clearMessages() { return new Named("clear chat", () -> { messages.clear(); return true; }); }
    Named msg(String success, String... failures) {
        return new Named("purchase confirmation", () -> {
            String message;
            while ((message = messages.poll()) != null) {
                if (message.contains(success)) return true;
                for (String failure : failures) if (message.toLowerCase(Locale.ROOT).contains(failure.toLowerCase(Locale.ROOT)))
                    throw new Abort(message);
            }
            return false;
        });
    }

    /** Buy only. Actual GUI price is re-read and revalidated before the confirmation click. */
    public void buy(Config c, FlipEngine.Flip flip, Budget budget) {
        Auction auction = flip.a();
        final long[] verifiedCost = {flip.buy()};
        List<Named> sequence = new ArrayList<>();
        sequence.add(cmd("viewauction " + auction.uuid()));
        sequence.add(title(c.viewTitle));
        sequence.add(new Named("verify live BIN price", () -> {
            long shown = GameBridge.slotCoins(c.buyBtn);
            if (shown == -2 || shown == -1) return false; // wait for slot/tooltip; never buy an unverified price
            if (shown <= 0) throw new Abort("could not read a valid BIN price");
            long max = Math.round(flip.buy() * (1.0 + c.maxPriceIncreasePct / 100.0));
            if (shown > max) throw new Abort("price changed from " + flip.buy() + " to " + shown);
            long purse = GameBridge.purse();
            if (!budget.allowsCost(c, auction.key(), shown, purse)) throw new Abort("budget no longer allows " + shown);
            long projected = FlipEngine.estimatedNetProfit(c, shown, FlipEngine.suggestedListPrice(c, flip.fair()));
            double projectedMargin = 100.0 * projected / shown;
            if (projected < c.minProfit || projectedMargin < c.minMarginPct || projectedMargin > c.maxMarginPct)
                throw new Abort("estimated profit/margin fell outside configured limits after price check");
            verifiedCost[0] = shown;
            return true;
        }));
        sequence.add(click(c.buyBtn));
        sequence.add(title("Confirm"));
        sequence.add(clearMessages());
        sequence.add(click(c.confirmBtn));
        sequence.add(msg("You purchased", "enough coins", "cannot afford", "already bought", "auction has ended"));

        start("Buy " + auction.name(), sequence, () -> {
            long listPrice = FlipEngine.suggestedListPrice(c, flip.fair());
            long net = FlipEngine.estimatedNetProfit(c, verifiedCost[0], listPrice);
            budget.add(new Budget.Position(auction.uuid(), auction.key(), auction.name(), verifiedCost[0],
                    System.currentTimeMillis(), listPrice, net));
            GameBridge.closeScreen();
            GameBridge.say("§aBought " + auction.name() + " for " + String.format("%,d", verifiedCost[0])
                    + " coins. §eSuggested BIN: " + String.format("%,d", listPrice)
                    + " §7(estimated net " + (net >= 0 ? "+" : "") + String.format("%,d", net)
                    + " after assumed fees). Not listed automatically.");
        }, () -> GameBridge.say("§ePurchase was not confirmed. Verify your inventory and purse before retrying."));
    }
}
