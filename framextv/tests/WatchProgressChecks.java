package app.nepal.framextv;

import java.util.List;

/** Executes the shipped progress policy without an Android runtime or test dependency. */
public final class WatchProgressChecks {
    private static int passed;

    public static void main(String[] args) {
        check("first real progress is resumable", () -> {
            WatchProgress model = new WatchProgress();
            truth(model.record(movie(1), 15, 7200, false, 1000));
            close(15, model.find(movie(1)).positionSeconds);
            equal(1, model.entries().size());
        });
        check("new title is not created from a zero or near-start sample", () -> {
            WatchProgress model = new WatchProgress();
            truth(!model.record(movie(1), 0, 7200, false, 1000));
            truth(!model.record(movie(1), 14.999, 7200, false, 1001));
            truth(model.find(movie(1)) == null);
        });
        check("startup zero cannot overwrite a previous position or completion", () -> {
            WatchProgress model = saved();
            truth(!model.record(movie(1), 0, 7200, false, 2000));
            truth(!model.record(movie(1), 0, 20, true, 2001));
            close(300, model.find(movie(1)).positionSeconds);
            equal(1000L, model.find(movie(1)).updatedAt);
        });
        check("explicit backward seek is saved after the minimum", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 20, 7200, false, 2000));
            close(20, model.find(movie(1)).positionSeconds);
        });
        check("NaN infinity negative and oversized samples are ignored", () -> {
            WatchProgress model = saved();
            double[][] invalid = {{Double.NaN, 7200}, {Double.POSITIVE_INFINITY, 7200},
                    {-1, 7200}, {300, Double.NaN}, {300, Double.POSITIVE_INFINITY},
                    {300, -1}, {300, 0}, {300, WatchProgress.MAX_DURATION_SECONDS + 1},
                    {7231, 7200}};
            for (double[] sample : invalid) {
                truth(!model.record(movie(1), sample[0], sample[1], false, 2000));
            }
            close(300, model.find(movie(1)).positionSeconds);
        });
        check("invalid timestamps are ignored", () -> {
            WatchProgress model = saved();
            truth(!model.record(movie(1), 600, 7200, false, 0));
            truth(!model.record(movie(1), 600, 7200, false, -1));
            truth(!model.record(movie(1), 600, 7200, false, Long.MAX_VALUE));
            close(300, model.find(movie(1)).positionSeconds);
        });
        check("ended removes an existing saved title", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 600, 7200, true, 2000));
            truth(model.find(movie(1)) == null);
            equal(0, model.entries().size());
        });
        check("last thirty seconds counts as completed", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 970, 1000, false, 2000));
            truth(model.find(movie(1)) == null);
        });
        check("ninety-five percent counts as completed", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 9500, 10000, false, 2000));
            truth(model.find(movie(1)) == null);
        });
        check("a real unfinished position stays resumable", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 9499, 10000, false, 2000));
            close(9499, model.find(movie(1)).positionSeconds);
        });
        check("stale and duplicate async samples cannot replace newer progress", () -> {
            WatchProgress model = saved();
            truth(model.record(movie(1), 600, 7200, false, 3000));
            truth(!model.record(movie(1), 800, 7200, false, 2000));
            truth(!model.record(movie(1), 900, 7200, false, 3000));
            close(600, model.find(movie(1)).positionSeconds);
        });
        check("completion tombstone rejects older progress", () -> {
            WatchProgress model = saved();
            model.record(movie(1), 7190, 7200, false, 3000);
            truth(!model.record(movie(1), 600, 7200, false, 2000));
            truth(!model.record(movie(1), 600, 7200, false, 3000));
            truth(model.find(movie(1)) == null);
        });
        check("watching completed title again can create new progress", () -> {
            WatchProgress model = saved();
            model.record(movie(1), 7190, 7200, false, 2000);
            truth(model.record(movie(1), 30, 7200, false, 3000));
            close(30, model.find(movie(1)).positionSeconds);
        });
        check("Start over removes progress and rejects pending snapshots", () -> {
            WatchProgress model = saved();
            truth(model.restart(movie(1), 2000));
            truth(!model.record(movie(1), 600, 7200, false, 1500));
            truth(!model.record(movie(1), 600, 7200, false, 2000));
            truth(model.find(movie(1)) == null);
            truth(model.record(movie(1), 20, 7200, false, 2001));
        });
        check("Start over also protects titles without saved progress", () -> {
            WatchProgress model = new WatchProgress();
            truth(model.restart(movie(1), 2000));
            truth(!model.record(movie(1), 300, 7200, false, 1000));
        });
        check("Start over stays newer when wall clock moves backwards", () -> {
            WatchProgress model = saved();
            model.restart(movie(1), 100);
            truth(!model.record(movie(1), 500, 7200, false, 1000));
            truth(!model.record(movie(1), 500, 7200, false, 1001));
            truth(model.record(movie(1), 20, 7200, false, 1002));
        });
        check("TV episodes and movies have separate canonical identities", () -> {
            WatchProgress model = new WatchProgress();
            model.record(movie(1), 300, 7200, false, 1000);
            model.record(tv(1, 1, 1), 600, 3600, false, 1001);
            model.record(tv(1, 1, 2), 900, 3600, false, 1002);
            model.record(tv(1, 0, 1), 1200, 3600, false, 1003);
            equal(4, model.entries().size());
            close(600, model.find(tv(1, 1, 1)).positionSeconds);
            close(900, model.find(tv(1, 1, 2)).positionSeconds);
            close(1200, model.find(tv(1, 0, 1)).positionSeconds);
        });
        check("movie metadata cannot split one movie into different identities", () -> {
            WatchProgress model = new WatchProgress();
            model.record(new EmbedSpec("movie", 1, 22, 40, "A"), 300, 7200, false, 1000);
            close(300, model.find(movie(1)).positionSeconds);
            equal("movie:1:0:0", model.storedRecords().get(0).key());
            equal(0, model.find(movie(1)).season);
            equal(0, model.find(movie(1)).episode);
        });
        check("visible history is recent first and capped at twenty-four", () -> {
            WatchProgress model = new WatchProgress();
            for (int index = 1; index <= 35; index++) {
                model.record(movie(index), 300, 7200, false, 1000 + index);
            }
            List<WatchProgress.Record> entries = model.entries();
            equal(24, entries.size());
            equal(35, entries.get(0).id);
            equal(12, entries.get(23).id);
            equal(35, model.storedRecords().size());
        });
        check("hard storage cap retains forty most recently watched titles", () -> {
            WatchProgress model = new WatchProgress();
            for (int index = 1; index <= 60; index++) {
                model.record(movie(index), 300, 7200, false, 1000 + index);
            }
            equal(40, model.storedRecords().size());
            truth(model.find(movie(20)) == null);
            truth(model.find(movie(21)) != null);
            truth(!model.record(movie(1), 600, 7200, false, 1001));
            equal(40, model.storedRecords().size());
        });
        check("tombstone storage is bounded", () -> {
            WatchProgress model = new WatchProgress();
            for (int index = 1; index <= 100; index++) model.restart(movie(index), 1000 + index);
            equal(40, model.storedTombstones().size());
            equal("movie:100:0:0", model.storedTombstones().get(0).key);
        });
        check("history snapshots cannot mutate the stored collections", () -> {
            WatchProgress model = saved();
            model.entries().clear();
            model.storedRecords().clear();
            truth(model.find(movie(1)) != null);
            model.restart(movie(1), 2000);
            model.storedTombstones().clear();
            truth(!model.record(movie(1), 300, 7200, false, 1500));
        });
        check("persistence round trip retains positions and deletion guards", () -> {
            WatchProgress source = saved();
            source.record(tv(2, 3, 4), 800, 3600, false, 2000);
            source.restart(movie(3), 3000);
            WatchProgress restored = restore(source);
            close(300, restored.find(movie(1)).positionSeconds);
            close(800, restored.find(tv(2, 3, 4)).positionSeconds);
            truth(!restored.record(movie(3), 500, 7200, false, 2999));
        });
        check("persisted tombstones reject malformed keys and timestamps", () -> {
            WatchProgress model = saved();
            String[] invalid = {null, "", "movie:1", "movie:01:0:0", "movie:1:2:0", "tv:1:-1:1",
                    "tv:1:1:0", "tv:1:1001:1", "tv:1:1:10001", "movie:-1:0:0", "other:1:0:0",
                    "movie:2147483648:0:0", "https://example.invalid/watch/1"};
            for (String key : invalid) truth(!model.restoreTombstone(key, 2000));
            truth(!model.restoreTombstone("movie:1:0:0", 0));
            truth(!model.restoreTombstone("movie:1:0:0", Long.MAX_VALUE));
            close(300, model.find(movie(1)).positionSeconds);
        });
        check("newer saved progress wins over older persisted deletion", () -> {
            WatchProgress model = saved();
            truth(!model.restoreTombstone("movie:1:0:0", 999));
            close(300, model.find(movie(1)).positionSeconds);
        });
        check("deletion wins an equal-timestamp corrupt-data tie", () -> {
            WatchProgress model = saved();
            truth(model.restoreTombstone("movie:1:0:0", 1000));
            truth(model.find(movie(1)) == null);
        });
        check("stored title is bounded sanitized and reconstructs a validated embed", () -> {
            StringBuilder longTitle = new StringBuilder("A\nB");
            for (int index = 0; index < 300; index++) longTitle.append('x');
            WatchProgress model = new WatchProgress();
            model.record(new EmbedSpec("tv", 123, 0, 5, longTitle.toString()), 300, 3600, false, 1000);
            WatchProgress.Record record = model.storedRecords().get(0);
            equal(160, record.title.length());
            truth(record.title.startsWith("A B"));
            equal("/embed/123/0/5", record.spec().path);
        });
        System.out.println("Watch progress checks passed: " + passed);
    }

    private static WatchProgress saved() {
        WatchProgress model = new WatchProgress();
        model.record(movie(1), 300, 7200, false, 1000);
        return model;
    }

    private static WatchProgress restore(WatchProgress source) {
        WatchProgress restored = new WatchProgress();
        for (WatchProgress.Record record : source.storedRecords()) {
            restored.record(record.spec(), record.positionSeconds, record.durationSeconds, false, record.updatedAt);
        }
        for (WatchProgress.Tombstone tombstone : source.storedTombstones()) {
            restored.restoreTombstone(tombstone.key, tombstone.updatedAt);
        }
        return restored;
    }

    private static EmbedSpec movie(int id) { return new EmbedSpec("movie", id, 0, 0, "Movie " + id); }
    private static EmbedSpec tv(int id, int season, int episode) {
        return new EmbedSpec("tv", id, season, episode, "Show " + id);
    }
    private static void check(String name, Runnable action) {
        try {
            action.run();
            passed++;
        } catch (RuntimeException | AssertionError failure) {
            throw new AssertionError(name, failure);
        }
    }
    private static void truth(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }
    private static void close(double expected, double actual) {
        if (Math.abs(expected - actual) > .0001) throw new AssertionError(expected + " != " + actual);
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
