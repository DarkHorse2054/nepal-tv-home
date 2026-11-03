package app.nepal.framextv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded local progress policy, kept independent of Android storage for offline checks. */
final class WatchProgress {
    static final int MAX_RECORDS = 40;
    static final int MAX_VISIBLE = 24;
    static final int MAX_TOMBSTONES = 40;
    static final double MIN_POSITION_SECONDS = 15;
    static final double MAX_DURATION_SECONDS = 7 * 24 * 60 * 60;
    private static final long MAX_TIMESTAMP = 253402300799999L;

    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, Long> tombstones = new LinkedHashMap<>();

    static final class Record {
        final String mediaType, title;
        final int id, season, episode;
        final double positionSeconds, durationSeconds;
        final long updatedAt;

        Record(EmbedSpec spec, double position, double duration, long updatedAt) {
            mediaType = spec.mediaType;
            id = spec.tmdbId;
            season = "tv".equals(mediaType) ? spec.season : 0;
            episode = "tv".equals(mediaType) ? spec.episode : 0;
            title = spec.title;
            positionSeconds = position;
            durationSeconds = duration;
            this.updatedAt = updatedAt;
        }

        EmbedSpec spec() {
            return new EmbedSpec(mediaType, id, season, episode, title);
        }

        String key() {
            return mediaType + ":" + id + ":" + season + ":" + episode;
        }
    }

    static final class Tombstone {
        final String key;
        final long updatedAt;

        Tombstone(String key, long updatedAt) {
            this.key = key;
            this.updatedAt = updatedAt;
        }
    }

    static String key(EmbedSpec spec) {
        return spec.mediaType + ":" + spec.tmdbId + ":"
                + ("tv".equals(spec.mediaType) ? spec.season + ":" + spec.episode : "0:0");
    }

    Record find(EmbedSpec spec) {
        return spec == null ? null : records.get(key(spec));
    }

    List<Record> entries() {
        List<Record> result = storedRecords();
        return new ArrayList<>(result.subList(0, Math.min(result.size(), MAX_VISIBLE)));
    }

    List<Record> storedRecords() {
        List<Record> result = new ArrayList<>(records.values());
        Collections.sort(result, Comparator.comparingLong((Record entry) -> entry.updatedAt)
                .reversed().thenComparing(Record::key));
        return result;
    }

    List<Tombstone> storedTombstones() {
        List<Tombstone> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : tombstones.entrySet()) {
            result.add(new Tombstone(entry.getKey(), entry.getValue()));
        }
        Collections.sort(result, Comparator.comparingLong((Tombstone entry) -> entry.updatedAt)
                .reversed().thenComparing(entry -> entry.key));
        return result;
    }

    boolean record(EmbedSpec spec, double position, double duration, boolean ended, long observedAt) {
        if (spec == null || !validTimestamp(observedAt) || !Double.isFinite(position)
                || !Double.isFinite(duration) || duration <= 0 || duration > MAX_DURATION_SECONDS
                || position < MIN_POSITION_SECONDS || position > duration + 30) return false;
        String key = key(spec);
        if (observedAt <= latestTimestamp(key)) return false;
        if (ended || duration - position <= 30 || position / duration >= .95) {
            return removeAt(key, observedAt);
        }
        records.put(key, new Record(spec, position, duration, observedAt));
        tombstones.remove(key);
        trim();
        return true;
    }

    boolean restart(EmbedSpec spec, long now) {
        if (spec == null || !validTimestamp(now)) return false;
        String key = key(spec);
        long latest = latestTimestamp(key);
        return removeAt(key, Math.max(now, Math.min(MAX_TIMESTAMP, latest + 1)));
    }

    /** Restores a validated persisted deletion without overriding a newer saved position. */
    boolean restoreTombstone(String key, long observedAt) {
        if (!validKey(key) || !validTimestamp(observedAt)
                || observedAt < latestTimestamp(key)) return false;
        return removeAt(key, observedAt);
    }

    private boolean removeAt(String key, long observedAt) {
        records.remove(key);
        tombstones.put(key, observedAt);
        trim();
        return true;
    }

    private long latestTimestamp(String key) {
        Record record = records.get(key);
        Long removed = tombstones.get(key);
        return Math.max(record == null ? 0 : record.updatedAt, removed == null ? 0 : removed);
    }

    private void trim() {
        if (records.size() > MAX_RECORDS) {
            List<Record> ordered = storedRecords();
            for (int index = MAX_RECORDS; index < ordered.size(); index++) {
                Record old = ordered.get(index);
                records.remove(old.key());
                tombstones.put(old.key(), old.updatedAt);
            }
        }
        if (tombstones.size() > MAX_TOMBSTONES) {
            List<Tombstone> ordered = storedTombstones();
            for (int index = MAX_TOMBSTONES; index < ordered.size(); index++) {
                tombstones.remove(ordered.get(index).key);
            }
        }
    }

    private static boolean validTimestamp(long value) {
        return value > 0 && value <= MAX_TIMESTAMP;
    }

    private static boolean validKey(String key) {
        if (key == null || key.length() > 64) return false;
        String[] fields = key.split(":", -1);
        if (fields.length != 4) return false;
        try {
            EmbedSpec spec = new EmbedSpec(fields[0], Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), "Theater");
            return key.equals(key(spec));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
