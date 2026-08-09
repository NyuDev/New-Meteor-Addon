package fr.nyuway.newaddon.utils.vc;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import fr.nyuway.newaddon.NewAddon;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The 2b2t.vc data API, asked politely.
 *
 * <p>Someone runs this for free and publishes no rate limit, which is a reason to be careful
 * rather than a licence not to be. So: one request at a time on one thread, a floor on the gap
 * between them, and an answer kept for as long as it can still be true. A window that asks who
 * someone is asks the cache; the cache asks the network at most once.
 *
 * <h2>Nothing here runs on the game thread</h2>
 * {@link #cached} answers immediately - with what it has, or with null and a fetch started
 * behind it. That is what lets a GUI call it once per frame without the frame waiting for
 * anything, which is the shape the buddy list already had to be rewritten into once.
 *
 * <p>A miss is cached too, briefly. Without that, the one thing the API does not know about
 * becomes the thing it is asked about sixty times a second.
 */
public final class VcApi {

    private VcApi() { }

    private static final String BASE = "https://api.2b2t.vc";
    private static final Gson GSON = new Gson();

    /** How long a "there is nothing here" answer is believed, so a miss is not asked twice a second. */
    private static final long MISS_TTL = 60_000L;

    /** One thread, so requests queue behind each other rather than arriving all at once. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "New-2b2t.vc");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Minimum gap between two requests, in milliseconds. Set from the module's settings. */
    private static volatile long minInterval = 1000L;

    /** Identifies this traffic, so whoever runs the API can tell what it is and block it if need be. */
    private static volatile String userAgent = "New-Meteor-Addon (+https://github.com/NyuDev/New-Meteor-Addon)";

    public static void configure(int minIntervalMs, String version) {
        minInterval = Math.max(200, minIntervalMs);
        userAgent = "New-Meteor-Addon/" + version
            + " (+https://github.com/NyuDev/New-Meteor-Addon)";
    }

    /** When the last request went out, so the next one can wait its turn. */
    private static long lastSent;

    private record Entry(Object value, long at, long ttl, String error) {
        boolean fresh() {
            return System.currentTimeMillis() - at < ttl;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    /** Requests already in flight, so ten frames asking the same thing send one request. */
    private static final Map<String, Boolean> INFLIGHT = new HashMap<>();

    /**
     * What is known right now, or null.
     *
     * <p>Returns without touching the network. When there is nothing fresh, a fetch is started
     * and null comes back; ask again on a later frame and the answer will be there.
     */
    @SuppressWarnings("unchecked")
    public static <T> T cached(String path, Map<String, String> query, Class<T> type, long ttlMs) {
        String key = key(path, query);

        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            if (entry != null && entry.fresh()) return (T) entry.value();
            if (INFLIGHT.containsKey(key)) return null;
            INFLIGHT.put(key, Boolean.TRUE);
        }

        WORKER.execute(() -> {
            Object value = null;
            String error = null;
            try {
                value = fetch(path, query, type);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                NewAddon.LOG.warn("[2b2t.vc] {} failed: {}", path, error);
            }

            synchronized (CACHE) {
                CACHE.put(key, new Entry(value, System.currentTimeMillis(),
                    value == null ? MISS_TTL : ttlMs, error));
                INFLIGHT.remove(key);
            }
        });

        return null;
    }

    /**
     * Asks, and calls back on the worker thread when there is an answer.
     *
     * <p>For a command, where the user asked and can wait. The callback runs off the game thread,
     * so anything it touches has to be safe for that - chat is.
     *
     * @param onFail told why, when there is no answer to give
     */
    public static <T> void request(String path, Map<String, String> query, Class<T> type,
                                   long ttlMs, Consumer<T> onDone, Consumer<String> onFail) {
        String key = key(path, query);

        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            if (entry != null && entry.fresh()) {
                @SuppressWarnings("unchecked") T value = (T) entry.value();
                if (value != null) onDone.accept(value);
                else onFail.accept(entry.error() == null ? "no data" : entry.error());
                return;
            }
        }

        WORKER.execute(() -> {
            try {
                T value = fetch(path, query, type);
                synchronized (CACHE) {
                    CACHE.put(key, new Entry(value, System.currentTimeMillis(),
                        value == null ? MISS_TTL : ttlMs, null));
                }
                if (value != null) onDone.accept(value);
                else onFail.accept("no data for that");
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                synchronized (CACHE) {
                    CACHE.put(key, new Entry(null, System.currentTimeMillis(), MISS_TTL, why));
                }
                onFail.accept(why);
            }
        });
    }

    /** Throws on a real failure; returns null when the API says it has nothing. */
    private static <T> T fetch(String path, Map<String, String> query, Class<T> type)
        throws Exception {

        pace();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path + queryString(query)))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .GET()
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        // 204 is the API's way of saying it has no data for this, which is an answer and not a
        // failure - a player nobody has ever seen is a normal thing to ask about.
        if (status == 204) return null;
        if (status == 429) throw new IllegalStateException("rate limited; slow down");
        if (status == 400) throw new IllegalArgumentException("the API refused that request");
        if (status / 100 != 2) throw new IllegalStateException("HTTP " + status);

        String body = response.body();
        if (body == null || body.isBlank()) return null;

        try {
            return GSON.fromJson(body, type);
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("could not read the answer");
        }
    }

    /** Waits until enough time has passed since the last request. Worker thread only. */
    private static void pace() throws InterruptedException {
        long wait = lastSent + minInterval - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastSent = System.currentTimeMillis();
    }

    private static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            parts.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static String key(String path, Map<String, String> query) {
        return path + queryString(query);
    }

    /** Forgets everything, so the next question goes to the network. */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static Map<String, String> params(String... pairs) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }
}
