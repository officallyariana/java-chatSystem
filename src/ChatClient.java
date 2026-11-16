import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HELLO_TIMEOUT_MS = 10_000;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ConcurrentMap<String, Long> PENDING = new ConcurrentHashMap<>();

    private ChatClient() { }

    private static void show(String[] event) {
        if (event.length == 0) {
            System.out.println("Received empty server event.");
            return;
        }
        switch (event[0]) {
            case "WELCOME" -> {
                if (event.length >= 3)
                    System.out.println("Connected as " + event[1] + ". Room: " + event[2] + ". Type /help for commands.");
            }
            case "ROOM_MSG" -> {
                if (event.length >= 4)
                    System.out.println("[" + event[1] + "] " + event[2] + ": " + event[3]);
            }
            case "DM_MSG" -> {
                if (event.length >= 4)
                    System.out.println("[PRIVATE] " + event[1] + " -> " + event[2] + ": " + event[3]);
            }
            case "JOINED" -> {
                if (event.length >= 2) System.out.println("Switched to room: " + event[1]);
            }
            case "HISTORY_START" -> {
                if (event.length >= 3)
                    System.out.println("--- " + event[1] + " history: " + event[2] + " (last 20) ---");
            }
            case "HISTORY_MSG" -> {
                if (event.length >= 4)
                    System.out.println(event[1] + " | " + event[2] + ": " + event[3]);
            }
            case "HISTORY_END" -> System.out.println("--- end of history ---");
            case "ACK" -> {
                if (event.length < 4) {
                    System.out.println("Received malformed acknowledgment.");
                    return;
                }
                Long started = PENDING.remove(event[1]);
                if (started == null) {
                    System.out.println("[ACK] Unrecognized request ID: " + event[1]);
                } else {
                    double rttMs = (System.nanoTime() - started) / 1_000_000.0;
                    System.out.printf(Locale.ROOT,
                            "[ACK] %s #%s: %s (server processing round-trip: %.2f ms)%n",
                            event[2], event[1], event[3], rttMs);
                }
            }
            case "ERROR" -> {
                if (event.length >= 2) System.out.println("Error from server: " + event[1]);
            }
            case "INFO", "USERS", "ROOMS" -> {
                if (event.length >= 2) System.out.println(event[0] + ": " + event[1]);
            }
            default -> System.out.println("Received: " + String.join(" ", event));
        }
    }

    private static void help() {
        System.out.println("Commands:\n"
                + "  /join ROOM           join or create a room\n"
                + "  /use ROOM            alias for /join\n"
                + "  /rooms               list rooms\n"
                + "  /users               list online users\n"
                + "  /dm USER MESSAGE     private message (with ACK timing)\n"
                + "  /history             last 20 messages in current room\n"
                + "  /dmhistory USER      last 20 direct messages\n"
                + "  /quit                disconnect\n"
                + "  /help                show commands\n"
                + "  Any other text sends a message to your room (with ACK timing).");
    }

    private static boolean send(PrintWriter out, String... fields) {
        try {
            out.println(Protocol.encode(fields));
            if (out.checkError()) {
                System.err.println("Connection lost while sending a message.");
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            System.out.println("Message not sent: " + e.getMessage());
            return true; 
        }
    }

    private static boolean sendWithAck(PrintWriter out, String type, String... args) {
        String id = Long.toString(NEXT_ID.incrementAndGet());
        String[] fields = new String[args.length + 2];
        fields[0] = type;
        fields[1] = id;
        System.arraycopy(args, 0, fields, 2, args.length);
        long started = System.nanoTime();
        PENDING.put(id, started);
        try {
            out.println(Protocol.encode(fields));
            if (out.checkError()) {
                PENDING.remove(id);
                System.err.println("Connection lost while sending " + type + ".");
                return false;
            }
        } catch (IllegalArgumentException e) {
            PENDING.remove(id);
            System.out.println("Message not sent: " + e.getMessage());
        }
        return true;
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        String username = null;
        int port = 5555;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--username" -> username = args[++i];
                    default -> {
                        System.err.println("Usage: java ChatClient [--host 127.0.0.1] [--port 5555] [--username Alice]");
                        return;
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.err.println("Invalid options. Usage: java ChatClient [--host HOST] [--port 5555] [--username Alice]");
            return;
        }
        if (port < 1 || port > 65535) {
            System.err.println("Port must be between 1 and 65535.");
            return;
        }

        Scanner keyboard = new Scanner(System.in);
        if (username == null) {
            System.out.print("Choose username: ");
            if (!keyboard.hasNextLine()) return;
            username = keyboard.nextLine().trim();
        }
        if (!username.matches("[A-Za-z0-9_-]{1,20}")) {
            System.err.println("Username must be 1-20 letters, numbers, _ or -.");
            return;
        }

        AtomicBoolean connected = new AtomicBoolean(true);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                if (!send(out, "HELLO", username)) return;
                String welcome = in.readLine();
                if (welcome == null) {
                    System.err.println("Server closed the connection during login.");
                    return;
                }
                String[] first;
                try {
                    first = Protocol.decode(welcome);
                } catch (IllegalArgumentException e) {
                    System.err.println("Server sent an invalid login response.");
                    return;
                }
                show(first);
                if (first.length == 0 || !"WELCOME".equals(first[0])) return;
                socket.setSoTimeout(0);

                Thread receiver = new Thread(() -> {
                    try {
                        String line;
                        while ((line = in.readLine()) != null) {
                            try {
                                show(Protocol.decode(line));
                            } catch (IllegalArgumentException e) {
                                System.out.println("Received malformed server data.");
                            }
                        }
                        System.out.println("Server closed the connection.");
                    } catch (IOException e) {
                        if (connected.get()) System.out.println("Connection lost: " + e.getMessage());
                    } finally {
                        connected.set(false);
                        PENDING.clear();
                        try { socket.close(); } catch (IOException ignored) { }
                        System.out.println("Press Enter to exit if the console is awaiting input.");
                    }
                }, "chat-receiver");
                receiver.setDaemon(true);
                receiver.start();
                help();

                while (connected.get() && keyboard.hasNextLine()) {
                    String line = keyboard.nextLine().trim();
                    if (!connected.get()) break;
                    if (line.isBlank()) continue;
                    boolean ok = true;
                    if (line.equals("/help")) { help(); continue; }
                    else if (line.equals("/quit")) { send(out, "QUIT"); connected.set(false); break; }
                    else if (line.startsWith("/join ") || line.startsWith("/use ")) {
                        ok = send(out, "JOIN", line.substring(line.indexOf(' ') + 1).trim());
                    } else if (line.equals("/rooms")) ok = send(out, "ROOMS");
                    else if (line.equals("/users")) ok = send(out, "USERS");
                    else if (line.equals("/history")) ok = send(out, "HISTORY");
                    else if (line.startsWith("/dmhistory ")) ok = send(out, "DMHISTORY", line.substring(11).trim());
                    else if (line.startsWith("/dm ")) {
                        String[] parts = line.substring(4).trim().split("\\s+", 2);
                        if (parts.length != 2 || parts[1].isBlank())
                            System.out.println("Usage: /dm USER MESSAGE");
                        else ok = sendWithAck(out, "DM", parts[0], parts[1]);
                    } else if (line.startsWith("/")) System.out.println("Unknown command. Type /help.");
                    else ok = sendWithAck(out, "SAY", line);
                    if (!ok) break;
                }
            }
        } catch (SocketTimeoutException e) {
            System.err.println("Timed out waiting for the chat server.");
        } catch (IOException e) {
            System.err.println("Unable to connect or communicate: " + e.getMessage());
        } finally {
            connected.set(false);
            PENDING.clear();
        }
    }
}
