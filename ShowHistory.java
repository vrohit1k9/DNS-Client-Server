import java.util.List;

/**
 * ShowHistory.java
 * Prints search history (with search counts, date, and time) and DNS query log.
 * Usage: java --enable-native-access=ALL-UNNAMED -cp ".;lib/*" ShowHistory
 */
public class ShowHistory {
    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb.driver", "off");
        java.util.logging.Logger.getLogger("org.mongodb.driver").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("com.mongodb").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
    }

    public static void main(String[] args) {
        MongoHistoryService mongo = MongoHistoryService.getInstance();

        // ── 1. Search History ─────────────────────────────────────────────────
        System.out.println("\n╔═══════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         SEARCH HISTORY (Counts & Timestamps)                              ║");
        System.out.println("╠════════════════════════════════╦══════════════════╦══════════════╦════════════════════════╣");
        System.out.printf( "║ %-30s ║ %-16s ║ %-10s ║ %-30s ║%n", "Website / Domain", "IP Address", "Searches", "Recent Time Used");
        System.out.println("╠════════════════════════════════╬══════════════════╬════════════╬════════════════════════════════╣");

        List<SearchRecord> history = mongo.getAllHistory();
        if (history.isEmpty()) {
            System.out.println("║  No records found.                                                                              ║");
        } else {
            for (SearchRecord r : history) {
                String recent = MongoHistoryService.formatTimeAgo(r.getLastSearched());
                System.out.printf("║ %-30s ║ %-16s ║ %-10d ║ %-30s ║%n",
                    truncate(r.getDomain(), 30),
                    truncate(r.getIp(), 16),
                    r.getSearchCount(),
                    truncate(recent, 30));
            }
        }
        System.out.println("╚════════════════════════════════╩══════════════════╩════════════╩════════════════════════════════╝");
        System.out.println("  Total unique domains: " + history.size());

        // ── 2. DNS Query Log ──────────────────────────────────────────────────
        System.out.println("\n╔═══════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         DNS QUERY LOG (Date & Time of Queries)                            ║");
        System.out.println("╠════════════════════════════════╦══════════════════╦══════════════╦════════════════════════╣");
        System.out.printf( "║ %-30s ║ %-16s ║ %-12s ║ %-22s ║%n", "Website / Domain", "IP Address", "Date", "Time");
        System.out.println("╠════════════════════════════════╬══════════════════╬══════════════╬════════════════════════╣");

        List<DnsQueryRecord> queries = mongo.getRecentDnsQueries(50);
        if (queries.isEmpty()) {
            System.out.println("║  No query records found.                                                                  ║");
        } else {
            for (DnsQueryRecord r : queries) {
                System.out.printf("║ %-30s ║ %-16s ║ %-12s ║ %-22s ║%n",
                    truncate(r.getDomain(), 30),
                    truncate(r.getIp(), 16),
                    r.getDateDisplay(),
                    r.getTimeDisplay());
            }
        }
        System.out.println("╚════════════════════════════════╩══════════════════╩══════════════╩════════════════════════╝");
        System.out.println("  Total query records: " + queries.size());
        System.out.println();

        mongo.close();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "--";
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
