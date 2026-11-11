import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Persists message records safely with standard-library files; no database driver required. */
public final class HistoryStore {
    private final Path root;
    public HistoryStore(Path root) throws IOException {
        this.root = root.toAbsolutePath();
        Files.createDirectories(this.root);
    }
    private Path path(String type, String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return root.resolve(type + "_" + encoded + ".log");
    }
    private String roomKey(String room) { return room.toLowerCase(java.util.Locale.ROOT); }
    private String dmKey(String a, String b) {
        String[] pair = {a.toLowerCase(java.util.Locale.ROOT), b.toLowerCase(java.util.Locale.ROOT)};
        java.util.Arrays.sort(pair);
        return pair[0] + "\u0000" + pair[1];
    }
    public synchronized void addRoom(String room, String sender, String text) throws IOException {
        append(path("room", roomKey(room)), sender, text);
    }
    public synchronized void addPrivate(String a, String b, String sender, String text) throws IOException {
        append(path("dm", dmKey(a, b)), sender, text);
    }
    private void append(Path file, String sender, String text) throws IOException {
        Files.writeString(file, Protocol.encode(Instant.now().toString(), sender, text) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public synchronized List<String[]> room(String room, int count) throws IOException {
        return tail(path("room", roomKey(room)), count);
    }
    public synchronized List<String[]> privateMessages(String a, String b, int count) throws IOException {
        return tail(path("dm", dmKey(a, b)), count);
    }
    private List<String[]> tail(Path file, int count) throws IOException {
        ArrayDeque<String[]> last = new ArrayDeque<>();
        if (!Files.exists(file)) return new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] msg = Protocol.decode(line);
                    if (msg.length != 3) continue;
                    if (last.size() == count) last.removeFirst();
                    last.addLast(msg);
                } catch (IllegalArgumentException ignored) { /* Skip damaged lines. */ }
            }
        }
        return new ArrayList<>(last);
    }
}
