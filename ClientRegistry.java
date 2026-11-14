import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe pool of connected clients plus a bounded chat history.
 * All access goes through synchronized methods so concurrent client threads
 * and REST API threads never see (or corrupt) a half-updated state.
 */
public class ClientRegistry {

    private static final int MAX_HISTORY = 100;

    /** One chat message kept in the history (exposed through the REST API). */
    public static final class ChatMessage {
        public final Instant time;
        public final String sender;
        public final String text;

        ChatMessage(Instant time, String sender, String text) {
            this.time = time;
            this.sender = sender;
            this.text = text;
        }
    }

    private final Map<String, ClientHandler> clients = new LinkedHashMap<>();
    private final Deque<ChatMessage> history = new ArrayDeque<>();
    private final int maxClients;

    public ClientRegistry(int maxClients) {
        this.maxClients = maxClients;
    }

    /** Adds a client. Returns a reason string on failure, or null on success. */
    public synchronized String register(String name, ClientHandler handler) {
        if (clients.size() >= maxClients) {
            return "Server is full (" + maxClients + " clients max).";
        }
        if (clients.containsKey(name.toLowerCase())) {
            return "Username '" + name + "' is already taken.";
        }
        clients.put(name.toLowerCase(), handler);
        return null;
    }

    public synchronized void remove(String name) {
        clients.remove(name.toLowerCase());
    }

    /** Relays a system notice (join/leave) to every connected client except the sender. */
    public synchronized void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients.values()) {
            if (client != sender) {
                client.send(message);
            }
        }
    }

    /**
     * Records a chat message in the history and relays it to every client except
     * {@code exclude} (pass null to deliver to everyone, e.g. for REST-posted messages).
     * Returns how many clients received it.
     */
    public synchronized int postMessage(String sender, String text, ClientHandler exclude) {
        history.addLast(new ChatMessage(Instant.now(), sender, text));
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        String formatted = sender + ": " + text;
        int delivered = 0;
        for (ClientHandler client : clients.values()) {
            if (client != exclude) {
                client.send(formatted);
                delivered++;
            }
        }
        return delivered;
    }

    /** Most recent messages, oldest first, at most {@code limit}. */
    public synchronized List<ChatMessage> recentMessages(int limit) {
        List<ChatMessage> all = new ArrayList<>(history);
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public synchronized List<String> userNames() {
        List<String> names = new ArrayList<>();
        for (ClientHandler c : clients.values()) {
            names.add(c.getUsername());
        }
        return names;
    }

    public synchronized String listUsers() {
        return "Online (" + clients.size() + "): " + String.join(", ", userNames());
    }

    /** Disconnects a user. Returns false if no such user is online. */
    public boolean kick(String name) {
        ClientHandler handler;
        synchronized (this) {
            handler = clients.get(name.toLowerCase());
        }
        if (handler == null) {
            return false;
        }
        handler.kick(); // outside the registry lock: it only closes the socket
        return true;
    }

    public synchronized int size() {
        return clients.size();
    }

    public int getMaxClients() {
        return maxClients;
    }
}
