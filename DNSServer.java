import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * DNSServer.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Entry-point for the Custom DNS Server.
 *
 * Responsibilities
 * ────────────────
 *  • Bind a UDP DatagramSocket on port 5353.
 *  • Load DNS records from  dns_records.txt  into a HashMap at startup.
 *  • Create a shared DNSCache instance.
 *  • Loop forever: receive a UDP packet → spawn a DNSHandler thread.
 *
 * Multi-client support
 * ─────────────────────
 *  Each incoming request is handled by a new Thread(DNSHandler). This means
 *  Client-1, Client-2, Client-3 … are all processed concurrently without
 *  blocking one another (classic thread-per-request model).
 *
 * Custom protocol (handled by DNSHandler)
 * ─────────────────────────────────────────
 *   Request  :  DNS|REQUEST|<domain>
 *   Response :  DNS|RESPONSE|<domain>|<ip>
 *   Error    :  DNS|ERROR|<domain>|<reason>
 *
 * Usage
 * ─────
 *   javac *.java
 *   java DNSServer
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DNSServer {

    // ── Configuration ────────────────────────────────────────────────────────
    private static final int    PORT            = 5454;   // changed from 5353 (occupied by Brave mDNS)
    private static final int    BUFFER_SIZE     = 1024;   // bytes per datagram
    private static final String RECORDS_FILE    = "dns_records.txt";

    // ── Main ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {

        printBanner();

        // ── Load DNS records from file ────────────────────────────────────────
        Map<String, String> dnsRecords = loadDNSRecords(RECORDS_FILE);
        if (dnsRecords == null) {
            System.err.println("[FATAL] Could not load " + RECORDS_FILE + ". Exiting.");
            System.exit(1);
        }

        // ── Create the shared cache ───────────────────────────────────────────
        DNSCache cache = new DNSCache();

        // ── Initialize MongoDB Search History ─────────────────────────────────
        MongoHistoryService mongoService = MongoHistoryService.getInstance();
        System.out.println("MongoDB Search History : " + (mongoService.isConnected()
                ? "CONNECTED (" + mongoService.getDatabaseName() + "." + mongoService.getCollectionName() + ")"
                : "OFFLINE (Continuing in fallback mode)"));

        // ── Open the UDP socket ───────────────────────────────────────────────
        try (DatagramSocket serverSocket = new DatagramSocket(PORT)) {

            System.out.println("Server started successfully");
            System.out.println("IP   : " + InetAddress.getLocalHost().getHostAddress());
            System.out.println("Port : " + PORT);
            System.out.println();
            System.out.println("Waiting for requests...");
            System.out.println("(Press Ctrl+C to stop the server)\n");

            // ── Main server loop ──────────────────────────────────────────────
            while (true) {

                // Prepare an empty buffer for the next incoming packet
                byte[]         buffer        = new byte[BUFFER_SIZE];
                DatagramPacket requestPacket = new DatagramPacket(buffer, buffer.length);

                // BLOCKING call – waits until a datagram arrives
                serverSocket.receive(requestPacket);

                // Hand off to a new handler thread immediately
                DNSHandler handler = new DNSHandler(
                        serverSocket,
                        requestPacket,
                        dnsRecords,
                        cache);

                Thread handlerThread = new Thread(handler);
                handlerThread.setDaemon(true);   // die when main thread exits
                handlerThread.start();
            }

        } catch (Exception e) {
            System.err.println("[DNSServer] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Read dns_records.txt and populate a HashMap.
     *
     * File format (one record per line):
     *   <domain>=<ip>
     *   # lines starting with '#' are comments
     *   blank lines are ignored
     *
     * @param  filename  path to the DNS records file
     * @return populated map, or null on fatal I/O error
     */
    private static Map<String, String> loadDNSRecords(String filename) {

        Map<String, String> records = new HashMap<>();

        System.out.println("Loading DNS records from: " + filename);

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {

            String line;
            int    lineNum  = 0;
            int    loaded   = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();

                // Skip blank lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Expect exactly one '=' separator
                int eqIdx = line.indexOf('=');
                if (eqIdx < 1) {
                    System.err.println("  [WARN] Skipping malformed line " + lineNum + ": " + line);
                    continue;
                }

                String domain = line.substring(0, eqIdx).trim().toLowerCase();
                String ip     = line.substring(eqIdx + 1).trim();

                if (domain.isEmpty() || ip.isEmpty()) {
                    System.err.println("  [WARN] Skipping malformed line " + lineNum + ": " + line);
                    continue;
                }

                records.put(domain, ip);
                System.out.printf("  Loaded: %-25s → %s%n", domain, ip);
                loaded++;
            }

            System.out.println("Total records loaded: " + loaded);
            System.out.println();
            return records;

        } catch (IOException e) {
            System.err.println("[ERROR] Failed to read " + filename + ": " + e.getMessage());
            return null;
        }
    }

    /** Print the startup banner. */
    private static void printBanner() {
        System.out.println("═".repeat(44));
        System.out.println("          CUSTOM DNS SERVER              ");
        System.out.println("═".repeat(44));
        System.out.println();
    }
}
