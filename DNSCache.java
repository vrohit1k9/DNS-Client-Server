import java.util.*;

/**
 * DNSCache.java  (TTL Edition)
 * ─────────────────────────────────────────────────────────────────────────────
 * In-memory DNS cache with per-entry TTL (Time To Live).
 *
 * How TTL works
 * ─────────────
 *  Each entry stores the resolved IP and the wall-clock time at which it was
 *  cached.  On every get():
 *    • If  now < cachedAt + TTL  → CACHE HIT  (return IP)
 *    • If  now >= cachedAt + TTL → EXPIRED     (evict, return null → re-lookup)
 *
 *  Default TTL = 60 seconds (matches typical stub-resolver behaviour).
 *  A custom TTL can be passed per put() call, or changed globally via setDefaultTTL().
 *
 * Real-world relevance
 * ─────────────────────
 *  Real DNS resolvers honour the TTL field in each DNS record.  Authoritative
 *  servers set short TTLs for dynamic IPs and long TTLs for stable ones.
 *  This implementation mirrors that behaviour at the stub-resolver level.
 *
 * Thread-safety: all public methods are synchronized.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DNSCache {

    // ── Default TTL ──────────────────────────────────────────────────────────
    public static final int DEFAULT_TTL_SECONDS = 60;

    // ── Inner record: one cached entry ───────────────────────────────────────
    public static class Entry {
        final String ip;
        final long   cachedAtMs;   // System.currentTimeMillis() when stored
        final int    ttlSeconds;   // time-to-live in seconds

        Entry(String ip, int ttlSeconds) {
            this.ip          = ip;
            this.cachedAtMs  = System.currentTimeMillis();
            this.ttlSeconds  = ttlSeconds;
        }

        /** Seconds remaining before this entry expires (never negative). */
        public long ttlRemainingSeconds() {
            long elapsed = (System.currentTimeMillis() - cachedAtMs) / 1000L;
            return Math.max(0, ttlSeconds - elapsed);
        }

        /** True when the entry has lived longer than its TTL. */
        public boolean isExpired() {
            return ttlRemainingSeconds() == 0;
        }

        /** Elapsed time since this entry was cached, in milliseconds. */
        public long ageMs() {
            return System.currentTimeMillis() - cachedAtMs;
        }
    }

    // ── Internal storage ─────────────────────────────────────────────────────
    private final Map<String, Entry> cache = new LinkedHashMap<>();

    // ── Global default TTL (can be changed at runtime) ───────────────────────
    private int defaultTtl = DEFAULT_TTL_SECONDS;

    // ── Statistics ───────────────────────────────────────────────────────────
    private int totalHits      = 0;   // non-expired cache hits
    private int totalMisses    = 0;   // misses + expired evictions
    private int totalEvictions = 0;   // entries removed because TTL expired

    // ── Constructor ──────────────────────────────────────────────────────────
    public DNSCache() {}

    public DNSCache(int defaultTtlSeconds) {
        this.defaultTtl = defaultTtlSeconds;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Look up a domain.  Returns null on miss OR if the entry has expired
     * (the expired entry is evicted so the next put() re-populates it).
     *
     * @param domain  domain name (case-insensitive)
     * @return cached IP, or null
     */
    public synchronized String get(String domain) {
        Entry e = cache.get(domain.toLowerCase());
        if (e == null) {
            totalMisses++;
            return null;
        }
        if (e.isExpired()) {
            cache.remove(domain.toLowerCase());
            totalEvictions++;
            totalMisses++;
            return null;          // treat as miss; caller will re-resolve
        }
        totalHits++;
        return e.ip;
    }

    /**
     * Look up an entry and return the full Entry object (contains IP, TTL remaining, age).
     * Returns null on miss or if expired.
     */
    public synchronized Entry getEntry(String domain) {
        Entry e = cache.get(domain.toLowerCase());
        if (e == null) {
            totalMisses++;
            return null;
        }
        if (e.isExpired()) {
            cache.remove(domain.toLowerCase());
            totalEvictions++;
            totalMisses++;
            return null;
        }
        totalHits++;
        return e;
    }

    /**
     * Store a domain → IP mapping using the current default TTL.
     */
    public synchronized void put(String domain, String ip) {
        put(domain, ip, defaultTtl);
    }

    /**
     * Store a domain → IP mapping with an explicit TTL (seconds).
     */
    public synchronized void put(String domain, String ip, int ttlSeconds) {
        cache.put(domain.toLowerCase(), new Entry(ip, ttlSeconds));
    }

    /**
     * Return the full Entry for a domain (including TTL metadata), or null.
     * Does NOT update hit/miss counters, does NOT evict expired entries.
     */
    public synchronized Entry peek(String domain) {
        return cache.get(domain.toLowerCase());
    }

    /**
     * Return a snapshot of all current entries (including possibly-expired ones).
     * Callers should check Entry.isExpired() themselves.
     */
    public synchronized Map<String, Entry> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    /** Remove a specific entry (manual invalidation). */
    public synchronized void remove(String domain) {
        cache.remove(domain.toLowerCase());
    }

    /** Evict all entries whose TTL has elapsed. */
    public synchronized int evictExpired() {
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                removed++;
            }
        }
        totalEvictions += removed;
        return removed;
    }

    /** Remove ALL entries (full flush). */
    public synchronized void clear() {
        cache.clear();
    }

    /** Number of entries currently in cache (including potentially-expired ones). */
    public synchronized int size() {
        return cache.size();
    }

    public synchronized int getHits()      { return totalHits; }
    public synchronized int getMisses()    { return totalMisses; }
    public synchronized int getEvictions() { return totalEvictions; }

    /** Hit-rate as a percentage (0–100).  Returns 0 if no queries yet. */
    public synchronized double getHitRate() {
        int total = totalHits + totalMisses;
        return total == 0 ? 0.0 : (totalHits * 100.0 / total);
    }

    public synchronized int  getDefaultTTL()              { return defaultTtl; }
    public synchronized void setDefaultTTL(int seconds)   { this.defaultTtl = seconds; }

    public synchronized boolean contains(String domain) {
        Entry e = cache.get(domain.toLowerCase());
        return e != null && !e.isExpired();
    }

    /** Pretty-print cache contents to stdout (CLI debugging). */
    public synchronized void printCache() {
        System.out.println("\n+" + "-".repeat(66) + "+");
        System.out.printf("| %-20s | %-17s | %6s | %-8s |%n",
                "Domain", "IP Address", "TTL(s)", "Age(ms)");
        System.out.println("+" + "-".repeat(66) + "+");
        if (cache.isEmpty()) {
            System.out.println("|" + " ".repeat(26) + "(cache is empty)" + " ".repeat(24) + "|");
        } else {
            for (Map.Entry<String, Entry> kv : cache.entrySet()) {
                Entry e = kv.getValue();
                System.out.printf("| %-20s | %-17s | %6d | %-8d |%s%n",
                        kv.getKey(), e.ip,
                        e.ttlRemainingSeconds(), e.ageMs(),
                        e.isExpired() ? " [EXPIRED]" : "");
            }
        }
        System.out.println("+" + "-".repeat(66) + "+");
        System.out.printf("  Hits: %d | Misses: %d | Evictions: %d | Hit-rate: %.1f%%%n%n",
                totalHits, totalMisses, totalEvictions, getHitRate());
    }
}