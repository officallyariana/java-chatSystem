import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs real sockets against the server; no JUnit dependency needed. */
public final class ChatIntegrationTest {
    private static int assertions = 0;
    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        final PrintWriter output;
        Peer(int port, String username) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(1600);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            send("HELLO", username);
        }
        void send(String... fields) { output.println(Protocol.encode(fields)); }
        String[] next() throws IOException {
            String raw = input.readLine();
            if (raw == null) throw new EOFException("Server disconnected");
            return Protocol.decode(raw);
        }
        String[] until(String event) throws IOException {
            for (int i = 0; i < 15; i++) {
                String[] f = next();
                if (f[0].equals(event)) return f;
            }
            throw new AssertionError("Did not receive " + event);
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
    public static void main(String[] args) throws Exception {
        Path storage = Files.createTempDirectory("java-chat-tests-");
        try (ServerSocket probe = new ServerSocket(0)) {
            int port = probe.getLocalPort();
            probe.close();
            try (ChatServer server = new ChatServer(port, storage)) {
                ExecutorService runner = Executors.newSingleThreadExecutor();
                Future<?> active = runner.submit(() -> {
                    try { server.start(); } catch (IOException e) { throw new RuntimeException(e); }
                });
                try {
                    Peer alice = null;
                    for (int i = 0; i < 30; i++) {
                        try { alice = new Peer(port, "Alice"); break; }
                        catch (ConnectException e) { Thread.sleep(30); }
                    }
                    if (alice == null) throw new AssertionError("Server startup failed");
                    Peer connected = alice;
                    try (connected; Peer bob = new Peer(port, "Bob"); Peer charlie = new Peer(port, "Charlie")) {
                        check(alice.until("WELCOME")[1].equals("Alice"), "Alice welcome");
                        check(bob.until("WELCOME")[1].equals("Bob"), "Bob welcome");
                        check(charlie.until("WELCOME")[1].equals("Charlie"), "Charlie welcome");
                        try (Peer duplicate = new Peer(port, "alice")) {
                            check(duplicate.until("ERROR")[1].contains("already"), "Case-insensitive duplicate rejected");
                        }
                        alice.send("JOIN", "project");
                        bob.send("JOIN", "project");
                        check(alice.until("JOINED")[1].equals("project"), "Alice joined project");
                        check(bob.until("JOINED")[1].equals("project"), "Bob joined project");
                        alice.send("SAY", "Hello project room!");
                        check(alice.until("ROOM_MSG")[3].equals("Hello project room!"), "Alice sees room message");
                        check(bob.until("ROOM_MSG")[3].equals("Hello project room!"), "Bob sees room message");
                        // Charlie is in another room; a USERS response must not be preceded by a ROOM_MSG.
                        charlie.send("USERS");
                        String[] charlieEvent = charlie.until("USERS");
                        check(charlieEvent[1].contains("Alice") && charlieEvent[1].contains("Bob"), "Presence list");
                        alice.send("DM", "Charlie", "Only you should see this");
                        check(alice.until("DM_MSG")[3].contains("Only you"), "Sender receives DM confirmation");
                        check(charlie.until("DM_MSG")[3].contains("Only you"), "Recipient receives DM");
                        bob.send("HISTORY");
                        bob.until("HISTORY_START");
                        check(bob.until("HISTORY_MSG")[2].equals("Alice"), "Room history persisted");
                        bob.until("HISTORY_END");
                        alice.send("DMHISTORY", "Charlie");
                        alice.until("HISTORY_START");
                        check(alice.until("HISTORY_MSG")[2].equals("Alice"), "Private history persisted");
                        alice.until("HISTORY_END");
                        alice.send("DM", "Nobody", "ping");
                        check(alice.until("ERROR")[1].contains("not online"), "Offline recipient handled");
                        alice.send("JOIN", "!invalid");
                        check(alice.until("ERROR")[1].contains("Room names"), "Invalid room rejected");
                    }
                    // Previous sessions ended. Verify reconnect can retrieve stored messages.
                    try (Peer newAlice = new Peer(port, "Alice")) {
                        check(newAlice.until("WELCOME")[1].equals("Alice"), "Username reusable after disconnect");
                        newAlice.send("JOIN", "project");
                        newAlice.until("JOINED");
                        newAlice.send("HISTORY");
                        newAlice.until("HISTORY_START");
                        check(newAlice.until("HISTORY_MSG")[3].equals("Hello project room!"), "History survives reconnect");
                    }
                } finally {
                    server.close();
                    runner.shutdownNow();
                    try { active.get(2, TimeUnit.SECONDS); }
                    catch (CancellationException | ExecutionException | TimeoutException ignored) { }
                }
            }
        }
        System.out.println("PASS: " + assertions + " integration assertions.");
    }
}
