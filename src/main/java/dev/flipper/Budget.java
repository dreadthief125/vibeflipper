package dev.flipper;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

/** Tracks bought items until the player manually marks them sold. No automated listing is performed. */
public final class Budget {
    public static final class Position {
        public final String uuid, key, name;
        public final long cost, boughtAt;
        public final long suggestedListPrice, estimatedNetProfit;
        public Position(String uuid, String key, String name, long cost, long boughtAt,
                        long suggestedListPrice, long estimatedNetProfit) {
            this.uuid = uuid; this.key = key; this.name = name; this.cost = cost; this.boughtAt = boughtAt;
            this.suggestedListPrice = suggestedListPrice; this.estimatedNetProfit = estimatedNetProfit;
        }
    }

    public final List<Position> open = new ArrayList<>();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("ahflipper-positions.json"); }

    public void load() {
        try {
            if (!Files.exists(path())) return;
            var type = new TypeToken<List<Position>>() {}.getType();
            List<Position> loaded = GSON.fromJson(Files.readString(path()), type);
            if (loaded != null) { open.clear(); open.addAll(loaded); }
        } catch (Exception e) {
            System.err.println("[AHFlipper] Could not load tracked positions: " + e.getMessage());
        }
    }

    public void save() {
        try { Files.writeString(path(), GSON.toJson(open)); }
        catch (Exception e) { System.err.println("[AHFlipper] Could not save tracked positions: " + e.getMessage()); }
    }

    public long committed() { return open.stream().mapToLong(p -> p.cost).sum(); }

    public boolean allows(Config c, FlipEngine.Flip f, long purse) { return allowsCost(c, f.a().key(), f.buy(), purse); }
    public boolean allowsCost(Config c, String key, long price, long purse) {
        if (purse <= 0 || price <= 0) return false;
        long committed = committed(), portfolio = purse + committed;
        if (portfolio <= 0) return false;
        if (price > portfolio * c.maxFractionPerItem) return false;
        if (purse - price < portfolio * c.reserveFraction) return false;
        if (committed + price > portfolio * c.maxCommittedFraction) return false;
        if (open.size() >= c.maxOpenPositions) return false;
        return open.stream().filter(p -> p.key.equals(key)).count() < c.maxPerItemKey;
    }

    public void add(Position p) { open.add(p); save(); }
    public boolean markSold(String itemName) {
        String q = itemName == null ? "" : itemName.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return false;
        boolean changed = open.removeIf(p -> p.name.toLowerCase(Locale.ROOT).contains(q));
        if (changed) save();
        return changed;
    }
}
