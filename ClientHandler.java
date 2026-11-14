import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/** Runs on its own thread and serves exactly one connected client. */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ClientRegistry registry;
    private PrintWriter out;
    private String username;

    public ClientHandler(Socket socket, ClientRegistry registry) {
        this.socket = socket;
        this.registry = registry;
    }

    public String getUsername() {
        return username;
    }

    /** synchronized so two threads never interleave writes to the same client. */
    public synchronized void send(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    /** Called by the REST API to remove this client from the chat. */
    public void kick() {
        send("*** You were removed from the chat by the server. ***");
        try {
            socket.close(); // makes the reader loop in run() end, which triggers disconnect()
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            this.out = writer;

            if (!login(in)) {
                return;
            }

            registry.broadcast("*** " + username + " joined the chat ***", this);
            send("Welcome, " + username + "! Commands: /users, /quit");
            System.out.println("[SERVER] " + username + " connected. Active: " + registry.size());

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("/quit")) {
                    break;
                } else if (line.equalsIgnoreCase("/users")) {
                    send(registry.listUsers());
                } else {
                    registry.postMessage(username, line, this);
                }
            }
        } catch (IOException e) {
            // client dropped the connection; handled in finally
        } finally {
            disconnect();
        }
    }

    private boolean login(BufferedReader in) throws IOException {
        send("Enter your username:");
        String name = in.readLine();
        if (name == null) return false;
        name = name.trim();

        if (name.isEmpty() || name.contains(" ")) {
            send("ERROR: Username must be one word with no spaces.");
            return false;
        }
        String error = registry.register(name, this);
        if (error != null) {
            send("ERROR: " + error);
            return false;
        }
        this.username = name;
        return true;
    }

    private void disconnect() {
        if (username != null) {
            registry.remove(username);
            registry.broadcast("*** " + username + " left the chat ***", this);
            System.out.println("[SERVER] " + username + " disconnected. Active: " + registry.size());
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
