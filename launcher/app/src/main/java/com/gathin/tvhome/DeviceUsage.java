package com.gathin.tvhome;

/** System-wide available memory includes reclaimable cache; used = total - available. */
final class DeviceUsage {
    final long total, available, used;
    final int percent;

    DeviceUsage(long total, long available) {
        if (total <= 0 || available < 0 || available > total) throw new IllegalArgumentException("Unavailable totals");
        this.total = total;
        this.available = available;
        this.used = total - available;
        this.percent = (int) Math.round(used * 100.0 / total);
    }
}
