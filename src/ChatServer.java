import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Multi-client TCP chat server with rooms, direct messages, presence and persistent history. */
public final class ChatServer implements AutoCloseable {
    private final int port;
    private final HistoryStore history;
    private final Map<String, Session> online = new HashMap<>();
    private final Map<String, Set<Session>> rooms = new HashMap<>();
    private final Map<String, String> roomNames = new HashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket listener;

    public ChatServer(int port, Path storage) throws IOException {
        this.port = port;
        this.history = new HistoryStore(storage);
        rooms.put("lobby", new HashSet<>());
        roomNames.put("lobby", "lobby");
    }
    /** Blocking accept loop; launch on its own thread if embedding. */
    public void start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        running.set(true);
        System.out.println("Chat server listening on port " + listener.getLocalPort());
        try {
            while (running.get()) {
                Socket socket = listener.accept();
                clients.execute(() -> serve(socket));
            }
        } catch (SocketException e) {
            if (running.get()) throw e;
        } finally { close(); }
    }
    private void serve(Socket socket) {
        Session session = null;
        try (socket;
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(30_000);
            String hello = input.readLine();
            if (hello == null) return;
            String[] init = Protocol.decode(hello);
            if (init.length != 2 || !init[0].equals("HELLO") || !validName(init[1])) {
                output.println(Protocol.encode("ERROR", "Send HELLO with a username (1-20 letters, numbers, _ or -)."));
                return;
            }
            synchronized (this) {
                String key = key(init[1]);
                if (online.containsKey(key)) {
                    output.println(Protocol.encode("ERROR", "Username already online."));
                    return;
                }
                session = new Session(init[1], socket, output);
                online.put(key, session);
                rooms.get("lobby").add(session);
            }
            socket.setSoTimeout(0);
            session.send("WELCOME", session.name, "lobby");
            announce("lobby", session.name + " joined the lobby.", session);
            System.out.println(session.name + " connected");
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    String[] command = Protocol.decode(line);
                    dispatch(session, command);
                } catch (IllegalArgumentException e) { session.send("ERROR", "Malformed command."); }
            }
        } catch (IOException | RuntimeException e) {
            // Broken connections are expected; do not bring down the server.
        } finally {
            if (session != null) disconnect(session);
        }
    }
    private static String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private static boolean validName(String s) { return s.matches("[A-Za-z0-9_-]{1,20}"); }
    private static boolean validRoom(String s) { return s.matches("[A-Za-z0-9_-]{1,30}"); }
    private static boolean validText(String s) { return !s.isBlank() && s.length() <= 2000; }
    private void dispatch(Session user, String[] c) {
        if (c.length == 0) { user.send("ERROR", "Empty command."); return; }
        switch (c[0]) {
            case "JOIN" -> {
                if (c.length != 2 || !validRoom(c[1])) { user.send("ERROR", "Room names: 1-30 letters, numbers, _ or -. "); return; }
                String old;
                String room;
                synchronized (this) {
                    old = user.room;
                    rooms.get(old).remove(user);
                    String k = key(c[1]);
                    roomNames.putIfAbsent(k, c[1]);
                    rooms.computeIfAbsent(k, ignored -> new HashSet<>()).add(user);
                    user.room = k;
                    room = roomNames.get(k);
                }
                user.send("JOINED", room);
                if (!old.equals(key(room))) announce(old, user.name + " left the room.", user);
                announce(key(room), user.name + " joined the room.", user);
            }
            case "SAY" -> {
                if (c.length != 2 || !validText(c[1])) { user.send("ERROR", "Message must have 1-2000 characters."); return; }
                String room;
                synchronized (this) { room = user.room; }
                try {
                    history.addRoom(room, user.name, c[1]);
                    broadcast(room, "ROOM_MSG", roomNames.get(room), user.name, c[1]);
                } catch (IOException e) { user.send("ERROR", "Could not save message."); }
            }
            case "DM" -> {
                if (c.length != 3 || !validName(c[1]) || !validText(c[2])) { user.send("ERROR", "Usage: /dm USER MESSAGE"); return; }
                Session target;
                synchronized (this) { target = online.get(key(c[1])); }
                if (target == null) { user.send("ERROR", "That user is not online."); return; }
                try {
                    history.addPrivate(user.name, target.name, user.name, c[2]);
                    user.send("DM_MSG", user.name, target.name, c[2]);
                    if (target != user) target.send("DM_MSG", user.name, target.name, c[2]);
                } catch (IOException e) { user.send("ERROR", "Could not save private message."); }
            }
            case "ROOMS" -> {
                if (c.length != 1) { user.send("ERROR", "Usage: /rooms"); return; }
                List<String> names;
                synchronized (this) { names = new ArrayList<>(roomNames.values()); }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                user.send("ROOMS", String.join(", ", names));
            }
            case "USERS" -> {
                if (c.length != 1) { user.send("ERROR", "Usage: /users"); return; }
                List<String> names;
                synchronized (this) { names = online.values().stream().map(s -> s.name).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
                user.send("USERS", String.join(", ", names));
            }
            case "HISTORY" -> {
                if (c.length != 1) { user.send("ERROR", "Usage: /history"); return; }
                String room;
                synchronized (this) { room = user.room; }
                try {
                    user.send("HISTORY_START", "room", roomNames.get(room));
                    for (String[] msg : history.room(room, 20)) user.send("HISTORY_MSG", msg[0], msg[1], msg[2]);
                    user.send("HISTORY_END");
                } catch (IOException e) { user.send("ERROR", "Could not load history."); }
            }
            case "DMHISTORY" -> {
                if (c.length != 2 || !validName(c[1])) { user.send("ERROR", "Usage: /dmhistory USER"); return; }
                try {
                    user.send("HISTORY_START", "private", c[1]);
                    for (String[] msg : history.privateMessages(user.name, c[1], 20)) user.send("HISTORY_MSG", msg[0], msg[1], msg[2]);
                    user.send("HISTORY_END");
                } catch (IOException e) { user.send("ERROR", "Could not load history."); }
            }
            case "QUIT" -> { user.send("INFO", "Goodbye."); try { user.socket.close(); } catch (IOException ignored) {} }
            default -> user.send("ERROR", "Unknown command. Try /help in the client.");
        }
    }
    private void announce(String room, String message, Session except) {
        List<Session> targets;
        synchronized (this) { targets = new ArrayList<>(rooms.getOrDefault(room, Set.of())); }
        for (Session s : targets) if (s != except) s.send("INFO", message);
    }
    private void broadcast(String room, String... fields) {
        List<Session> targets;
        synchronized (this) { targets = new ArrayList<>(rooms.getOrDefault(room, Set.of())); }
        for (Session s : targets) s.send(fields);
    }
    private void disconnect(Session user) {
        boolean removed;
        String room;
        synchronized (this) {
            removed = online.remove(key(user.name), user);
            room = user.room;
            if (removed) rooms.get(room).remove(user);
        }
        if (removed) {
            announce(room, user.name + " disconnected.", user);
            System.out.println(user.name + " disconnected");
        }
    }
    @Override public void close() {
        running.set(false);
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        synchronized (this) {
            for (Session s : online.values()) try { s.socket.close(); } catch (IOException ignored) {}
        }
        clients.shutdownNow();
    }
    private static final class Session {
        final String name;
        final Socket socket;
        final PrintWriter out;
        volatile String room = "lobby";
        Session(String name, Socket socket, PrintWriter out) { this.name = name; this.socket = socket; this.out = out; }
        synchronized void send(String... fields) { out.println(Protocol.encode(fields)); }
    }
    public static void main(String[] args) throws Exception {
        int port = 5555;
        Path data = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--data") && i + 1 < args.length) data = Path.of(args[++i]);
            else { System.err.println("Usage: java ChatServer [--port 5555] [--data data]"); return; }
        }
        ChatServer server = new ChatServer(port, data);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
