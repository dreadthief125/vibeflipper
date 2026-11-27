package dev.flipper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/** Sales-backed BIN evaluator. Auction sales history is in-memory and warms up after launch. */
public final class FlipEngine {
    public record Flip(Auction a, long buy, long fair, long profit, double margin,
                       double salesPerHour, double hoursToClear, double score) {}
    public record Stats(long fair, double perHour, double hoursToClear) {}

    final Map<String, String> seen = new ConcurrentHashMap<>();
    final Map<String, ArrayDeque<long[]>> sales = new HashMap<>();
    final long startedAt = System.currentTimeMillis();
    public volatile List<Flip> flips = List.of();
    public volatile Map<String, Stats> stats = Map.of();
    public volatile long lastRefreshAt = 0;
    public volatile String lastError = "";

    public void refresh(Config c) throws Exception {
        CompletableFuture<List<AuctionApi.Sale>> endedFuture = CompletableFuture.supplyAsync(() -> {
            try { return AuctionApi.ended(); } catch (Exception e) { throw new CompletionException(e); }
        });
        List<Auction> list = AuctionApi.active();
        List<AuctionApi.Sale> ended = endedFuture.join();
        long now = System.currentTimeMillis();
        for (var sale : ended) {
            if (!sale.bin()) continue;
            String key = seen.remove(sale.uuid());
            if (key != null) sales.computeIfAbsent(key, x -> new ArrayDeque<>()).add(new long[]{sale.ts(), sale.price()});
        }
        Set<String> ids = new HashSet<>();
        for (var a : list) if (a.bin() && !a.claimed()) { ids.add(a.uuid()); seen.putIfAbsent(a.uuid(), a.key()); }
        seen.keySet().retainAll(ids);
        for (var d : sales.values()) d.removeIf(s -> now - s[0] > 86_400_000L);
        evaluate(c, list, now);
        lastRefreshAt = now;
        lastError = "";
    }

    void evaluate(Config c, List<Auction> list, long now) {
        double observedHours = Math.min(24, (now - startedAt) / 3.6e6);
        var bins = list.stream().filter(a -> a.bin() && !a.claimed() && a.end() > now && !ignored(c, a))
                .collect(Collectors.groupingBy(Auction::key));
        var newStats = new HashMap<String, Stats>();
        var out = new ArrayList<Flip>();
        for (var entry : bins.entrySet()) {
            var history = sales.get(entry.getKey());
            if (history == null || history.size() < 3 || observedHours < 0.5) continue;
            double perHour = history.size() / observedHours;
            if (perHour * 24 < c.minSalesPerDay) continue;
            long[] prices = history.stream().mapToLong(s -> s[1]).sorted().toArray();
            long median = prices[prices.length / 2];
            var listings = entry.getValue().stream().sorted(Comparator.comparingLong(Auction::price)).toList();
            if (listings.size() < 2) continue;
            long fair = Math.min(median, listings.get(1).price());
            double hoursToClear = listings.size() / perHour;
            newStats.put(entry.getKey(), new Stats(fair, perHour, hoursToClear));
            if (hoursToClear > c.maxHoursToClear) continue;
            consider(c, listings.get(0), fair, perHour, hoursToClear, out);
        }
        out.sort(Comparator.comparingDouble(Flip::score).reversed());
        stats = Map.copyOf(newStats);
        flips = List.copyOf(out);
    }

    void consider(Config c, Auction a, long fair, double perHour, double hoursToClear, List<Flip> out) {
        long buy = a.price();
        if (!a.bin() || buy < c.minBuyPrice) return;
        long suggested = suggestedListPrice(c, fair);
        long profit = estimatedNetProfit(c, buy, suggested);
        double margin = 100.0 * profit / buy;
        if (profit < c.minProfit || margin < c.minMarginPct || margin > c.maxMarginPct) return;
        double score = profit * Math.min(1, perHour / 2.0) / (1 + hoursToClear);
        out.add(new Flip(a, buy, fair, profit, margin, perHour, hoursToClear, score));
    }

    /** Suggested BIN price is fair value less the configured undercut. */
    public static long suggestedListPrice(Config c, long fair) {
        return Math.max(1, Math.round(fair * (1.0 - c.undercutPct / 100.0)));
    }

    /** Conservative net estimate using the same fee assumptions as the flip filter. */
    public static long estimatedNetProfit(Config c, long cost, long listPrice) {
        return listPrice - Math.round(listPrice * 0.01) - listFee(listPrice) - Math.round(c.extraFeeCoins) - cost;
    }

    /** Current fee tiers are assumptions; verify against live server fees. */
    public static long listFee(long price) {
        return Math.round(price * (price < 10_000_000 ? 0.01 : price < 100_000_000 ? 0.02 : 0.025));
    }

    static boolean ignored(Config c, Auction a) {
        return c.ignoredNames.stream().anyMatch(a.name()::contains);
    }
}
