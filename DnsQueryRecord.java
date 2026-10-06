import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * DnsQueryRecord.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Model class representing ONE individual DNS query log entry.
 * Stored in MongoDB collection: dns_queries
 *
 * Document structure:
 * {
 *   "domain"    : "github.com",
 *   "ip"        : "140.82.121.4",
 *   "timestamp" : ISODate("2026-10-05T22:45:12.000Z"),   // real MongoDB Date
 *   "source"    : "DNS_CLIENT"
 * }
 *
 * NOTE: Unlike search_history (which aggregates & counts),
 *       dns_queries stores every single query as its own document.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DnsQueryRecord {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("hh:mm a").withZone(ZoneId.systemDefault());

    private final String  domain;
    private final String  ip;
    private final Instant timestamp;
    private final String  source;

    public DnsQueryRecord(String domain, String ip, Instant timestamp, String source) {
        this.domain    = domain;
        this.ip        = ip;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.source    = source != null ? source : "DNS_CLIENT";
    }

    public String  getDomain()    { return domain; }
    public String  getIp()        { return ip; }
    public Instant getTimestamp() { return timestamp; }
    public String  getSource()    { return source; }

    /** Returns date formatted as  dd/MM/yyyy  e.g. 05/10/2026 */
    public String getDateDisplay() {
        return DATE_FMT.format(timestamp);
    }

    /** Returns time formatted as  hh:mm AM/PM  e.g. 10:45 PM */
    public String getTimeDisplay() {
        return TIME_FMT.format(timestamp);
    }

    @Override
    public String toString() {
        return "DnsQueryRecord{domain='" + domain + "', ip='" + ip +
               "', timestamp=" + timestamp + ", source='" + source + "'}";
    }
}
