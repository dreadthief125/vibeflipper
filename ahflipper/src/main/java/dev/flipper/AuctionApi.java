package dev.flipper;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Public Hypixel API client. Endpoints are cached server-side; faster polling cannot bypass that cache. */
public final class AuctionApi {
    static final String BASE = "https://api.hypixel.net/v2/skyblock/";
    static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    static final ExecutorService PAGE_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "ahflipper-api-page"); t.setDaemon(true); return t;
    });
    public record Sale(String uuid, long ts, long price, boolean bin) {}

    static JsonObject get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path)).header("User-Agent", "AHFlipper/0.2")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = H.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Hypixel API HTTP " + response.statusCode() + " for " + path);
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (body.has("success") && !body.get("success").getAsBoolean())
            throw new IllegalStateException("Hypixel API rejected " + path + ": " + body);
        return body;
    }

    /** Fetch all pages concurrently with bounded parallelism and consume them as each completes. */
    public static List<Auction> active() throws Exception {
        JsonObject first = get("auctions?page=0");
        int pages = first.get("totalPages").getAsInt();
        List<Auction> out = new ArrayList<>(parse(first));
        if (pages <= 1) return out;
        CompletionService<JsonObject> completed = new ExecutorCompletionService<>(PAGE_POOL);
        int submitted = 0;
        for (int page = 1; page < pages; page++) {
            final int p = page;
            completed.submit(() -> get("auctions?page=" + p));
            submitted++;
        }
        for (int i = 0; i < submitted; i++) out.addAll(parse(completed.take().get()));
        return out;
    }

    static List<Auction> parse(JsonObject o) {
        var list = new ArrayList<Auction>();
        for (var element : o.getAsJsonArray("auctions")) {
            var a = element.getAsJsonObject();
            list.add(new Auction(a.get("uuid").getAsString(), a.get("item_name").getAsString(),
                    a.get("tier").getAsString(), a.get("starting_bid").getAsLong(),
                    a.has("highest_bid_amount") ? a.get("highest_bid_amount").getAsLong() : 0,
                    a.has("bin") && a.get("bin").getAsBoolean(), a.get("end").getAsLong(),
                    a.has("claimed") && a.get("claimed").getAsBoolean()));
        }
        return list;
    }

    public static List<Sale> ended() throws Exception {
        var list = new ArrayList<Sale>();
        for (var element : get("auctions_ended").getAsJsonArray("auctions")) {
            var a = element.getAsJsonObject();
            list.add(new Sale(a.get("auction_id").getAsString(), a.get("timestamp").getAsLong(),
                    a.get("price").getAsLong(), a.has("bin") && a.get("bin").getAsBoolean()));
        }
        return list;
    }
}
