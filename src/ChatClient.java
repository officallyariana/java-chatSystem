import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Interactive command-line chat client. Run one process per username. */
public final class ChatClient {
    private static void show(String[] event) {
        switch (event[0]) {
            case "WELCOME" -> System.out.println("Connected as " + event[1] + ". Room: " + event[2] + ". Type /help for commands.");
            case "ROOM_MSG" -> System.out.println("[" + event[1] + "] " + event[2] + ": " + event[3]);
            case "DM_MSG" -> System.out.println("[PRIVATE] " + event[1] + " -> " + event[2] + ": " + event[3]);
            case "JOINED" -> System.out.println("Switched to room: " + event[1]);
            case "HISTORY_START" -> System.out.println("--- " + event[1] + " history: " + event[2] + " (last 20) ---");
            case "HISTORY_MSG" -> System.out.println(event[1] + " | " + event[2] + ": " + event[3]);
            case "HISTORY_END" -> System.out.println("--- end of history ---");
            case "ERROR" -> System.out.println("Error: " + event[1]);
            case "INFO", "USERS", "ROOMS" -> System.out.println(event[0] + ": " + event[1]);
            default -> System.out.println("Received: " + String.join(" ", event));
        }
    }
    private static void help() {
        System.out.println("Commands:\n  /join ROOM           join or create a room\n  /use ROOM            alias for /join\n  /rooms               list rooms\n  /users               list online users\n  /dm USER MESSAGE     private message\n  /history             last 20 messages in current room\n  /dmhistory USER      last 20 direct messages\n  /quit                disconnect\n  /help                show these commands\n  Any other text sends a message to your current room.");
    }
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1", username = null;
        int port = 5555;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--username" -> username = args[++i];
                default -> { System.err.println("Usage: java ChatClient [--host 127.0.0.1] [--port 5555] [--username Alice]"); return; }
            }
        }
        Scanner keyboard = new Scanner(System.in);
        if (username == null) {
            System.out.print("Choose username: ");
            username = keyboard.nextLine().trim();
        }
        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            out.println(Protocol.encode("HELLO", username));
            String welcome = in.readLine();
            if (welcome == null) { System.err.println("Server closed the connection."); return; }
            String[] event = Protocol.decode(welcome);
            show(event);
            if (!event[0].equals("WELCOME")) return;
            Thread receiver = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        try { show(Protocol.decode(line)); }
                        catch (RuntimeException e) { System.out.println("Received invalid server data."); }
                    }
                } catch (IOException ignored) { }
                System.out.println("Disconnected from server.");
            }, "chat-receiver");
            receiver.setDaemon(true);
            receiver.start();
            while (keyboard.hasNextLine()) {
                String line = keyboard.nextLine();
                if (line.isBlank()) continue;
                if (line.equals("/help")) { help(); continue; }
                if (line.equals("/quit")) { out.println(Protocol.encode("QUIT")); break; }
                if (line.startsWith("/join ") || line.startsWith("/use ")) {
                    out.println(Protocol.encode("JOIN", line.substring(line.indexOf(' ') + 1).trim()));
                } else if (line.equals("/rooms")) out.println(Protocol.encode("ROOMS"));
                else if (line.equals("/users")) out.println(Protocol.encode("USERS"));
                else if (line.equals("/history")) out.println(Protocol.encode("HISTORY"));
                else if (line.startsWith("/dmhistory ")) out.println(Protocol.encode("DMHISTORY", line.substring(11).trim()));
                else if (line.startsWith("/dm ")) {
                    String[] parts = line.substring(4).trim().split("\\s+", 2);
                    if (parts.length != 2) System.out.println("Usage: /dm USER MESSAGE");
                    else out.println(Protocol.encode("DM", parts[0], parts[1]));
                } else if (line.startsWith("/")) System.out.println("Unknown command. Type /help.");
                else out.println(Protocol.encode("SAY", line));
            }
        } catch (IOException e) { System.err.println("Unable to connect: " + e.getMessage()); }
    }
}
