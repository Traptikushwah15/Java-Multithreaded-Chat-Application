import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Multi-client chat server.
 * Thread-per-client model: the main thread only accepts connections,
 * and every accepted client gets its own dedicated thread.
 *
 * Also starts a REST API (see ApiServer) on a separate HTTP port.
 *
 * Usage: java ChatServer [chatPort] [maxClients] [apiPort]
 */
public class ChatServer {

    private static final int DEFAULT_PORT = 5000;
    private static final int DEFAULT_MAX_CLIENTS = 50;
    private static final int DEFAULT_API_PORT = 8080;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int maxClients = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_MAX_CLIENTS;

        int apiPort = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_API_PORT;

        ClientRegistry registry = new ClientRegistry(maxClients);

        try {
            ApiServer api = new ApiServer(apiPort, registry);
            api.start();
            System.out.println("[SERVER] REST API started on http://localhost:" + apiPort + "/api/status");
        } catch (IOException e) {
            System.err.println("[SERVER] REST API could not start on port " + apiPort + ": " + e.getMessage());
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[SERVER] Chat server started on port " + port
                    + " (max " + maxClients + " clients)");

            while (true) {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(new ClientHandler(socket, registry));
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.err.println("[SERVER] Error: " + e.getMessage());
        }
    }
}
