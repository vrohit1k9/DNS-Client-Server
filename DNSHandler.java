import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;

/**
 * DNSHandler.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles a SINGLE incoming DNS request inside its own thread.
 *
 * Lifecycle (per request)
 * ───────────────────────
 *  1. Constructor receives the raw DatagramPacket from DNSServer.
 *  2. run() is invoked by the Thread.
 *  3. Parse the custom DNS request format:
 *        DNS|REQUEST|<domain>
 *  4. Check the DNSCache for a previous answer.
 *     ├─ CACHE HIT  → reply immediately.
 *     └─ CACHE MISS → look up the domain in the DNS records map.
 *                      Store the result in the cache.
 *  5. Build and send the response packet back to the client:
 *        Success → DNS|RESPONSE|<domain>|<ip>
 *        Failure → DNS|ERROR|<domain>|DOMAIN_NOT_FOUND
 *
 * Custom DNS Message Protocol (plain-text, pipe-delimited):
 * ─────────────────────────────────────────────────────────
 *   Request  :  DNS|REQUEST|<domain>
 *   Response :  DNS|RESPONSE|<domain>|<ip>
 *   Error    :  DNS|ERROR|<domain>|DOMAIN_NOT_FOUND
 *               DNS|ERROR|<domain>|INVALID_REQUEST_FORMAT
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DNSHandler implements Runnable {

    // ── shared server-side resources (passed in from DNSServer) ─────────────
    private final DatagramSocket  serverSocket;
    private final DatagramPacket  requestPacket;
    private final Map<String, String> dnsRecords;
    private final DNSCache        cache;

    // ── constructor ──────────────────────────────────────────────────────────
    public DNSHandler(DatagramSocket  serverSocket,
                      DatagramPacket  requestPacket,
                      Map<String, String> dnsRecords,
                      DNSCache        cache) {

        this.serverSocket  = serverSocket;
        this.requestPacket = requestPacket;
        this.dnsRecords    = dnsRecords;
        this.cache         = cache;
    }

    // ── Runnable entry-point ─────────────────────────────────────────────────
    @Override
    public void run() {

        long startTime = System.currentTimeMillis();

        // ── 1. Extract client details ────────────────────────────────────────
        InetAddress clientAddress = requestPacket.getAddress();
        int         clientPort    = requestPacket.getPort();

        // ── 2. Decode request bytes → String ────────────────────────────────
        String rawMessage = new String(
                requestPacket.getData(), 0, requestPacket.getLength()).trim();

        System.out.println("\n" + "─".repeat(48));
        System.out.println("Client       : " + clientAddress.getHostAddress());
        System.out.println("Raw request  : " + rawMessage);

        // ── 3. Parse custom DNS protocol ─────────────────────────────────────
        String responseMessage;

        // Expected format: DNS|REQUEST|<domain>
        String[] parts = rawMessage.split("\\|");

        if (parts.length != 3
                || !parts[0].equals("DNS")
                || !parts[1].equals("REQUEST")) {

            // Malformed request
            responseMessage = "DNS|ERROR|UNKNOWN|INVALID_REQUEST_FORMAT";
            System.out.println("Status       : INVALID REQUEST FORMAT");

        } else {

            String domain = parts[2].trim().toLowerCase();
            System.out.println("Domain       : " + domain);

            responseMessage = resolve(domain);
        }

        // ── 4. Send response ─────────────────────────────────────────────────
        try {
            byte[] responseBytes = responseMessage.getBytes();
            DatagramPacket responsePacket = new DatagramPacket(
                    responseBytes,
                    responseBytes.length,
                    clientAddress,
                    clientPort);

            // Use synchronized block so multiple threads don't interleave sends
            synchronized (serverSocket) {
                serverSocket.send(responsePacket);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("Response Time: " + elapsed + " ms");
            System.out.println("Response     : " + responseMessage);

        } catch (Exception e) {
            System.err.println("[DNSHandler] Error sending response: " + e.getMessage());
        }

        System.out.println("─".repeat(48));
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Perform cache → records look-up and build the appropriate response string.
     *
     * @param  domain  the domain name to resolve (already lower-cased)
     * @return a correctly formatted DNS response/error string
     */
    private String resolve(String domain) {

        // ── Validate domain name (basic rules) ───────────────────────────────
        if (domain.isEmpty()) {
            System.out.println("Status       : EMPTY DOMAIN");
            return "DNS|ERROR|" + domain + "|INVALID_DOMAIN_NAME";
        }

        // ── 1. Check DNSCache first ──────────────────────────────────────────
        DNSCache.Entry cached = cache.getEntry(domain);

        if (cached != null) {
            // ── CACHE HIT ────────────────────────────────────────────────────
            long ttlLeft = cached.ttlRemainingSeconds();
            System.out.println("Cache        : HIT (TTL: " + ttlLeft + "s remaining)");
            System.out.println("IP Address   : " + cached.ip);
            return "DNS|RESPONSE|" + domain + "|" + cached.ip + "|CACHE|" + ttlLeft;
        }

        // ── 2. Check MongoDB search history ──────────────────────────────────
        System.out.println("Cache        : MISS");
        MongoHistoryService mongo = MongoHistoryService.getInstance();
        SearchRecord historyHit = mongo.findAndRecordHit(domain);

        if (historyHit != null) {
            // ── SEARCH HISTORY HIT ───────────────────────────────────────────
            String ip = historyHit.getIp();
            cache.put(domain, ip); // populate cache with TTL
            System.out.println("MongoDB      : HIT (Search count: " + historyHit.getSearchCount() + ")");
            System.out.println("IP Address   : " + ip);
            System.out.println("Source       : MONGODB SEARCH HISTORY");
            return "DNS|RESPONSE|" + domain + "|" + ip + "|MONGODB|" + historyHit.getSearchCount();
        }

        System.out.println("MongoDB      : NOT FOUND");

        // ── 3. Use existing DNS resolution logic ─────────────────────────────
        System.out.println("Searching DNS records...");
        String ip = dnsRecords.get(domain);

        if (ip != null) {
            // ── Record found in local file → cache it & store in MongoDB ─────
            cache.put(domain, ip);
            mongo.saveNewSearch(domain, ip, "DNS_LOOKUP");
            System.out.println("IP Address   : " + ip);
            System.out.println("Source       : LOCAL RECORDS -> SAVED TO MONGODB");
            return "DNS|RESPONSE|" + domain + "|" + ip + "|DNS_LOOKUP|" + cache.getDefaultTTL();

        } else {
            // ── Not in file → try real DNS lookup via InetAddress ─────────────
            System.out.println("Not in local records. Trying real DNS lookup...");
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(domain);
                String resolvedIP = address.getHostAddress();
                cache.put(domain, resolvedIP);
                mongo.saveNewSearch(domain, resolvedIP, "DNS_LOOKUP");
                System.out.println("IP Address   : " + resolvedIP);
                System.out.println("Source       : REAL DNS LOOKUP -> SAVED TO MONGODB");
                return "DNS|RESPONSE|" + domain + "|" + resolvedIP + "|DNS_LOOKUP|" + cache.getDefaultTTL();
            } catch (java.net.UnknownHostException e) {
                System.out.println("IP Address   : NOT FOUND (real DNS also failed)");
                return "DNS|ERROR|" + domain + "|DOMAIN_NOT_FOUND";
            }
        }
    }
}
