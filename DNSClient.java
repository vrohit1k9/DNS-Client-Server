import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;

/**
 * DNSClient.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Interactive command-line DNS client.
 *
 * Workflow (per query)
 * ─────────────────────
 *  1. Prompt the user for a domain name.
 *  2. Validate the input (empty / blank check).
 *  3. Build a custom DNS request:   DNS|REQUEST|<domain>
 *  4. Send it as a UDP datagram to the DNS server (127.0.0.1:5353).
 *  5. Wait for the response (with a configurable timeout).
 *  6. Parse the response and display a formatted result.
 *
 * Custom Protocol
 * ───────────────
 *  Request  :  DNS|REQUEST|<domain>
 *  Response :  DNS|RESPONSE|<domain>|<ip>       → RESOLVED
 *  Error    :  DNS|ERROR|<domain>|<reason>       → NOT FOUND / other error
 *
 * Error scenarios handled
 * ────────────────────────
 *  • Empty / blank domain input
 *  • Domain not found on server
 *  • Server unreachable (SocketException)
 *  • Request timeout (SocketTimeoutException)
 *  • Invalid / unexpected response format
 *
 * Usage
 * ─────
 *   javac *.java
 *   java DNSClient
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DNSClient {

    // ── Configuration ────────────────────────────────────────────────────────
    private static final String SERVER_IP      = "127.0.0.1";
    private static final int    SERVER_PORT    = 5454;   // must match DNSServer.PORT
    private static final int    BUFFER_SIZE    = 1024;   // bytes
    private static final int    TIMEOUT_MS     = 5000;   // 5-second UDP timeout

    // ── Main ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {

        printBanner();

        Scanner scanner = new Scanner(System.in);

        System.out.println("Type 'exit' or 'quit' to stop the client.");
        System.out.println("Type 'help' to see sample domain names.\n");

        // ── Interactive query loop ────────────────────────────────────────────
        while (true) {

            System.out.print("Enter domain name: ");
            String input = scanner.nextLine();

            if (input == null) break;
            input = input.trim();

            // ── Exit commands ─────────────────────────────────────────────────
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("\nGoodbye! DNS Client stopped.");
                break;
            }

            // ── Help ──────────────────────────────────────────────────────────
            if (input.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            }

            // ── Validate input ────────────────────────────────────────────────
            if (input.isEmpty()) {
                System.out.println("[ERROR] Domain name cannot be empty. Please try again.\n");
                continue;
            }

            // ── Send DNS query ────────────────────────────────────────────────
            sendQuery(input);

            System.out.println(); // blank line between queries
        }

        scanner.close();
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Build, send the DNS request and display the server response.
     *
     * @param domain  domain name entered by the user
     */
    private static void sendQuery(String domain) {

        // ── Build the custom DNS request string ───────────────────────────────
        String request = "DNS|REQUEST|" + domain;

        System.out.println();
        System.out.println("Sending query to DNS Server...");
        System.out.println("Server : " + SERVER_IP);
        System.out.println("Port   : " + SERVER_PORT);
        System.out.println();

        try (DatagramSocket socket = new DatagramSocket()) {

            // Set timeout so we don't block forever if server is down
            socket.setSoTimeout(TIMEOUT_MS);

            InetAddress serverAddress = InetAddress.getByName(SERVER_IP);

            // ── 1. Send request & measure response time ─────────────────────
            byte[]         sendBuffer = request.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(
                    sendBuffer,
                    sendBuffer.length,
                    serverAddress,
                    SERVER_PORT);

            long startTime = System.currentTimeMillis();
            socket.send(sendPacket);

            // ── 2. Receive response ───────────────────────────────────────────
            byte[]         recvBuffer  = new byte[BUFFER_SIZE];
            DatagramPacket recvPacket  = new DatagramPacket(recvBuffer, recvBuffer.length);

            socket.receive(recvPacket);   // blocks until data arrives or timeout
            long elapsedMs = System.currentTimeMillis() - startTime;

            String response = new String(
                    recvPacket.getData(), 0, recvPacket.getLength()).trim();

            // ── 3. Parse and display ──────────────────────────────────────────
            parseAndDisplay(domain, response, elapsedMs);

        } catch (java.net.SocketTimeoutException e) {
            System.out.println("┌─────────────────────────────────────────┐");
            System.out.printf( "│  Domain : %-30s│%n", domain);
            System.out.println("│  Status : REQUEST TIMEOUT               │");
            System.out.println("│  Hint   : Is the DNS server running?    │");
            System.out.println("└─────────────────────────────────────────┘");

        } catch (java.net.ConnectException e) {
            System.out.println("[ERROR] Cannot connect to DNS Server at "
                    + SERVER_IP + ":" + SERVER_PORT);

        } catch (Exception e) {
            System.out.println("[ERROR] Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Parse the pipe-delimited response from DNSHandler and display it nicely.
     *
     * Response formats:
     *   DNS|RESPONSE|<domain>|<ip>|<source>|<ttl>   → resolved
     *   DNS|ERROR|<domain>|DOMAIN_NOT_FOUND         → not found
     *   DNS|ERROR|<domain>|<other-reason>           → some other error
     *
     * @param originalDomain  domain the user typed (for display if parse fails)
     * @param response        raw response string from server
     * @param elapsedMs       round-trip time in milliseconds
     */
    private static void parseAndDisplay(String originalDomain, String response, long elapsedMs) {

        String[] parts = response.split("\\|");

        System.out.println("================================");

        if (parts.length < 3 || !parts[0].equals("DNS")) {
            // Unexpected / garbled response
            System.out.printf("  Domain : %s%n", originalDomain);
            System.out.println("  Status : INVALID SERVER RESPONSE");
            System.out.println("  Raw    : " + response);
            System.out.println("================================");
            return;
        }

        String msgType = parts[1];   // RESPONSE or ERROR
        String domain  = parts[2];   // echoed domain

        switch (msgType) {

            case "RESPONSE":
                // DNS|RESPONSE|<domain>|<ip>|<source>|<ttl>
                if (parts.length >= 4) {
                    String ip     = parts[3];
                    String source = (parts.length >= 5) ? parts[4] : "DATABASE";
                    String ttl    = (parts.length >= 6) ? parts[5] + "s" : "60s";

                    String sourceDesc;
                    String statusDesc = "RESOLVED [OK]";
                    if ("CACHE".equalsIgnoreCase(source)) {
                        sourceDesc = "[CACHE HIT] (from memory)";
                    } else if ("MONGODB".equalsIgnoreCase(source)) {
                        sourceDesc = "[SEARCH HISTORY HIT] (from MongoDB, Count: " + parts[5] + ")";
                        statusDesc = "SEARCH HISTORY HIT [OK]";
                    } else if ("DNS_LOOKUP".equalsIgnoreCase(source) || "NEW_SEARCH".equalsIgnoreCase(source)) {
                        sourceDesc = "[NEW SEARCH] (DNS Lookup -> Saved to MongoDB)";
                        statusDesc = "NEW SEARCH [OK]";
                    } else if ("REAL_DNS".equalsIgnoreCase(source)) {
                        sourceDesc = "[REAL DNS LOOKUP] (cached)";
                    } else {
                        sourceDesc = "[DATABASE LOOKUP] (cached)";
                    }

                    System.out.printf("  Domain       : %s%n", domain);
                    System.out.printf("  IP Address   : %s%n", ip);
                    System.out.printf("  Resolution   : %s%n", sourceDesc);
                    System.out.printf("  TTL / Info   : %s%n", ttl);
                    System.out.printf("  Response Time: %d ms%n", elapsedMs);
                    System.out.printf("  Status       : %s%n", statusDesc);
                } else {
                    System.out.printf("  Domain : %s%n", domain);
                    System.out.println("  Status : MALFORMED RESPONSE (missing IP)");
                }
                break;

            case "ERROR":
                // DNS|ERROR|<domain>|<reason>
                String reason = (parts.length >= 4) ? parts[3] : "UNKNOWN_ERROR";
                System.out.printf("  Domain : %s%n", domain);

                switch (reason) {
                    case "DOMAIN_NOT_FOUND":
                        System.out.println("  Status : DOMAIN NOT FOUND [FAILED]");
                        break;
                    case "INVALID_REQUEST_FORMAT":
                        System.out.println("  Status : INVALID REQUEST FORMAT [FAILED]");
                        break;
                    case "INVALID_DOMAIN_NAME":
                        System.out.println("  Status : INVALID DOMAIN NAME [FAILED]");
                        break;
                    default:
                        System.out.println("  Status : ERROR – " + reason);
                }
                break;

            default:
                System.out.printf("  Domain : %s%n", originalDomain);
                System.out.println("  Status : UNKNOWN RESPONSE TYPE: " + msgType);
        }

        System.out.println("================================");
    }

    /** Print the client startup banner. */
    private static void printBanner() {
        System.out.println("================================");
        System.out.println("       CUSTOM DNS CLIENT        ");
        System.out.println("================================");
        System.out.println();
    }

    /** Print sample domains from dns_records.txt. */
    private static void printHelp() {
        System.out.println("\nSample domains you can try:");
        System.out.println("  google.com");
        System.out.println("  example.com");
        System.out.println("  localhost");
        System.out.println("  server.local");
        System.out.println("  openai.com");
        System.out.println("  github.com");
        System.out.println("  stackoverflow.com");
        System.out.println("  abc.com       ← not found (test error handling)");
        System.out.println();
    }
}
