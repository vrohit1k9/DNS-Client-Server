import java.util.HashMap;
import java.util.Map;

/**
 * MongoDnsTest.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Verification test script demonstrating the required lookup priority:
 *
 * 1. First search ("google.com"):
 *    DNS Cache   → MISS
 *    MongoDB     → NOT FOUND
 *    DNS lookup  → performed (from records or live)
 *    MongoDB     → saved (count = 1)
 *    Result      → IP
 *
 * 2. Second search ("google.com"):
 *    (Cache flushed to demonstrate MongoDB persistent retrieval)
 *    DNS Cache   → MISS
 *    MongoDB     → FOUND (searchCount = 2)
 *    Result      → stored IP
 *    Status      → SEARCH HISTORY HIT
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MongoDnsTest {

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("     CUSTOM DNS - MONGODB LOOKUP PRIORITY VERIFICATION      ");
        System.out.println("════════════════════════════════════════════════════════════\n");

        MongoHistoryService mongo = MongoHistoryService.getInstance();
        System.out.println("MongoDB Connection Status: " + (mongo.isConnected() ? "ONLINE" : "OFFLINE / FALLBACK"));
        System.out.println("Database                 : " + mongo.getDatabaseName());
        System.out.println("Collection               : " + mongo.getCollectionName());
        System.out.println();

        DNSCache cache = new DNSCache();
        Map<String, String> records = new HashMap<>();
        records.put("google.com", "142.250.195.14");

        String domain = "google.com";

        // ── TEST 1: First Search ─────────────────────────────────────────────
        System.out.println("[TEST 1] First Search for '" + domain + "'");
        System.out.println("------------------------------------------------------------");
        DNSCache.Entry cached1 = cache.getEntry(domain);
        if (cached1 != null) {
            System.out.println("Step 1 (Cache)   : HIT (" + cached1.ip + ")");
        } else {
            System.out.println("Step 1 (Cache)   : MISS");
            SearchRecord hist1 = mongo.findAndRecordHit(domain);
            if (hist1 != null) {
                System.out.println("Step 2 (MongoDB) : HIT (Search count: " + hist1.getSearchCount() + ")");
                System.out.println("Result Status    : SEARCH HISTORY HIT -> " + hist1.getIp());
            } else {
                System.out.println("Step 2 (MongoDB) : NOT FOUND");
                String resolvedIp = records.get(domain);
                System.out.println("Step 3 (DNS)     : RESOLVED (" + resolvedIp + ")");
                mongo.saveNewSearch(domain, resolvedIp, "DNS_LOOKUP");
                cache.put(domain, resolvedIp);
                System.out.println("Step 4 (Save)    : Saved to MongoDB & cached");
                System.out.println("Result Status    : NEW SEARCH -> " + resolvedIp);
            }
        }
        System.out.println();

        // ── TEST 2: Second Search (Simulate after cache TTL expiry) ───────────
        System.out.println("[TEST 2] Second Search for '" + domain + "' (Simulating Cache Expiry)");
        System.out.println("------------------------------------------------------------");
        cache.clear(); // Flushed to test MongoDB retrieval

        DNSCache.Entry cached2 = cache.getEntry(domain);
        if (cached2 != null) {
            System.out.println("Step 1 (Cache)   : HIT (" + cached2.ip + ")");
        } else {
            System.out.println("Step 1 (Cache)   : MISS");
            SearchRecord hist2 = mongo.findAndRecordHit(domain);
            if (hist2 != null) {
                System.out.println("Step 2 (MongoDB) : FOUND!");
                System.out.println("  Search Count   : " + hist2.getSearchCount());
                System.out.println("  Stored IP      : " + hist2.getIp());
                System.out.println("  Last Searched  : " + hist2.getLastSearched());
                System.out.println("Result Status    : SEARCH HISTORY HIT -> " + hist2.getIp());
            } else {
                System.out.println("Step 2 (MongoDB) : NOT FOUND (MongoDB offline or not seeded)");
            }
        }
        System.out.println();

        // ── TEST 3: Persistent History Listing ──────────────────────────────
        System.out.println("[TEST 3] Loading Persistent Search History from MongoDB");
        System.out.println("------------------------------------------------------------");
        java.util.List<SearchRecord> all = mongo.getAllHistory();
        System.out.println("Total Persistent Records in MongoDB: " + all.size());
        for (SearchRecord r : all) {
            System.out.printf("  Domain: %-18s IP: %-16s Searches: %-3d Last: %s%n",
                    r.getDomain(), r.getIp(), r.getSearchCount(), r.getLastSearched());
        }
        System.out.println("\nVerification test completed.");
    }
}
