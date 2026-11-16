import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatServer implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 16_384;
    private final int port;
    private final HistoryStore history;
    private final Map<String, Session> online = new HashMap<>();
    private final Map<String, Set<Session>> rooms = new HashMap<>();
    private final Map<String, String> roomNames = new HashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedMessages = new AtomicLong();
    private final AtomicLong attemptedDeliveries = new AtomicLong();
    private final AtomicLong detectedSendFailures = new AtomicLong();
    private ServerSocket listener;

    public ChatServer(int port, Path storage) throws IOException {
        this.port = port;
        this.history = new HistoryStore(storage);
        rooms.put("lobby", new HashSet<>());
        roomNames.put("lobby", "lobby");
    }

    private static synchronized void log(String category, String message) {
        System.out.printf("[%s] [%s] %s%n", Instant.now(), category, message);
    }

    public void start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        running.set(true);
        log("SERVER", "Listening on TCP port " + listener.getLocalPort());
        try {
            while (running.get()) {
                Socket socket = listener.accept();
                try {
                    clients.execute(() -> serve(socket));
                } catch (RejectedExecutionException e) {
                    socket.close();
                    if (running.get()) log("ERROR", "Client worker rejected");
                }
            }
        } catch (SocketException e) {
            if (running.get()) throw e;
        } finally {
            close();
        }
    }

    private static String readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == '\n') break;
            if (bytes.size() >= MAX_FRAME_BYTES) {
                throw new FrameException("Message exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            bytes.write(ch);
        }
        if (ch == -1 && bytes.size() == 0) return null;
        if (ch == -1) throw new EOFException("Connection closed during a message");
        byte[] raw = bytes.toByteArray();
        int length = raw.length;
        if (length > 0 && raw[length - 1] == '\r') length--; // tolerate CRLF
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new FrameException("Invalid UTF-8 message");
        }
    }

    private static final class FrameException extends IOException {
        FrameException(String message) { super(message); }
    }

    private void serve(Socket socket) {
        Session session = null;
        try (socket;
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(30_000);
            String hello = readFrame(input);
            if (hello == null) return;
            String[] init;
            try {
                init = Protocol.decode(hello);
            } catch (IllegalArgumentException e) {
                output.println(Protocol.encode("ERROR", "Malformed HELLO message."));
                return;
            }
            if (init.length != 2 || !init[0].equals("HELLO") || !validName(init[1])) {
                output.println(Protocol.encode("ERROR", "Send HELLO with a username (1-20 letters, numbers, _ or -)."));
                return;
            }
            synchronized (this) {
                String userKey = key(init[1]);
                if (online.containsKey(userKey)) {
                    output.println(Protocol.encode("ERROR", "Username already online."));
                    log("REJECT", "Duplicate username: " + init[1]);
                    return;
                }
                session = new Session(init[1], socket, output);
                online.put(userKey, session);
                rooms.get("lobby").add(session);
            }
            socket.setSoTimeout(0);
            session.send("WELCOME", session.name, "lobby");
            announce("lobby", session.name + " joined the lobby.", session);
            log("CONNECT", session.name + " connected; active users=" + onlineCount());
            while (true) {
                String line = readFrame(input);
                if (line == null) break;
                try {
                    dispatch(session, Protocol.decode(line));
                } catch (IllegalArgumentException e) {
                    log("REJECT", "Malformed command from " + session.name);
                    session.send("ERROR", "Malformed command.");
                }
            }
        } catch (FrameException e) {
            log("REJECT", "Invalid frame from " + (session == null ? socket.getRemoteSocketAddress() : session.name)
                    + ": " + e.getMessage());
        } catch (IOException e) {
            log("DISCONNECT", (session == null ? "Unregistered connection" : session.name) + ": " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log("ERROR", "Client handler failed: " + e.getClass().getSimpleName());
        } finally {
            if (session != null) disconnect(session);
        }
    }

    private synchronized int onlineCount() { return online.size(); }
    private static String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private static boolean validName(String s) { return s.matches("[A-Za-z0-9_-]{1,20}"); }
    private static boolean validRoom(String s) { return s.matches("[A-Za-z0-9_-]{1,30}"); }
    private static boolean validText(String s) { return !s.isBlank() && s.length() <= 2000; }
    private static boolean validRequestId(String s) { return s.matches("[A-Za-z0-9_-]{1,64}"); }

    private void dispatch(Session user, String[] c) {
        if (c.length == 0) { user.send("ERROR", "Empty command."); return; }
        switch (c[0]) {
            case "JOIN" -> {
                if (c.length != 2 || !validRoom(c[1])) { user.send("ERROR", "Room names: 1-30 letters, numbers, _ or -."); return; }
                String old, room, k = key(c[1]);
                synchronized (this) {
                    old = user.room;
                    rooms.get(old).remove(user);
                    roomNames.putIfAbsent(k, c[1]);
                    rooms.computeIfAbsent(k, ignored -> new HashSet<>()).add(user);
                    user.room = k;
                    room = roomNames.get(k);
                }
                user.send("JOINED", room);
                if (!old.equals(k)) {
                    announce(old, user.name + " left the room.", user);
                    announce(k, user.name + " joined the room.", user);
                }
                log("ROOM", user.name + " joined " + room);
            }
            case "SAY" -> {
                boolean withId = c.length == 3;
                String id = withId ? c[1] : "";
                String message = withId ? c[2] : c.length == 2 ? c[1] : "";
                if ((c.length != 2 && c.length != 3) || (withId && !validRequestId(id)) || !validText(message)) {
                    user.send("ERROR", "Message must have 1-2000 characters; optional ID must be 1-64 safe characters.");
                    return;
                }
                String room, displayRoom;
                synchronized (this) { room = user.room; displayRoom = roomNames.get(room); }
                try {
                    history.addRoom(room, user.name, message);
                    int sent = broadcast(room, "ROOM_MSG", displayRoom, user.name, message);
                    long number = processedMessages.incrementAndGet();
                    log("MESSAGE", "#" + number + " room=" + room + " from=" + user.name + " write-success=" + sent);
                    if (withId) user.send("ACK", id, "SAY", "STORED");
                } catch (IOException e) {
                    log("ERROR", "Room history save failed for " + user.name);
                    user.send("ERROR", "Could not save message.");
                }
            }
            case "DM" -> {
                boolean withId = c.length == 4;
                String id = withId ? c[1] : "";
                String targetName = withId ? c[2] : c.length == 3 ? c[1] : "";
                String message = withId ? c[3] : c.length == 3 ? c[2] : "";
                if ((c.length != 3 && c.length != 4) || (withId && !validRequestId(id))
                        || !validName(targetName) || !validText(message)) {
                    user.send("ERROR", "Usage: DM [optional ID] USER MESSAGE");
                    return;
                }
                Session target;
                synchronized (this) { target = online.get(key(targetName)); }
                if (target == null) { user.send("ERROR", "That user is not online."); return; }
                try {
                    history.addPrivate(user.name, target.name, user.name, message);
                    int sent = user.send("DM_MSG", user.name, target.name, message) ? 1 : 0;
                    if (target != user && target.send("DM_MSG", user.name, target.name, message)) sent++;
                    attemptedDeliveries.addAndGet(sent);
                    long number = processedMessages.incrementAndGet();
                    log("MESSAGE", "#" + number + " DM from=" + user.name + " to=" + target.name + " write-success=" + sent);
                    if (withId) user.send("ACK", id, "DM", "STORED");
                } catch (IOException e) {
                    log("ERROR", "DM history save failed for " + user.name);
                    user.send("ERROR", "Could not save private message.");
                }
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
                String room, displayRoom;
                synchronized (this) { room = user.room; displayRoom = roomNames.get(room); }
                try {
                    user.send("HISTORY_START", "room", displayRoom);
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
            case "QUIT" -> {
                if (c.length != 1) { user.send("ERROR", "Usage: /quit"); return; }
                user.send("INFO", "Goodbye.");
                try { user.socket.close(); } catch (IOException ignored) { }
            }
            default -> user.send("ERROR", "Unknown command. Try /help in the client.");
        }
    }

    private void announce(String room, String message, Session except) {
        List<Session> targets;
        synchronized (this) { targets = new ArrayList<>(rooms.getOrDefault(room, Set.of())); }
        for (Session s : targets) if (s != except) s.send("INFO", message);
    }

    private int broadcast(String room, String... fields) {
        List<Session> targets;
        synchronized (this) { targets = new ArrayList<>(rooms.getOrDefault(room, Set.of())); }
        int deliveredToSocket = 0;
        for (Session s : targets) if (s.send(fields)) deliveredToSocket++;
        attemptedDeliveries.addAndGet(deliveredToSocket);
        return deliveredToSocket;
    }

    private void disconnect(Session user) {
        boolean removed;
        String room;
        synchronized (this) {
            removed = online.remove(key(user.name), user);
            room = user.room;
            if (removed) rooms.getOrDefault(room, Set.of()).remove(user);
        }
        if (removed) {
            announce(room, user.name + " disconnected.", user);
            log("DISCONNECT", user.name + "; active users=" + onlineCount());
        }
    }

    @Override public void close() {
        running.set(false);
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        synchronized (this) {
            for (Session s : online.values()) try { s.socket.close(); } catch (IOException ignored) { }
        }
        clients.shutdownNow();
    }

    private final class Session {
        final String name;
        final Socket socket;
        final PrintWriter out;
        volatile String room = "lobby";
        Session(String name, Socket socket, PrintWriter out) {
            this.name = name;
            this.socket = socket;
            this.out = out;
        }
        synchronized boolean send(String... fields) {
            if (socket.isClosed()) return false;
            out.println(Protocol.encode(fields));
            if (out.checkError()) {
                detectedSendFailures.incrementAndGet();
                log("SEND_ERROR", "Socket write failed for " + name);
                try { socket.close(); } catch (IOException ignored) { }
                return false;
            }
            return true;
        }
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
