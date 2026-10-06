import java.time.Instant;

/**
 * SearchRecord.java
 * Model class representing a domain search entry stored in MongoDB.
 */
public class SearchRecord {

    private final String domain;
    private final String ip;
    private final int searchCount;
    private final String firstSearched;
    private final String lastSearched;
    private final String source;

    public SearchRecord(String domain, String ip, int searchCount,
                        String firstSearched, String lastSearched, String source) {
        this.domain = domain;
        this.ip = ip;
        this.searchCount = searchCount;
        this.firstSearched = firstSearched;
        this.lastSearched = lastSearched;
        this.source = source != null ? source : "DNS_LOOKUP";
    }

    public String getDomain() {
        return domain;
    }

    public String getIp() {
        return ip;
    }

    public int getSearchCount() {
        return searchCount;
    }

    public String getFirstSearched() {
        return firstSearched;
    }

    public String getLastSearched() {
        return lastSearched;
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "SearchRecord{" +
                "domain='" + domain + '\'' +
                ", ip='" + ip + '\'' +
                ", searchCount=" + searchCount +
                ", firstSearched='" + firstSearched + '\'' +
                ", lastSearched='" + lastSearched + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}
