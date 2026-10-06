import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * DNSWebServer.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Lightweight local HTTP API and Web server (using standard Java HttpServer).
 * Exposes real-time API endpoints for the HTML/CSS/JS frontend dashboard and
 * connects directly to MongoHistoryService and DNSCache.
 *
 * Endpoints:
 *  - GET /                 -> Serves index.html
 *  - GET /style.css        -> Serves style.css
 *  - GET /app.js           -> Serves app.js
 *  - GET /api/status       -> MongoDB and Server status JSON
 *  - GET /api/history      -> Live search history from MongoDB Atlas
 *  - GET /api/query?domain= -> Live DNS resolution via cache and MongoDB
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DNSWebServer {

    private static final int HTTP_PORT = 8080;
    private static DNSCache cache;
    private static MongoHistoryService mongo;

    public static void main(String[] args) throws IOException {
        cache = new DNSCache();
        mongo = MongoHistoryService.getInstance();

        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        // API routes
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/api/query", new QueryHandler());

        // Static file routes
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(null); // default executor
        server.start();

        System.out.println("====================================================");
        System.out.println("   CUSTOM DNS WEB SERVER & API RUNNING               ");
        System.out.println("====================================================");
        System.out.println("Web Dashboard URL: http://localhost:" + HTTP_PORT);
        System.out.println("MongoDB Status   : " + (mongo.isConnected() ? "ONLINE (Atlas)" : "OFFLINE / FALLBACK"));
        System.out.println("====================================================");
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    // ── Static File Handler ───────────────────────────────────────────────────
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            File file = new File("." + path);
            if (!file.exists() || file.isDirectory()) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(notFound); }
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html; charset=UTF-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
            else if (path.endsWith(".json")) contentType = "application/json";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // ── /api/status ──────────────────────────────────────────────────────────
    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String json = String.format(
                "{\"server\":\"ONLINE\",\"mongo\":\"%s\",\"database\":\"%s\",\"collection\":\"%s\"}",
                mongo.isConnected() ? "ONLINE" : "OFFLINE",
                mongo.getDatabaseName(),
                mongo.getCollectionName()
            );
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // ── /api/history ─────────────────────────────────────────────────────────
    static class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            List<SearchRecord> records = mongo.getAllHistory();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < records.size(); i++) {
                SearchRecord r = records.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format(
                    "{\"domain\":\"%s\",\"ip\":\"%s\",\"count\":%d,\"last\":\"%s\",\"source\":\"%s\"}",
                    r.getDomain(), r.getIp(), r.getSearchCount(), r.getLastSearched(), r.getSource()
                ));
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // ── /api/query?domain=... ────────────────────────────────────────────────
    static class QueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String query = exchange.getRequestURI().getQuery();
            String domain = "";
            if (query != null && query.startsWith("domain=")) {
                domain = query.substring(7).trim().toLowerCase();
            }

            if (domain.isEmpty()) {
                byte[] err = "{\"error\":\"Missing domain parameter\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            // 1. Check Cache
            String tier = "UPSTREAM";
            String ip = cache.get(domain);
            if (ip != null) {
                tier = "CACHE_HIT";
            } else {
                // 2. Check MongoDB
                SearchRecord hit = mongo.findAndRecordHit(domain);
                if (hit != null && hit.getIp() != null) {
                    ip = hit.getIp();
                    tier = "MONGODB_HIT";
                    cache.put(domain, ip);
                } else {
                    // 3. Resolve live
                    try {
                        InetAddress addr = InetAddress.getByName(domain);
                        ip = addr.getHostAddress();
                        tier = "UPSTREAM_RESOLVED";
                        cache.put(domain, ip);
                        mongo.saveNewSearch(domain, ip, "WEB_QUERY");
                    } catch (Exception e) {
                        ip = "NXDOMAIN";
                        tier = "FAILED";
                    }
                }
            }

            String json = String.format("{\"domain\":\"%s\",\"ip\":\"%s\",\"tier\":\"%s\",\"ttl\":60}", domain, ip, tier);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
}
