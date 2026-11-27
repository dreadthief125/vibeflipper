package dev.flipper;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;

/** User-tunable thresholds. Edit config/ahflipper.json or use /flip commands. */
public class Config {
    // Safety: scanning is enabled with /flip on; purchases additionally require /flip auto on.
    public boolean autoExecute = false;
    public double maxFractionPerItem = 0.20;
    public double maxCommittedFraction = 0.60;
    public double reserveFraction = 0.10;
    public int maxOpenPositions = 4;
    public int maxPerItemKey = 1;
    public long purseOverride = 0;

    // Flip quality
    public long minProfit = 100_000;
    public double minMarginPct = 5;
    public double maxMarginPct = 50;
    public long minBuyPrice = 100_000;
    public double minSalesPerDay = 15;
    public double maxHoursToClear = 3;
    public double undercutPct = 0.5;
    public double extraFeeCoins = 0;
    public List<String> ignoredNames = new ArrayList<>(List.of("Enchanted Book", "Rune", "[Lvl", "Shulker Box", "Bundle", "Potion", "Tipped Arrow", "Firework", "Suspicious Stew", "Spawner"));

    // Public Hypixel endpoints are cached; polling substantially faster than this usually adds load, not freshness.
    public int pollSeconds = 60;
    // One client tick is the minimum reliable GUI transition interval. 0 means no extra ticks are added.
    public int actionDelayMs = 0, actionJitterMs = 0, stepTimeoutSec = 6;
    public double maxPriceIncreasePct = 0.0; // do not overpay beyond the API quote by default

    // GUI text; verify against your current server/client version.
    public String viewTitle = "Auction View", buyBtn = "Buy Item Now",
            confirmBtn = "Confirm";

    static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("ahflipper.json"); }

    /** Merge saved values over defaults so newly-added settings never become zero/null in older configs. */
    public static Config load() {
        try {
            if (Files.exists(path())) {
                JsonObject merged = G.toJsonTree(new Config()).getAsJsonObject();
                JsonElement saved = JsonParser.parseString(Files.readString(path()));
                if (saved.isJsonObject()) saved.getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                Config c = G.fromJson(merged, Config.class);
                if (c.ignoredNames == null) c.ignoredNames = new ArrayList<>();
                c.clamp();
                return c;
            }
        } catch (Exception ignored) {}
        Config c = new Config(); c.save(); return c;
    }

    void clamp() {
        pollSeconds = Math.max(15, Math.min(300, pollSeconds));
        actionDelayMs = Math.max(0, Math.min(1000, actionDelayMs));
        actionJitterMs = Math.max(0, Math.min(1000, actionJitterMs));
        stepTimeoutSec = Math.max(3, Math.min(30, stepTimeoutSec));
        maxPriceIncreasePct = Math.max(0, Math.min(5, maxPriceIncreasePct));
        undercutPct = Math.max(0, Math.min(20, undercutPct));
    }

    public void save() { try { clamp(); Files.writeString(path(), G.toJson(this)); } catch (Exception ignored) {} }
}
