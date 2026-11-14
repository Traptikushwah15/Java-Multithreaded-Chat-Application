import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Console chat client. One thread prints incoming messages while the
 * main thread reads what you type and sends it to the server.
 *
 * Usage: java ChatClient [host] [port]
 */
public class ChatClient {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter serverOut = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))) {

            Thread receiver = new Thread(() -> {
                try {
                    String msg;
                    while ((msg = serverIn.readLine()) != null) {
                        System.out.println(msg);
                    }
                } catch (IOException ignored) {
                }
                System.out.println("Disconnected from server.");
                System.exit(0);
            });
            receiver.setDaemon(true);
            receiver.start();

            String input;
            while ((input = keyboard.readLine()) != null) {
                serverOut.println(input);
                if (input.equalsIgnoreCase("/quit")) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Could not connect to " + host + ":" + port + " - " + e.getMessage());
        }
    }
}
