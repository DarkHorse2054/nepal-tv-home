package app.nepal.framextv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic fixtures exercise the shipped catalog policy; no network or Android calls. */
public final class CatalogPolicyChecks {
    private static int passed;
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("all fixed home sections are parsed with their own media type", () -> {
            Map<CatalogClient.BrowseSection, List<CatalogClient.Item>> parsed = CatalogPolicy.home(home());
            equal(8, parsed.size());
            for (CatalogClient.BrowseSection section : CatalogClient.BrowseSection.values()) {
                equal(section.type, parsed.get(section).get(0).type);
                truth(!section.label.isEmpty());
            }
        });
        check("home map and nested lists are immutable", () -> {
            Map<CatalogClient.BrowseSection, List<CatalogClient.Item>> parsed = CatalogPolicy.home(home());
            rejectsMutation(() -> parsed.clear());
            rejectsMutation(() -> parsed.get(CatalogClient.BrowseSection.POPULAR_TV).clear());
        });
        check("home rejects missing malformed or unsuccessful rails", () -> {
            for (Object raw : Arrays.asList(null, false, "rails", new ArrayList<>())) {
                Map<String, Object> root = map("success", true, "rails", raw);
                rejects(() -> CatalogPolicy.home(root));
            }
            Map<String, Object> root = home();
            rails(root).remove("popular_movies");
            final Map<String, Object> missing = root;
            rejects(() -> CatalogPolicy.home(missing));
            root = home(); rails(root).put("popular_movies", response(false, new ArrayList<>()));
            final Map<String, Object> bad = root;
            rejects(() -> CatalogPolicy.home(bad));
        });
        check("literal success true is required", () -> {
            for (Object success : Arrays.asList(null, false, "true", 1)) {
                rejects(() -> CatalogPolicy.results(response(success, new ArrayList<>()), ""));
                Map<String, Object> root = home();root.put("success", success);
                rejects(() -> CatalogPolicy.home(root));
            }
        });
        check("explicit null and numeric zero is the only null-empty result", () -> {
            for (Number zero : Arrays.asList(0, 0L, 0.0))
                equal(0, CatalogPolicy.results(map("success", true, "results", null, "totalResults", zero), "").size());
            for (Object total : Arrays.asList(null, false, "0", 1, -1, Double.NaN, Double.POSITIVE_INFINITY))
                rejects(() -> CatalogPolicy.results(map("success", true, "results", null, "totalResults", total), ""));
            rejects(() -> CatalogPolicy.results(map("success", true, "totalResults", 0), ""));
        });
        check("wrong result shapes are rejected rather than reported empty", () -> {
            for (Object raw : Arrays.asList(false, 0, "[]", new HashMap<>()))
                rejects(() -> CatalogPolicy.results(response(true, raw), ""));
        });
        check("empty arrays are valid and immutable", () -> {
            List<CatalogClient.Item> parsed = CatalogPolicy.results(response(true, new ArrayList<>()), "");
            equal(0, parsed.size());rejectsMutation(() -> parsed.add(item(2, "movie")));
        });
        check("movie and tv identities deduplicate independently", () -> {
            List<Object> rows = Arrays.asList(row(1, "movie"), row("1", "movie"), row(1, "tv"));
            List<CatalogClient.Item> parsed = CatalogPolicy.results(response(true, rows), "");
            equal(2, parsed.size());equal("movie", parsed.get(0).type);equal("tv", parsed.get(1).type);
        });
        check("shelves filter wrong and unsupported types without relabeling", () -> {
            List<Object> rows = Arrays.asList(row(1, "movie"), row(2, "tv"), row(3, "anime"), row(4, "MOVIE"));
            List<CatalogClient.Item> parsed = CatalogPolicy.results(response(true, rows), "tv");
            equal(1, parsed.size());equal(2, parsed.get(0).id);
        });
        check("invalid objects and IDs are skipped", () -> {
            List<Object> rows = new ArrayList<>(Arrays.asList(null, false, "movie", new ArrayList<>()));
            for (Object id : Arrays.asList(0, -1, 1.5, 2147483648L, "-1", "1e2", "1.0", "999999999999", true))
                rows.add(row(id, "movie"));
            rows.add(row(9, "movie"));
            List<CatalogClient.Item> parsed = CatalogPolicy.results(response(true, rows), "");
            equal(1, parsed.size());equal(9, parsed.get(0).id);
        });
        check("integer maximum IDs and string IDs are accepted", () -> {
            equal(Integer.MAX_VALUE, CatalogPolicy.item(row("2147483647", "movie"), "movie").id);
            equal(12, CatalogPolicy.item(row("00012", "tv"), "tv").id);
        });
        check("titles fall back safely and years use verified date fields", () -> {
            Map<String, Object> row = row(1, "tv");row.remove("title");row.put("name", "A show");
            row.put("year", "unknown");row.put("first_air_date", "2025-01-02");
            CatalogClient.Item item = CatalogPolicy.item(row, "tv");
            equal("A show", item.title);equal("2025", item.year);
            row.remove("name");row.remove("first_air_date");
            item = CatalogPolicy.item(row, "tv");equal("Untitled", item.title);equal("", item.year);
        });
        check("text bounds remove controls without splitting a surrogate pair", () -> {
            Map<String, Object> row = row(1, "movie");row.put("title", "A\nB\tC");
            row.put("overview", repeat("x", 299) + "\uD83D\uDE00" + repeat("y", 400));
            CatalogClient.Item item = CatalogPolicy.item(row, "movie");
            equal("A B C", item.title);equal(299, item.overview.length());
            row.put("title", repeat("t", 300));row.put("overview", repeat("o", 800));
            item = CatalogPolicy.item(row, "movie");equal(160, item.title.length());equal(300, item.overview.length());
        });
        check("safe poster and backdrop use separate TMDB sizes", () -> {
            Map<String, Object> row = row(1, "movie");row.put("poster", "/poster.jpg");row.put("backdrop_path", "/scene.webp");
            CatalogClient.Item item = CatalogPolicy.item(row, "movie");
            equal("https://image.tmdb.org/t/p/w500/poster.jpg", item.posterUrl);
            equal("https://image.tmdb.org/t/p/w1280/scene.webp", item.backdropUrl);
        });
        check("unapproved image addresses never reach the UI", () -> {
            for (String address : Arrays.asList("http://image.tmdb.org/t/p/w500/p.jpg", "https://127.0.0.1/p.jpg",
                    "https://image.tmdb.org@localhost/t/p/w500/p.jpg", "//localhost/p.jpg",
                    "https://image.tmdb.org/t/p/w500/p.jpg?token=fixture", "https://image.tmdb.org/t/p/w500/p.jpg#x",
                    "https://image.tmdb.org:444/t/p/w500/p.jpg", "https://image.tmdb.org/t/p/w500/../p.jpg",
                    "/%2e%2e/private.jpg", "https://image.tmdb.org/t/p/w500/p.svg")) {
                Map<String, Object> row = row(1, "movie");row.put("poster", address);row.put("backdrop", address);
                CatalogClient.Item item = CatalogPolicy.item(row, "movie");equal("", item.posterUrl);equal("", item.backdropUrl);
            }
        });
        check("safe fallback artwork is used after a rejected primary image", () -> {
            Map<String, Object> row = row(1, "movie");row.put("poster", "https://untrusted.invalid/a.jpg");
            row.put("image", "https://image.tmdb.org/t/p/w500/safe.jpg");
            equal("https://image.tmdb.org/t/p/w500/safe.jpg", CatalogPolicy.item(row, "movie").posterUrl);
        });
        check("rows are capped at twenty-four and order remains provider order", () -> {
            List<Object> rows = new ArrayList<>();for (int i = 1; i <= 80; i++) rows.add(row(i, "movie"));
            List<CatalogClient.Item> parsed = CatalogPolicy.results(response(true, rows), "movie");
            equal(24, parsed.size());equal(1, parsed.get(0).id);equal(24, parsed.get(23).id);
        });
        check("oversized input arrays fail before row processing", () -> {
            List<Object> rows = new ArrayList<>();for (int i = 0; i <= CatalogPolicy.MAX_INPUT_ROWS; i++) rows.add(null);
            rejects(() -> CatalogPolicy.results(response(true, rows), ""));
        });
        check("thread interruption cancels policy parsing", () -> {
            Thread.currentThread().interrupt();
            try { rejects(() -> CatalogPolicy.results(response(true, Arrays.asList(row(1, "movie"))), "")); }
            finally { Thread.interrupted(); }
        });
        check("legacy item constructor retains empty optional metadata", () -> {
            CatalogClient.Item item = new CatalogClient.Item(1, "movie", "A", "2025", "");
            equal("", item.backdropUrl);equal("", item.overview);
        });
        check("detail responses must match the requested identity and literal success", () -> {
            Map<String, Object> row = row(7, "movie");row.put("success", true);
            equal(7, CatalogPolicy.details(row, "movie", 7).id);
            rejects(() -> CatalogPolicy.details(row, "movie", 8));
            rejects(() -> CatalogPolicy.details(row, "tv", 7));
            rejects(() -> CatalogPolicy.details(row, "movie", 0));
            rejects(() -> CatalogPolicy.details(row, "anime", 7));
            for (Object success : Arrays.asList(false, "true", null)) {
                row.put("success", success);rejects(() -> CatalogPolicy.details(row, "movie", 7));
            }
        });
        System.out.println("Catalog policy: " + passed + " checks passed");
    }

    private static void check(String label, Check body) throws Exception {
        try { body.run();passed++;System.out.println("PASS " + label); }
        catch (Throwable failure) { throw new AssertionError(label, failure); }
    }
    private static void truth(boolean value) { if (!value) throw new AssertionError("Expected true"); }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
    private static void rejectsMutation(Check action) throws Exception {
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable collection");
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String, Object> row(Object id, String type) { return map("id", id, "type", type, "title", "Fixture"); }
    private static Map<String, Object> response(Object success, Object rows) { return map("success", success, "results", rows); }
    private static CatalogClient.Item item(int id, String type) { return CatalogPolicy.item(row(id, type), type); }
    private static Map<String, Object> home() {
        Map<String, Object> rails = new HashMap<>();
        for (CatalogClient.BrowseSection section : CatalogClient.BrowseSection.values())
            rails.put(section.key, response(true, Arrays.asList(row(1, section.type))));
        return map("success", true, "rails", rails);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rails(Map<String, Object> root) { return (Map<String, Object>) root.get("rails"); }
    private static String repeat(String value, int count) { StringBuilder text = new StringBuilder();for (int i = 0; i < count; i++) text.append(value);return text.toString(); }
}
