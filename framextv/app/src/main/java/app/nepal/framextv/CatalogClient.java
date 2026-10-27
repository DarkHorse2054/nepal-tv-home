package app.nepal.framextv;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keyless catalog endpoints used by the provider's own public frontend.
 *
 * Call off the UI thread. These are observed website contracts, not a promised
 * versioned API. This client never requests, extracts or decrypts media streams.
 */
public final class CatalogClient implements Closeable {
    private static final String API = "https://api.framextv.cc";
    private static final int JSON_LIMIT = 512 * 1024;
    private static final int POSTER_LIMIT = 2 * 1024 * 1024;
    private static final int MAX_RESULTS = 24;
    private static final int MAX_SEASONS = 200;
    private static final int MAX_EPISODES = 1000;
    private static final int SOCKET_TIMEOUT_MS = 6000;
    private static final int REQUEST_TIMEOUT_MS = 12000;

    public static final class Item {
        public final int id;
        public final String type, title, year, posterUrl;

        Item(int id, String type, String title, String year, String posterUrl) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.year = year;
            this.posterUrl = posterUrl;
        }
    }

    public static final class Season {
        public final int number, episodeCount;
        public final String title;

        Season(int number, String title, int episodeCount) {
            this.number = number;
            this.title = title;
            this.episodeCount = episodeCount;
        }
    }

    public static final class Episode {
        public final int number;
        public final String title;

        Episode(int number, String title) {
            this.number = number;
            this.title = title;
        }
    }

    private final Object lock = new Object();
    private final Set<HttpsURLConnection> active = new HashSet<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "framextv-catalog-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private long generation;
    private boolean closed;

    public List<Item> search(String query) throws IOException {
        long request = begin();
        if (query == null) throw new IOException("Enter a movie or show title.");
        for (int index = 0; index < query.length(); index++) {
            if (Character.isISOControl(query.charAt(index)))
                throw new IOException("Enter a title without control characters.");
        }
        String clean = query.trim();
        if (clean.isEmpty()) return Collections.emptyList();
        if (clean.length() > 120)
            throw new IOException("Use a title of 120 characters or fewer.");
        JSONObject value = json("/api/search?q=" + URLEncoder.encode(clean, "UTF-8") + "&type=all&page=1", request);
        JSONArray rows = value.optJSONArray("results");
        if (rows == null) {
            Object total = value.opt("totalResults");
            // The provider returns explicit null, rather than [], for no matches.
            if (value.has("results") && value.isNull("results") && total instanceof Number
                    && ((Number) total).doubleValue() == 0) return Collections.emptyList();
            throw new IOException("The catalog returned an unreadable search result.");
        }
        List<Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.length() && result.size() < MAX_RESULTS; index++) {
            check(request);
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            int id = number(row, "id", Integer.MAX_VALUE, false);
            String type = text(row.opt("type"), "", 16);
            if (id < 1 || !("movie".equals(type) || "tv".equals(type)) || !seen.add(type + ":" + id)) continue;
            String title = text(row.opt("title"), text(row.opt("name"), "Untitled", 160), 160);
            String year = text(row.opt("year"), "", 10);
            if (!year.matches("[0-9]{4}")) {
                String date = text(row.opt("release_date"), text(row.opt("first_air_date"), "", 32), 32);
                year = date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*") ? date.substring(0, 4) : "";
            }
            result.add(new Item(id, type, title, year, posterAddress(row)));
        }
        return Collections.unmodifiableList(result);
    }

    public List<Season> seasons(int tvId) throws IOException {
        requireId(tvId);
        long request = begin();
        JSONObject value = json("/api/details?id=" + tvId + "&type=tv", request);
        JSONArray rows = value.optJSONArray("seasons");
        if (rows == null) throw new IOException("Season information is not available for this show.");
        List<Season> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < rows.length() && result.size() < MAX_SEASONS; index++) {
            check(request);
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            int number = number(row, row.has("number") ? "number" : "season_number", 1000, true);
            if (number < 0 || !seen.add(number)) continue;
            int count = number(row, row.has("episodeCount") ? "episodeCount" : "episode_count", 10000, true);
            String title = text(row.opt("title"), text(row.opt("name"), number == 0 ? "Specials" : "Season " + number, 100), 100);
            result.add(new Season(number, title, Math.max(0, count)));
        }
        Collections.sort(result, Comparator.comparingInt(season -> season.number));
        return Collections.unmodifiableList(result);
    }

    public List<Episode> episodes(int tvId, int season) throws IOException {
        requireId(tvId);
        if (season < 0 || season > 1000) throw new IOException("Choose a valid season.");
        long request = begin();
        JSONObject value = json("/api/season?id=" + tvId + "&season=" + season, request);
        JSONArray rows = value.optJSONArray("episodes");
        if (rows == null) throw new IOException("Episode information is not available for this season.");
        List<Episode> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < rows.length() && result.size() < MAX_EPISODES; index++) {
            check(request);
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            int number = number(row, row.has("number") ? "number" : "episode_number", 10000, false);
            if (number < 1 || !seen.add(number)) continue;
            String title = text(row.opt("title"), text(row.opt("name"), "Episode " + number, 160), 160);
            result.add(new Episode(number, title));
        }
        Collections.sort(result, Comparator.comparingInt(episode -> episode.number));
        return Collections.unmodifiableList(result);
    }

    public byte[] poster(String httpsUrl) throws IOException {
        long request = begin();
        String approved = approvedPoster(httpsUrl);
        if (approved.isEmpty()) throw new IOException("This poster address is not supported.");
        return download(approved, POSTER_LIMIT, true, request);
    }

    private JSONObject json(String path, long request) throws IOException {
        byte[] bytes = download(API + path, JSON_LIMIT, false, request);
        check(request);
        try {
            JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!value.optBoolean("success", false)) throw new IOException("The catalog could not load this request.");
            return value;
        } catch (JSONException invalid) {
            throw new IOException("The catalog returned an unreadable response.");
        }
    }

    private byte[] download(String address, int limit, boolean image, long request) throws IOException {
        check(request);
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !(image ? "image.tmdb.org" : "api.framextv.cc").equals(url.getHost())
                || url.getRef() != null)
            throw new IOException("This catalog address is not supported.");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> watchdog;
        synchronized (lock) {
            checkLocked(request);
            active.add(connection);
            watchdog = deadlines.schedule(() -> {
                timedOut.set(true);
                connection.disconnect();
            }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", image ? "image/jpeg,image/png,image/webp" : "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "NepalHomeCatalog/2.0");
            int status = connection.getResponseCode();
            check(request);
            if (timedOut.get()) throw new InterruptedIOException("The catalog request timed out.");
            if (status < 200 || status >= 300) throw new IOException("The catalog is unavailable (HTTP " + status + ").");
            String mime = connection.getContentType();
            mime = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            boolean accepted = image ? ("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime))
                    : "application/json".equals(mime);
            if (!accepted) throw new IOException("The catalog returned an unsupported response.");
            if (connection.getContentLengthLong() > limit) throw new IOException("The catalog response is too large.");
            try (InputStream source = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) != -1) {
                    check(request);
                    if (timedOut.get()) throw new InterruptedIOException("The catalog request timed out.");
                    if (output.size() > limit - length) throw new IOException("The catalog response is too large.");
                    output.write(buffer, 0, length);
                }
                check(request);
                if (timedOut.get()) throw new InterruptedIOException("The catalog request timed out.");
                return output.toByteArray();
            }
        } catch (IOException failure) {
            check(request);
            if (timedOut.get()) throw new InterruptedIOException("The catalog request timed out.");
            // Do not expose upstream URLs, query text, response bodies or credentials.
            throw new IOException("Could not load the catalog. Check Wi-Fi and try again.");
        } finally {
            watchdog.cancel(false);
            synchronized (lock) { active.remove(connection); }
            connection.disconnect();
        }
    }

    private long begin() throws IOException {
        synchronized (lock) {
            checkLocked(generation);
            return generation;
        }
    }

    private void check(long request) throws IOException {
        synchronized (lock) { checkLocked(request); }
    }

    private void checkLocked(long request) throws IOException {
        if (closed || request != generation || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Catalog request cancelled.");
    }

    public void cancelAll() {
        List<HttpsURLConnection> connections;
        synchronized (lock) {
            generation++;
            connections = new ArrayList<>(active);
            active.clear();
        }
        for (HttpsURLConnection connection : connections) connection.disconnect();
    }

    @Override public void close() {
        synchronized (lock) { closed = true; }
        cancelAll();
        deadlines.shutdownNow();
    }

    private static void requireId(int id) throws IOException {
        if (id <= 0) throw new IOException("Choose a valid show.");
    }

    private static int number(JSONObject row, String key, int maximum, boolean zeroAllowed) {
        String raw = row.optString(key, "");
        if (!raw.matches("[0-9]{1,10}")) return -1;
        try {
            long value = Long.parseLong(raw);
            return value >= (zeroAllowed ? 0 : 1) && value <= maximum ? (int) value : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static String text(Object raw, String fallback, int maximum) {
        if (!(raw instanceof String)) return fallback;
        String original = (String) raw;
        StringBuilder sanitized = new StringBuilder(Math.min(original.length(), maximum));
        for (int index = 0; index < original.length() && index < maximum; index++) {
            char character = original.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        String value = sanitized.toString().trim();
        if (value.isEmpty()) return fallback;
        int end = Math.min(value.length(), maximum);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static String posterAddress(JSONObject row) {
        for (String key : new String[]{"poster", "image", "poster_path"}) {
            String value = text(row.opt(key), "", 512);
            if (value.startsWith("/") && !value.startsWith("//")) value = "https://image.tmdb.org/t/p/w500" + value;
            String approved = approvedPoster(value);
            if (!approved.isEmpty()) return approved;
        }
        return "";
    }

    private static String approvedPoster(String raw) {
        if (raw == null || raw.length() > 512) return "";
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"image.tmdb.org".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/t/p/(?:w[0-9]{2,4}|original)/[A-Za-z0-9_-]{1,100}\\.(?:jpg|jpeg|png|webp)")) return "";
            return "https://image.tmdb.org" + uri.getRawPath();
        } catch (URISyntaxException invalid) {
            return "";
        }
    }
}
