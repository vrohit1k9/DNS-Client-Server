import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

/**
 * CacheStressTest.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Complicated DNS Cache Hit / Miss Benchmark & Simulation Tool
 *
 * Demonstrates:
 *   1. Cold-start misses (Local file DB & Upstream Real Internet DNS)
 *   2. Hot-cache hits (Sub-millisecond resolution & TTL countdown)
 *   3. Mixed interleaved traffic with Zipfian/Pareto distribution
 *   4. Cache expiration & eviction dynamics (TTL decay)
 *   5. Negative query handling (Non-existent domains)
 *   6. Comprehensive latency comparison & speedup analytics
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class CacheStressTest {

    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 5454;
    private static final int TIMEOUT_MS = 4000;

    public static void main(String[] args) throws Exception {
        printHeader();

        // 1. Check if DNSServer is running over UDP
        boolean serverActive = isServerListening();

        if (serverActive) {
            System.out.println(">>> DETECTED ACTIVE DNS SERVER ON PORT " + SERVER_PORT);
            System.out.println(">>> Running live network-level Cache Hit/Miss Demonstration...\n");
            runLiveNetworkDemo();
        } else {
            System.out.println(">>> DNSServer is not currently running on port " + SERVER_PORT);
            System.out.println(">>> Running direct in-memory Cache Engine Simulation with TTL decay...\n");
            runDirectCacheEngineDemo();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. LIVE NETWORK-BASED COMPLICATED TEST (Queries DNSServer over UDP)
    // ─────────────────────────────────────────────────────────────────────────
    private static void runLiveNetworkDemo() throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                   SCENARIO 1: COLD MISS VS HOT CACHE HIT                      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        // Step 1: Cold query to local DB record
        System.out.println("\n[Test 1.1] Querying 'google.com' (Pre-configured in records database):");
        QueryResult r1 = queryServer("google.com");
        printResult("Query 1 (Cold)", r1);

        // Step 2: Immediate repeated query
        System.out.println("\n[Test 1.2] Re-querying 'google.com' immediately (Expect Cache HIT):");
        QueryResult r2 = queryServer("google.com");
        printResult("Query 2 (Hot)", r2);

        System.out.printf("  ==> Speedup Factor: %.1fx faster on cache hit!%n",
                r2.latencyMs == 0 ? (double) r1.latencyMs : (double) r1.latencyMs / Math.max(1, r2.latencyMs));

        System.out.println("\n╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║            SCENARIO 2: UPSTREAM REAL INTERNET DNS RESOLUTION & CACHING        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        // Step 3: Unlisted domain (triggers real internet resolution via InetAddress)
        System.out.println("\n[Test 2.1] Querying 'cloudflare.com' (NOT in local db, triggers Real DNS):");
        QueryResult r3 = queryServer("cloudflare.com");
        printResult("Query 3 (Real DNS Cold)", r3);

        // Step 4: Warm query for unlisted domain
        System.out.println("\n[Test 2.2] Re-querying 'cloudflare.com' immediately (Now cached):");
        QueryResult r4 = queryServer("cloudflare.com");
        printResult("Query 4 (Real DNS Cached)", r4);

        System.out.println("\n╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 SCENARIO 3: NEGATIVE LOOKUP (NON-EXISTENT DOMAIN)             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        System.out.println("\n[Test 3.1] Querying 'non-existent-domain-xyz999.invalid':");
        QueryResult r5 = queryServer("non-existent-domain-xyz999.invalid");
        printResult("Query 5 (Invalid Domain)", r5);

        System.out.println("\n╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 SCENARIO 4: MIXED TRAFFIC WORKLOAD (20 QUERIES)               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        String[] queryStream = {
            "google.com", "github.com", "google.com", "openai.com", "github.com",
            "microsoft.com", "google.com", "example.com", "apple.com", "google.com",
            "github.com", "unknown-test-123.com", "amazon.com", "openai.com", "google.com",
            "wikipedia.org", "google.com", "github.com", "apple.com", "localhost"
        };

        int hits = 0, misses = 0;
        long totalHitTime = 0, totalMissTime = 0;

        System.out.printf("%-4s | %-22s | %-12s | %-15s | %-8s | %-10s%n",
                "Seq", "Domain", "Status", "Resolution", "Latency", "TTL Remaining");
        System.out.println("-".repeat(82));

        for (int i = 0; i < queryStream.length; i++) {
            String domain = queryStream[i];
            QueryResult qr = queryServer(domain);

            boolean isHit = "CACHE".equalsIgnoreCase(qr.source);
            if (isHit) {
                hits++;
                totalHitTime += qr.latencyMs;
            } else {
                misses++;
                totalMissTime += qr.latencyMs;
            }

            System.out.printf("#%-3d | %-22s | %-12s | %-15s | %4d ms  | %-10s%n",
                    (i + 1),
                    domain,
                    isHit ? "[HIT]" : "[MISS]",
                    qr.source,
                    qr.latencyMs,
                    qr.ttl);

            Thread.sleep(80); // slight delay to simulate real client traffic
        }

        System.out.println("=".repeat(82));
        double hitRatio = (double) hits / (hits + misses) * 100.0;
        double avgHitTime = hits > 0 ? (double) totalHitTime / hits : 0;
        double avgMissTime = misses > 0 ? (double) totalMissTime / misses : 0;

        System.out.printf("Stream Summary: Total=%d | Hits=%d | Misses=%d | Hit Rate=%.1f%%%n",
                queryStream.length, hits, misses, hitRatio);
        System.out.printf("Average Latency: Cache HIT = %.2f ms | Cache MISS = %.2f ms (%.1fx faster)%n%n",
                avgHitTime, avgMissTime, avgHitTime == 0 ? avgMissTime : avgMissTime / avgHitTime);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. DIRECT IN-MEMORY ENGINE SIMULATION (Demonstrating TTL Eviction & Expiry)
    // ─────────────────────────────────────────────────────────────────────────
    public static void runDirectCacheEngineDemo() throws Exception {
        System.out.println("Simulating DNS Cache Engine with Fast 3-Second TTL to demonstrate dynamic expiration...");

        // Create cache with 3s TTL
        DNSCache simulatedCache = new DNSCache(3);

        System.out.println("\n[Stage 1] Cold cache lookup for 'api.service.internal'");
        String res1 = simulatedCache.get("api.service.internal");
        System.out.println("Lookup 1: Result = " + res1 + " -> [CACHE MISS]");
        System.out.println("Simulating DB Fetch: Resolved to 10.10.50.1 -> Storing in cache with 3s TTL.");
        simulatedCache.put("api.service.internal", "10.10.50.1", 3);

        System.out.println("\n[Stage 2] Immediate lookups (0 to 2 seconds):");
        for (int i = 1; i <= 2; i++) {
            Thread.sleep(1000);
            DNSCache.Entry e = simulatedCache.getEntry("api.service.internal");
            if (e != null) {
                System.out.printf("Lookup at +%ds: IP = %s, TTL Remaining = %ds -> [CACHE HIT]%n",
                        i, e.ip, e.ttlRemainingSeconds());
            }
        }

        System.out.println("\n[Stage 3] Sleeping 2.5 seconds to cross TTL expiration boundary (TTL = 3s total)...");
        Thread.sleep(2500);

        System.out.println("\n[Stage 4] Post-expiration lookup:");
        DNSCache.Entry expiredEntry = simulatedCache.getEntry("api.service.internal");
        if (expiredEntry == null) {
            System.out.println("Lookup at +4.5s: Result = null -> [CACHE MISS via TTL EXPIRATION & EVICTION]");
        } else {
            System.out.println("Lookup at +4.5s: Found unexpected entry.");
        }

        System.out.printf("\nCache Statistics: Total Hits=%d | Total Misses=%d | Evictions=%d | Hit Rate=%.1f%%%n%n",
                simulatedCache.getHits(),
                simulatedCache.getMisses(),
                simulatedCache.getEvictions(),
                simulatedCache.getHitRate());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UDP Query Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private static class QueryResult {
        String domain;
        String ip;
        String source;
        String ttl;
        long latencyMs;
        String status;
        String raw;
    }

    private static boolean isServerListening() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(600);
            byte[] buf = "DNS|REQUEST|localhost".getBytes();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, InetAddress.getByName(SERVER_IP), SERVER_PORT);
            socket.send(packet);

            byte[] recvBuf = new byte[1024];
            DatagramPacket recv = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recv);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static QueryResult queryServer(String domain) {
        QueryResult qr = new QueryResult();
        qr.domain = domain;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            String msg = "DNS|REQUEST|" + domain;
            byte[] sendBuf = msg.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendBuf, sendBuf.length, InetAddress.getByName(SERVER_IP), SERVER_PORT);

            long start = System.currentTimeMillis();
            socket.send(sendPacket);

            byte[] recvBuf = new byte[1024];
            DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPacket);
            qr.latencyMs = System.currentTimeMillis() - start;

            qr.raw = new String(recvPacket.getData(), 0, recvPacket.getLength()).trim();
            String[] parts = qr.raw.split("\\|");

            if (parts.length >= 3 && parts[1].equals("RESPONSE")) {
                qr.ip = parts[3];
                qr.source = parts.length >= 5 ? parts[4] : "UNKNOWN";
                qr.ttl = parts.length >= 6 ? parts[5] + "s" : "N/A";
                qr.status = "SUCCESS";
            } else if (parts.length >= 3 && parts[1].equals("ERROR")) {
                qr.ip = "N/A";
                qr.source = "NONE";
                qr.ttl = "0s";
                qr.status = parts[parts.length - 1];
            } else {
                qr.status = "MALFORMED";
            }
        } catch (Exception e) {
            qr.status = "TIMEOUT/ERROR (" + e.getMessage() + ")";
            qr.latencyMs = -1;
            qr.source = "ERROR";
            qr.ttl = "0s";
        }
        return qr;
    }

    private static void printResult(String label, QueryResult r) {
        System.out.printf("  ┌─ %s%n", label);
        System.out.printf("  │ Domain      : %s%n", r.domain);
        System.out.printf("  │ Status      : %s%n", r.status);
        System.out.printf("  │ Resolved IP : %s%n", r.ip);
        System.out.printf("  │ Resolution  : %s%n",
                "CACHE".equalsIgnoreCase(r.source) ? ">>> CACHE HIT (RAM) <<<" :
                "REAL_DNS".equalsIgnoreCase(r.source) ? "CACHE MISS -> Real Upstream DNS" :
                "DATABASE".equalsIgnoreCase(r.source) ? "CACHE MISS -> Local records.txt" : r.source);
        System.out.printf("  │ Latency     : %d ms%n", r.latencyMs);
        System.out.printf("  │ TTL Left    : %s%n", r.ttl);
        System.out.println("  └────────────────────────────────────────────────────────────");
    }

    private static void printHeader() {
        System.out.println("===============================================================================");
        System.out.println("      COMPLICATED DNS CACHE HIT & MISS BENCHMARK / STRESS SUITE                ");
        System.out.println("===============================================================================");
    }
}
