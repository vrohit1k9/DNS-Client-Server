import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.function.Consumer;

/**
 * ChromeHistoryImporter.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Imports and displays search history with:
 *  - Website / Domain
 *  - Number of times searched / visited
 *  - Exact Date and Time of search
 *  - Resolved IP Address
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class ChromeHistoryImporter {

    public static class DomainItem {
        public String domain;
        public int visits;
        public String lastTime;
        public String ip;

        public DomainItem(String domain, int visits, String lastTime, String ip) {
            this.domain = domain;
            this.visits = visits;
            this.lastTime = lastTime != null ? lastTime : "--";
            this.ip = ip != null ? ip : "--";
        }
    }

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb.driver", "off");
        java.util.logging.Logger.getLogger("org.mongodb.driver").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("com.mongodb").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
    }

    public static void main(String[] args) {
        printCleanWebsitesList();
    }

    /**
     * Prints a clean, uncluttered table of all searched websites with visit count,
     * last searched date and time, and resolved IP address.
     */
    public static void printCleanWebsitesList() {
        Map<String, DomainItem> combined = getAggregatedHistory();

        if (combined.isEmpty()) {
            System.out.println("No search history or visited websites found.");
            return;
        }

        // Sort by last searched date & time descending (most recent first)
        List<DomainItem> list = new ArrayList<>(combined.values());
        list.sort((a, b) -> b.lastTime.compareTo(a.lastTime));

        System.out.println();
        System.out.println("===================================================================================================================");
        System.out.println("                                      SEARCHED WEBSITES & QUERY HISTORY                                            ");
        System.out.println("===================================================================================================================");
        System.out.printf(" %-4s  %-30s  %-16s  %-32s  %-16s%n",
                "#", "Website / Domain", "Times Searched", "Recent Time Used", "Resolved IP");
        System.out.println("-------------------------------------------------------------------------------------------------------------------");

        int idx = 1;
        for (DomainItem item : list) {
            String recentTime = MongoHistoryService.formatTimeAgo(item.lastTime);
            System.out.printf(" %-4d  %-30s  %-16d  %-32s  %-16s%n",
                    idx++, item.domain, item.visits, recentTime, item.ip);
        }

        System.out.println("===================================================================================================================");
        System.out.println(" Total: " + list.size() + " unique websites found.\n");
    }

    /**
     * Combines Chrome history with Custom DNS queries.
     */
    public static Map<String, DomainItem> getAggregatedHistory() {
        Map<String, DomainItem> items = new LinkedHashMap<>();
        Map<String, String> localRecords = loadLocalRecords("dns_records.txt");

        // 1. Pull from Chrome SQLite history
        Path historyFile = locateHistoryFile();
        if (historyFile != null && Files.exists(historyFile)) {
            Path tempFile = null;
            try {
                tempFile = Files.createTempFile("chrome_history_view_", ".db");
                Files.copy(historyFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
                Class.forName("org.sqlite.JDBC");

                String jdbcUrl = "jdbc:sqlite:" + tempFile.toAbsolutePath().toString().replace("\\", "/");
                try (Connection conn = DriverManager.getConnection(jdbcUrl);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT url, visit_count, datetime((last_visit_time/1000000)-11644473600, 'unixepoch', 'localtime') AS last_time " +
                             "FROM urls WHERE url LIKE 'http%' ORDER BY last_visit_time DESC LIMIT 300")) {

                    while (rs.next()) {
                        String urlStr = rs.getString("url");
                        int visits = rs.getInt("visit_count");
                        String lastTime = rs.getString("last_time");
                        if (lastTime == null || lastTime.isEmpty()) lastTime = "--";

                        String domain = extractDomain(urlStr);
                        if (domain != null && isValidDomain(domain)) {
                            DomainItem existing = items.get(domain);
                            if (existing == null) {
                                items.put(domain, new DomainItem(domain, Math.max(1, visits), lastTime, null));
                            } else {
                                existing.visits += Math.max(1, visits);
                                if (existing.lastTime.compareTo(lastTime) < 0) {
                                    existing.lastTime = lastTime;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                }
            }
        }

        // 2. Merge with Custom DNS search records (including searches made in DNSGui / DNSClient)
        try {
            MongoHistoryService mongo = MongoHistoryService.getInstance();
            List<SearchRecord> dnsHistory = mongo.getAllHistory();
            for (SearchRecord sr : dnsHistory) {
                String d = sr.getDomain().toLowerCase();
                String last = sr.getLastSearched();
                if (last != null && last.length() > 19) last = last.substring(0, 19).replace("T", " ");

                DomainItem existing = items.get(d);
                if (existing == null) {
                    items.put(d, new DomainItem(d, sr.getSearchCount(), last, sr.getIp()));
                } else {
                    existing.visits = Math.max(existing.visits, sr.getSearchCount());
                    if (last != null && !last.equals("--") && existing.lastTime.compareTo(last) < 0) {
                        existing.lastTime = last;
                    }
                    if (existing.ip == null || existing.ip.equals("--")) {
                        existing.ip = sr.getIp();
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. Resolve missing IPs
        for (DomainItem item : items.values()) {
            if (item.ip == null || item.ip.equals("--") || item.ip.equals("Unresolved")) {
                String ip = localRecords != null ? localRecords.get(item.domain) : null;
                if (ip == null) {
                    try {
                        InetAddress addr = InetAddress.getByName(item.domain);
                        ip = addr.getHostAddress();
                    } catch (Exception e) {
                        ip = "Unresolved";
                    }
                }
                item.ip = ip;
            }
        }

        return items;
    }

    /**
     * Imports browsing history into MongoDB and local persistence.
     */
    public static int importHistory(Consumer<String> logCallback) {
        if (logCallback == null) logCallback = System.out::println;

        Map<String, DomainItem> items = getAggregatedHistory();
        if (items.isEmpty()) {
            logCallback.accept("[WARN] No Chrome history found.");
            return 0;
        }

        MongoHistoryService mongo = MongoHistoryService.getInstance();
        int imported = 0;

        for (DomainItem item : items.values()) {
            mongo.saveChromeImport(item.domain, item.ip, item.visits, "CHROME_HISTORY", item.lastTime);
            imported++;
            logCallback.accept("  + " + item.domain + " -> " + item.ip + " (" + item.visits + " searches | " + item.lastTime + ")");
        }

        logCallback.accept(">>> Synchronized " + imported + " domains with search counts and date/time.");
        return imported;
    }

    private static Path locateHistoryFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.trim().isEmpty()) {
            localAppData = System.getProperty("user.home") + "\\AppData\\Local";
        }

        String chromeProfile = System.getenv("CHROME_PROFILE");
        if (chromeProfile != null && !chromeProfile.trim().isEmpty()) {
            Path specified = Paths.get(localAppData, "Google", "Chrome", "User Data", chromeProfile.trim(), "History");
            if (Files.exists(specified)) return specified;
        }

        Path chromeDefault = Paths.get(localAppData, "Google", "Chrome", "User Data", "Default", "History");
        if (Files.exists(chromeDefault)) return chromeDefault;

        Path chromeP1 = Paths.get(localAppData, "Google", "Chrome", "User Data", "Profile 1", "History");
        if (Files.exists(chromeP1)) return chromeP1;

        Path edgeDefault = Paths.get(localAppData, "Microsoft", "Edge", "User Data", "Default", "History");
        if (Files.exists(edgeDefault)) return edgeDefault;

        return null;
    }

    private static String extractDomain(String urlStr) {
        try {
            URI uri = new URI(urlStr);
            String host = uri.getHost();
            if (host != null) return host.toLowerCase().trim();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isValidDomain(String domain) {
        if (domain == null || domain.isEmpty() || !domain.contains(".")) return false;
        if (domain.equals("localhost") || domain.endsWith(".internal")) return false;
        return true;
    }

    private static Map<String, String> loadLocalRecords(String file) {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                m.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
