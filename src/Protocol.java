import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Tab-separated commands, each field base64 encoded to preserve Unicode and newlines. */
public final class Protocol {
    private Protocol() {}
    public static String encode(String... fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append('\t');
            out.append(Base64.getEncoder().encodeToString(fields[i].getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }
    public static String[] decode(String line) {
        String[] parts = line.split("\t", -1);
        String[] fields = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            fields[i] = new String(Base64.getDecoder().decode(parts[i]), StandardCharsets.UTF_8);
        }
        return fields;
    }
}
