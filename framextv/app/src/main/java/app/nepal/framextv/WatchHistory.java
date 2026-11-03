package app.nepal.framextv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Private on-device watch history. No stream addresses, account data or external storage. */
final class WatchHistory {
    private static final String PREFERENCES = "theater_watch_history";
    private static final String DATA = "progress_v1";
    private static final int MAX_BYTES = 64 * 1024;
    private static final Object STORAGE_LOCK = new Object();
    private final SharedPreferences preferences;
    private WatchProgress progress;

    static final class Entry {
        final String mediaType, title;
        final int id, season, episode;
        final double positionSeconds, durationSeconds;
        final long updatedAt;

        Entry(WatchProgress.Record record) {
            mediaType = record.mediaType;
            id = record.id;
            season = record.season;
            episode = record.episode;
            title = record.title;
            positionSeconds = record.positionSeconds;
            durationSeconds = record.durationSeconds;
            updatedAt = record.updatedAt;
        }

        EmbedSpec spec() {
            return new EmbedSpec(mediaType, id, season, episode, title);
        }
    }

    WatchHistory(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized List<Entry> entries() {
        synchronized (STORAGE_LOCK) {
            load();
            List<Entry> result = new ArrayList<>();
            for (WatchProgress.Record record : progress.entries()) result.add(new Entry(record));
            return result;
        }
    }

    synchronized Entry find(EmbedSpec spec) {
        synchronized (STORAGE_LOCK) {
            load();
            WatchProgress.Record record = progress.find(spec);
            return record == null ? null : new Entry(record);
        }
    }

    synchronized void record(EmbedSpec spec, double position, double duration, boolean ended, long observedAt) {
        synchronized (STORAGE_LOCK) {
            load();
            if (progress.record(spec, position, duration, ended, observedAt)) persist();
        }
    }

    synchronized void remove(Entry entry) {
        if (entry != null) restart(entry.spec());
    }

    synchronized void restart(EmbedSpec spec) {
        synchronized (STORAGE_LOCK) {
            load();
            if (progress.restart(spec, System.currentTimeMillis())) persist();
        }
    }

    private void load() {
        // SharedPreferences updates its process cache before apply() returns. Reading under one
        // process-wide lock keeps separate browser/player Activity instances consistent.
        progress = new WatchProgress();
        final String raw;
        try {
            raw = preferences.getString(DATA, null);
        } catch (ClassCastException malformed) {
            preferences.edit().remove(DATA).apply();
            return;
        }
        if (raw == null) return;
        if (raw.length() > MAX_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            preferences.edit().remove(DATA).apply();
            return;
        }
        try {
            JSONObject document = new JSONObject(raw);
            if (document.getInt("version") != 1) throw new JSONException("Unsupported local history version");
            JSONArray records = document.getJSONArray("entries");
            JSONArray removed = document.getJSONArray("removed");
            if (records.length() > WatchProgress.MAX_RECORDS || removed.length() > WatchProgress.MAX_TOMBSTONES) {
                throw new JSONException("Local history exceeds its record limit");
            }
            for (int index = 0; index < records.length(); index++) {
                try {
                    JSONObject entry = records.getJSONObject(index);
                    EmbedSpec spec = new EmbedSpec(entry.getString("type"), entry.getInt("id"),
                            entry.getInt("season"), entry.getInt("episode"), entry.getString("title"));
                    progress.record(spec, entry.getDouble("position"), entry.getDouble("duration"),
                            false, entry.getLong("updated"));
                } catch (JSONException | IllegalArgumentException invalidEntry) {
                    // One malformed record does not discard the remaining valid local history.
                }
            }
            for (int index = 0; index < removed.length(); index++) {
                try {
                    JSONObject entry = removed.getJSONObject(index);
                    progress.restoreTombstone(entry.getString("key"), entry.getLong("updated"));
                } catch (JSONException invalidEntry) {
                    // Invalid deletion metadata is ignored rather than exposed to playback.
                }
            }
        } catch (JSONException malformed) {
            preferences.edit().remove(DATA).apply();
        }
    }

    private void persist() {
        try {
            JSONArray records = new JSONArray();
            for (WatchProgress.Record record : progress.storedRecords()) {
                records.put(new JSONObject().put("type", record.mediaType).put("id", record.id)
                        .put("season", record.season).put("episode", record.episode).put("title", record.title)
                        .put("position", record.positionSeconds).put("duration", record.durationSeconds)
                        .put("updated", record.updatedAt));
            }
            JSONArray removed = new JSONArray();
            for (WatchProgress.Tombstone tombstone : progress.storedTombstones()) {
                removed.put(new JSONObject().put("key", tombstone.key).put("updated", tombstone.updatedAt));
            }
            String raw = new JSONObject().put("version", 1).put("entries", records).put("removed", removed).toString();
            if (raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                preferences.edit().putString(DATA, raw).apply();
            }
        } catch (JSONException invalid) {
            // The policy accepts only finite numeric values; preserve the last valid disk snapshot.
        }
    }
}
