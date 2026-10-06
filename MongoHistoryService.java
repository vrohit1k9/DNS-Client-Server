import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MongoHistoryService.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Manages persistent DNS search history in MongoDB with automatic local CSV
 * fallback persistence.
 *
 * Guarantees:
 *  - Atomic search count increment & lastSearched date/time on hit
 *  - Persistent storage in local CSV files (dns_search_history.csv, dns_queries.csv)
 *  - Seamless operation offline or online with MongoDB
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MongoHistoryService {

    private static final Logger LOGGER = Logger.getLogger(MongoHistoryService.class.getName());

    private static volatile MongoHistoryService instance;

    private static final String CSV_HISTORY_FILE = "dns_search_history.csv";
    private static final String CSV_QUERIES_FILE = "dns_queries.csv";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String uri;
    private final String dbName;
    private final String collectionName;

    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> collection;       // search_history
    private MongoCollection<Document> queryCollection;  // dns_queries
    private boolean connected = false;

    private final Map<String, SearchRecord> localHistory = new ConcurrentHashMap<>();
    private final List<DnsQueryRecord> localQueries = new CopyOnWriteArrayList<>();

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb", "off");
        System.setProperty("org.slf4j.simpleLogger.log.org.mongodb.driver", "off");
        Logger.getLogger("org.mongodb.driver").setLevel(Level.OFF);
        Logger.getLogger("com.mongodb").setLevel(Level.OFF);
    }

    private static String getEnvOrDotEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        File envFile = new File(".env");
        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        if (k.equalsIgnoreCase(key)) {
                            String v = line.substring(eq + 1).trim();
                            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                                v = v.substring(1, v.length() - 1);
                            }
                            return v;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    private MongoHistoryService() {
        this.uri = getEnvOrDotEnv("MONGODB_URI", "mongodb://localhost:27017");
        this.dbName = getEnvOrDotEnv("MONGODB_DATABASE", "custom_dns");
        this.collectionName = getEnvOrDotEnv("MONGODB_COLLECTION", "search_history");

        // Load local file persistence first
        loadLocalHistoryCsv();
        loadLocalQueriesCsv();

        connect();
    }

    public static MongoHistoryService getInstance() {
        if (instance == null) {
            synchronized (MongoHistoryService.class) {
                if (instance == null) {
                    instance = new MongoHistoryService();
                }
            }
        }
        return instance;
    }

    private synchronized void connect() {
        try {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(1500, TimeUnit.MILLISECONDS))
                    .build();

            this.mongoClient = MongoClients.create(settings);
            this.database = mongoClient.getDatabase(dbName);
            this.collection = database.getCollection(collectionName);
            this.queryCollection = database.getCollection("dns_queries");

            database.runCommand(new Document("ping", 1));
            collection.createIndex(Indexes.ascending("domain"), new IndexOptions().unique(true));
            queryCollection.createIndex(Indexes.descending("timestamp"));

            this.connected = true;
            System.out.println("[MongoDB] Successfully connected to " + dbName + "." + collectionName);

            // Sync from Mongo if available
            for (Document doc : collection.find()) {
                SearchRecord r = docToRecord(doc);
                localHistory.put(r.getDomain().toLowerCase(), r);
            }
            saveLocalHistoryCsv();

        } catch (Exception e) {
            this.connected = false;
            System.out.println("[MongoDB] Running in fallback mode (MongoDB offline). Local persistence active.");
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDatabaseName() {
        return dbName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    /**
     * Looks up existing record without modifying search count or timestamp.
     * Useful for seeing the previous / recent time the user used the website.
     */
    public SearchRecord getRecord(String domain) {
        if (domain == null) return null;
        String lowerDomain = domain.trim().toLowerCase();
        if (connected && collection != null) {
            try {
                Document doc = collection.find(Filters.eq("domain", lowerDomain)).first();
                if (doc != null) return docToRecord(doc);
            } catch (Exception ignored) {}
        }
        return localHistory.get(lowerDomain);
    }

    /**
     * Formats a timestamp into human-readable relative format:
     * e.g. "2026-10-06 19:44:05 (13m ago)" or "Never used before".
     */
    public static String formatTimeAgo(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty() || timestamp.equals("--") || timestamp.startsWith("1601")) {
            return "Never used before";
        }
        try {
            String clean = timestamp.length() > 19 ? timestamp.substring(0, 19).replace("T", " ") : timestamp;
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime past = LocalDateTime.parse(clean, dtf);
            LocalDateTime now = LocalDateTime.now();
            Duration diff = Duration.between(past, now);
            long seconds = diff.getSeconds();
            if (seconds < 0) seconds = 0;

            String ago;
            if (seconds < 60) {
                ago = seconds + "s ago";
            } else if (seconds < 3600) {
                ago = (seconds / 60) + "m ago";
            } else if (seconds < 86400) {
                ago = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m ago";
            } else {
                long days = seconds / 86400;
                ago = days + "d ago";
            }
            return clean + " (" + ago + ")";
        } catch (Exception e) {
            return timestamp;
        }
    }

    /**
     * Checks search history for the domain, increments search count,
     * updates lastSearched timestamp, and returns the updated record.
     */
    public SearchRecord findAndRecordHit(String domain) {
        if (domain == null) return null;
        String lowerDomain = domain.trim().toLowerCase();
        String now = TS_FMT.format(Instant.now());

        SearchRecord hit = null;

        if (connected && collection != null) {
            try {
                Bson filter = Filters.eq("domain", lowerDomain);
                Bson update = Updates.combine(
                        Updates.inc("searchCount", 1),
                        Updates.set("lastSearched", now)
                );
                FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER);
                Document doc = collection.findOneAndUpdate(filter, update, options);
                if (doc != null) {
                    hit = docToRecord(doc);
                }
            } catch (Exception ignored) {}
        }

        // Local cache / persistence update
        SearchRecord local = localHistory.get(lowerDomain);
        if (local != null) {
            int newCount = (hit != null) ? hit.getSearchCount() : (local.getSearchCount() + 1);
            SearchRecord updated = new SearchRecord(
                    local.getDomain(), local.getIp(), newCount, local.getFirstSearched(), now, local.getSource());
            localHistory.put(lowerDomain, updated);
            saveLocalHistoryCsv();
            if (hit == null) hit = updated;
        } else if (hit != null) {
            localHistory.put(lowerDomain, hit);
            saveLocalHistoryCsv();
        }

        return hit;
    }

    /**
     * Stores a newly resolved domain into search history.
     */
    public void saveNewSearch(String domain, String ip, String source) {
        if (domain == null || ip == null) return;
        String lowerDomain = domain.trim().toLowerCase();
        String now = TS_FMT.format(Instant.now());

        SearchRecord existing = localHistory.get(lowerDomain);
        int count = (existing != null) ? existing.getSearchCount() + 1 : 1;
        String first = (existing != null) ? existing.getFirstSearched() : now;

        SearchRecord record = new SearchRecord(lowerDomain, ip.trim(), count, first, now, source != null ? source : "DNS_LOOKUP");
        localHistory.put(lowerDomain, record);
        saveLocalHistoryCsv();

        if (connected && collection != null) {
            try {
                Document doc = new Document("domain", lowerDomain)
                        .append("ip", ip.trim())
                        .append("searchCount", count)
                        .append("firstSearched", first)
                        .append("lastSearched", now)
                        .append("source", source != null ? source : "DNS_LOOKUP");

                collection.updateOne(
                        Filters.eq("domain", lowerDomain),
                        new Document("$set", doc),
                        new com.mongodb.client.model.UpdateOptions().upsert(true)
                );
            } catch (Exception ignored) {}
        }
    }

    /**
     * Stores or updates a domain imported from Google Chrome history.
     */
    public void saveChromeImport(String domain, String ip, int visitCount, String source, String lastVisited) {
        if (domain == null || ip == null) return;
        String lowerDomain = domain.trim().toLowerCase();
        String now = (lastVisited != null && !lastVisited.isEmpty()) ? lastVisited : TS_FMT.format(Instant.now());

        SearchRecord existing = localHistory.get(lowerDomain);
        int count = Math.max(visitCount, existing != null ? existing.getSearchCount() : 1);
        String first = existing != null ? existing.getFirstSearched() : now;

        SearchRecord record = new SearchRecord(lowerDomain, ip.trim(), count, first, now, source != null ? source : "CHROME_HISTORY");
        localHistory.put(lowerDomain, record);
        saveLocalHistoryCsv();

        if (connected && collection != null) {
            try {
                Document doc = new Document("domain", lowerDomain)
                        .append("ip", ip.trim())
                        .append("searchCount", count)
                        .append("firstSearched", first)
                        .append("lastSearched", now)
                        .append("source", source != null ? source : "CHROME_HISTORY");

                collection.updateOne(
                        Filters.eq("domain", lowerDomain),
                        new Document("$set", doc),
                        new com.mongodb.client.model.UpdateOptions().upsert(true)
                );
            } catch (Exception ignored) {}
        }
    }

    public void saveChromeImport(String domain, String ip, int visitCount, String source) {
        saveChromeImport(domain, ip, visitCount, source, null);
    }

    /**
     * Retrieves all search history records sorted by lastSearched descending.
     */
    public List<SearchRecord> getAllHistory() {
        if (connected && collection != null) {
            try {
                List<SearchRecord> list = new ArrayList<>();
                for (Document doc : collection.find().sort(Sorts.descending("lastSearched"))) {
                    list.add(docToRecord(doc));
                }
                if (!list.isEmpty()) return list;
            } catch (Exception ignored) {}
        }

        List<SearchRecord> list = new ArrayList<>(localHistory.values());
        list.sort((a, b) -> {
            String ta = a.getLastSearched() != null ? a.getLastSearched() : "";
            String tb = b.getLastSearched() != null ? b.getLastSearched() : "";
            return tb.compareTo(ta);
        });
        return list;
    }

    private SearchRecord docToRecord(Document doc) {
        String domain = doc.getString("domain");
        String ip = doc.getString("ip");
        Integer count = doc.getInteger("searchCount");
        int searchCount = (count != null) ? count : 1;
        String firstSearched = doc.getString("firstSearched");
        String lastSearched = doc.getString("lastSearched");
        String src = doc.getString("source");
        return new SearchRecord(domain, ip, searchCount, firstSearched, lastSearched, src);
    }

    /**
     * Saves a single DNS query event.
     */
    public void saveDnsQuery(String domain, String ip, String source) {
        if (domain == null || ip == null) return;
        DnsQueryRecord record = new DnsQueryRecord(domain.trim().toLowerCase(), ip.trim(), Instant.now(), source);
        localQueries.add(0, record);
        appendLocalQueryCsv(record);

        if (connected && queryCollection != null) {
            try {
                Document doc = new Document("domain", domain.trim().toLowerCase())
                        .append("ip",        ip.trim())
                        .append("timestamp", new Date())
                        .append("source",    source != null ? source : "DNS_CLIENT");
                queryCollection.insertOne(doc);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Returns recent DNS query records sorted by timestamp descending.
     */
    public List<DnsQueryRecord> getRecentDnsQueries(int limit) {
        if (connected && queryCollection != null) {
            try {
                List<DnsQueryRecord> list = new ArrayList<>();
                for (Document doc : queryCollection.find().sort(Sorts.descending("timestamp")).limit(limit)) {
                    String domain = doc.getString("domain");
                    String ip = doc.getString("ip");
                    String source = doc.getString("source");
                    Object tsObj = doc.get("timestamp");
                    Instant timestamp = Instant.now();
                    if (tsObj instanceof Date) {
                        timestamp = ((Date) tsObj).toInstant();
                    } else if (tsObj instanceof String) {
                        try { timestamp = Instant.parse((String) tsObj); } catch (Exception ignored) {}
                    }
                    list.add(new DnsQueryRecord(domain, ip, timestamp, source));
                }
                if (!list.isEmpty()) return list;
            } catch (Exception ignored) {}
        }

        int max = Math.min(limit, localQueries.size());
        return new ArrayList<>(localQueries.subList(0, max));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local File Persistence (CSV)
    // ─────────────────────────────────────────────────────────────────────────
    private synchronized void loadLocalHistoryCsv() {
        Path path = Paths.get(CSV_HISTORY_FILE);
        if (!Files.exists(path)) return;
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length >= 6) {
                    String d = p[0].trim();
                    String ip = p[1].trim();
                    int count = 1;
                    try { count = Integer.parseInt(p[2].trim()); } catch (Exception ignored) {}
                    String first = p[3].trim();
                    String last = p[4].trim();
                    String src = p[5].trim();
                    localHistory.put(d.toLowerCase(), new SearchRecord(d, ip, count, first, last, src));
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void saveLocalHistoryCsv() {
        Path path = Paths.get(CSV_HISTORY_FILE);
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write("domain,ip,searchCount,firstSearched,lastSearched,source\n");
            for (SearchRecord r : localHistory.values()) {
                bw.write(String.format("%s,%s,%d,%s,%s,%s\n",
                        r.getDomain(), r.getIp(), r.getSearchCount(),
                        r.getFirstSearched(), r.getLastSearched(), r.getSource()));
            }
        } catch (Exception ignored) {}
    }

    private synchronized void loadLocalQueriesCsv() {
        Path path = Paths.get(CSV_QUERIES_FILE);
        if (!Files.exists(path)) return;
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length >= 4) {
                    String ts = p[0].trim();
                    String d = p[1].trim();
                    String ip = p[2].trim();
                    String src = p[3].trim();
                    Instant inst;
                    try {
                        LocalDateTime ldt = LocalDateTime.parse(ts, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        inst = ldt.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception e) {
                        try { inst = Instant.parse(ts); } catch (Exception e2) { inst = Instant.now(); }
                    }
                    localQueries.add(0, new DnsQueryRecord(d, ip, inst, src));
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void appendLocalQueryCsv(DnsQueryRecord r) {
        Path path = Paths.get(CSV_QUERIES_FILE);
        boolean exists = Files.exists(path);
        try (BufferedWriter bw = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (!exists) {
                bw.write("timestamp,domain,ip,source\n");
            }
            String ts = TS_FMT.format(r.getTimestamp());
            bw.write(String.format("%s,%s,%s,%s\n", ts, r.getDomain(), r.getIp(), r.getSource()));
        } catch (Exception ignored) {}
    }

    public synchronized void close() {
        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception ignored) {}
            connected = false;
        }
    }
}
