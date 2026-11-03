package app.nepal.framextv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure catalog validation shared by Android's JSON adapter and offline fixtures. */
final class CatalogPolicy {
    static final int MAX_INPUT_ROWS = 512;
    private static final int MAX_RESULTS = 24;
    static final String[] ITEM_KEYS = {"id", "type", "title", "name", "year", "release_date",
            "first_air_date", "poster", "image", "poster_path", "backdrop", "backdrop_path", "overview"};

    private CatalogPolicy() {}

    static Map<CatalogClient.BrowseSection, List<CatalogClient.Item>> home(Map<String, ?> root)
            throws IOException {
        if (root == null || !Boolean.TRUE.equals(root.get("success")) || !(root.get("rails") instanceof Map))
            throw invalid();
        Map<?, ?> rails = (Map<?, ?>) root.get("rails");
        EnumMap<CatalogClient.BrowseSection, List<CatalogClient.Item>> result =
                new EnumMap<>(CatalogClient.BrowseSection.class);
        for (CatalogClient.BrowseSection section : CatalogClient.BrowseSection.values()) {
            Object shelf = rails.get(section.key);
            if (!(shelf instanceof Map)) throw invalid();
            result.put(section, results((Map<?, ?>) shelf, section.type));
        }
        return Collections.unmodifiableMap(result);
    }

    static List<CatalogClient.Item> results(Map<?, ?> response, String expectedType) throws IOException {
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) throw invalid();
        Object raw = response.get("results");
        // The public search endpoint uses explicit null plus numeric zero for no matches.
        if (raw == null && response.containsKey("results")) {
            Object total = response.get("totalResults");
            if (total instanceof Number && ((Number) total).doubleValue() == 0)
                return Collections.emptyList();
        }
        if (!(raw instanceof List)) throw invalid();
        List<?> rows = (List<?>) raw;
        if (rows.size() > MAX_INPUT_ROWS) throw invalid();
        List<CatalogClient.Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object row : rows) {
            if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("Catalog request cancelled.");
            if (!(row instanceof Map)) continue;
            CatalogClient.Item item = item((Map<?, ?>) row, expectedType);
            if (item != null && seen.add(item.type + ":" + item.id)) result.add(item);
            if (result.size() == MAX_RESULTS) break;
        }
        return Collections.unmodifiableList(result);
    }

    static CatalogClient.Item item(Map<?, ?> row, String expectedType) {
        Object rawId = row.get("id");
        if (!(rawId instanceof String || rawId instanceof Number)) return null;
        String digits = rawId.toString();
        if (!digits.matches("[0-9]{1,10}")) return null;
        long id;
        try { id = Long.parseLong(digits); } catch (NumberFormatException invalid) { return null; }
        if (id < 1 || id > Integer.MAX_VALUE) return null;
        String type = CatalogClient.text(row.get("type"), "", 16);
        if (!("movie".equals(type) || "tv".equals(type))
                || (!expectedType.isEmpty() && !expectedType.equals(type))) return null;
        String title = CatalogClient.text(row.get("title"),
                CatalogClient.text(row.get("name"), "Untitled", 160), 160);
        String year = CatalogClient.text(row.get("year"), "", 10);
        if (!year.matches("[0-9]{4}")) {
            String date = CatalogClient.text(row.get("release_date"),
                    CatalogClient.text(row.get("first_air_date"), "", 32), 32);
            year = date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*") ? date.substring(0, 4) : "";
        }
        String poster = image(row, new String[]{"poster", "image", "poster_path"}, "w500");
        String backdrop = image(row, new String[]{"backdrop", "backdrop_path"}, "w1280");
        String overview = CatalogClient.text(row.get("overview"), "", 300);
        return new CatalogClient.Item((int) id, type, title, year, poster, backdrop, overview);
    }

    static CatalogClient.Item details(Map<?, ?> row, String expectedType, int expectedId) throws IOException {
        if (row == null || !Boolean.TRUE.equals(row.get("success")) || expectedId < 1
                || !("movie".equals(expectedType) || "tv".equals(expectedType))) throw invalid();
        CatalogClient.Item item = item(row, expectedType);
        if (item == null || item.id != expectedId)
            throw new IOException("The catalog returned mismatched title information.");
        return item;
    }

    private static String image(Map<?, ?> row, String[] keys, String size) {
        for (String key : keys) {
            String value = CatalogClient.text(row.get(key), "", 512);
            if (value.startsWith("/") && !value.startsWith("//"))
                value = "https://image.tmdb.org/t/p/" + size + value;
            String approved = CatalogClient.approvedPoster(value);
            if (!approved.isEmpty()) return approved;
        }
        return "";
    }

    private static IOException invalid() {
        return new IOException("The catalog returned unreadable title information.");
    }
}
