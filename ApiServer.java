import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API for the chat server, built on the JDK's own com.sun.net.httpserver
 * (no frameworks, no extra libraries). Runs next to the socket server and shares
 * the same thread-safe ClientRegistry.
 *
 *   GET    /api/status          server info
 *   GET    /api/users           list online users
 *   DELETE /api/users/{name}    remove (kick) a user
 *   GET    /api/messages        recent messages  (?limit=1..100, default 20)
 *   POST   /api/messages        send a message to everyone: {"from":"Bot","message":"Hi"}
 */
public class ApiServer {

    private static final int MAX_BODY_BYTES = 4096;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_SENDER_LENGTH = 20;

    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ClientRegistry registry;
    private final long startTime = System.currentTimeMillis();

    public ApiServer(int port, ClientRegistry registry) throws IOException {
        this.registry = registry;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/users", this::handleUsers);
        server.createContext("/api/messages", this::handleMessages);
        server.createContext("/", ex -> sendError(ex, 404, "Unknown endpoint. Try /api/status"));
        server.setExecutor(pool);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        pool.shutdown();
    }

    // ---------- GET /api/status ----------
    private void handleStatus(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        if (!exactPath(ex, "/api/status")) return;
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        send(ex, 200, "{\"status\":\"running\",\"onlineUsers\":" + registry.size()
                + ",\"maxClients\":" + registry.getMaxClients()
                + ",\"uptimeSeconds\":" + uptime + "}");
    }

    // ---------- /api/users ----------
    private void handleUsers(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/users") || path.equals("/api/users/")) {
            if (!requireMethod(ex, "GET")) return;
            List<String> users = registry.userNames();
            StringBuilder sb = new StringBuilder("{\"count\":" + users.size() + ",\"users\":[");
            for (int i = 0; i < users.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(JsonUtil.quote(users.get(i)));
            }
            send(ex, 200, sb.append("]}").toString());
        } else if (path.startsWith("/api/users/")) {
            if (!requireMethod(ex, "DELETE")) return;
            String name = URLDecoder.decode(path.substring("/api/users/".length()), StandardCharsets.UTF_8);
            if (name.isEmpty() || name.contains("/")) {
                sendError(ex, 400, "Invalid username.");
            } else if (registry.kick(name)) {
                send(ex, 200, "{\"message\":" + JsonUtil.quote("User '" + name + "' was removed.") + "}");
            } else {
                sendError(ex, 404, "User '" + name + "' is not online.");
            }
        } else {
            sendError(ex, 404, "Unknown endpoint.");
        }
    }

    // ---------- /api/messages ----------
    private void handleMessages(HttpExchange ex) throws IOException {
        if (!exactPath(ex, "/api/messages")) return;
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            getMessages(ex);
        } else if (method.equals("POST")) {
            postMessage(ex);
        } else {
            ex.getResponseHeaders().set("Allow", "GET, POST");
            sendError(ex, 405, "Method not allowed. Use GET or POST.");
        }
    }

    private void getMessages(HttpExchange ex) throws IOException {
        int limit = 20;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("limit=")) {
                    try {
                        limit = Integer.parseInt(pair.substring(6));
                    } catch (NumberFormatException e) {
                        sendError(ex, 400, "limit must be a number between 1 and 100.");
                        return;
                    }
                    if (limit < 1 || limit > 100) {
                        sendError(ex, 400, "limit must be a number between 1 and 100.");
                        return;
                    }
                }
            }
        }
        List<ClientRegistry.ChatMessage> messages = registry.recentMessages(limit);
        StringBuilder sb = new StringBuilder("{\"count\":" + messages.size() + ",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            ClientRegistry.ChatMessage m = messages.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"time\":").append(JsonUtil.quote(m.time.toString()))
              .append(",\"from\":").append(JsonUtil.quote(m.sender))
              .append(",\"message\":").append(JsonUtil.quote(m.text)).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void postMessage(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body == null) {
            sendError(ex, 413, "Request body too large (max " + MAX_BODY_BYTES + " bytes).");
            return;
        }
        Map<String, String> json;
        try {
            json = JsonUtil.parseFlatObject(body);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, "Invalid JSON: " + e.getMessage());
            return;
        }
        String text = json.getOrDefault("message", "").trim();
        String from = json.getOrDefault("from", "API").trim();

        if (text.isEmpty()) {
            sendError(ex, 400, "Field 'message' is required.");
        } else if (text.length() > MAX_MESSAGE_LENGTH) {
            sendError(ex, 400, "Field 'message' is too long (max " + MAX_MESSAGE_LENGTH + " characters).");
        } else if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            sendError(ex, 400, "Field 'message' must be a single line.");
        } else if (from.isEmpty() || from.contains(" ") || from.length() > MAX_SENDER_LENGTH
                || !from.matches("[A-Za-z0-9_-]+")) {
            sendError(ex, 400, "Field 'from' must be 1-" + MAX_SENDER_LENGTH
                    + " letters, digits, '_' or '-' (no spaces).");
        } else {
            // "[API]" prefix stops REST callers from impersonating real chat users
            int delivered = registry.postMessage("[API] " + from, text, null);
            send(ex, 201, "{\"status\":\"sent\",\"deliveredTo\":" + delivered + "}");
        }
    }

    // ---------- helpers ----------
    private boolean requireMethod(HttpExchange ex, String allowed) throws IOException {
        if (ex.getRequestMethod().equals(allowed)) return true;
        ex.getResponseHeaders().set("Allow", allowed);
        sendError(ex, 405, "Method not allowed. Use " + allowed + ".");
        return false;
    }

    private boolean exactPath(HttpExchange ex, String expected) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals(expected) || path.equals(expected + "/")) return true;
        sendError(ex, 404, "Unknown endpoint.");
        return false;
    }

    /** Returns the request body, or null if it exceeds MAX_BODY_BYTES. */
    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY_BYTES) return null;
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        send(ex, status, "{\"error\":" + JsonUtil.quote(message) + "}");
    }

    private void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
