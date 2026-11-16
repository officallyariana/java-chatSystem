
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Protocol {
    public static final int MAX_MESSAGE_SIZE = 16384;
    public static final int MAX_FIELD_SIZE = 4096;
    public static final int MAX_FIELDS = 16;

    private Protocol() {}

    public static String encode(String... fields) {

        if (fields == null ||
            fields.length == 0 ||
            fields.length > MAX_FIELDS) {
            throw new IllegalArgumentException(
                "Invalid number of message fields"
            );
        }

        StringBuilder out = new StringBuilder();

        for (int i = 0; i < fields.length; i++) {

            if (fields[i] == null) {
                throw new IllegalArgumentException(
                    "Message fields cannot be null"
                );
            }

            byte[] bytes = fields[i].getBytes(
                StandardCharsets.UTF_8
            );

            if (bytes.length > MAX_FIELD_SIZE) {
                throw new IllegalArgumentException(
                    "Message field exceeds size limit"
                );
            }

            if (i > 0) {
                out.append('\t');
            }

            out.append(
                Base64.getEncoder().encodeToString(bytes)
            );

            if (out.length() > MAX_MESSAGE_SIZE) {
                throw new IllegalArgumentException(
                    "Message exceeds maximum size"
                );
            }
        }

        return out.toString();
    }

    public static String[] decode(String line) {

        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException(
                "Empty message received"
            );
        }

        if (line.length() > MAX_MESSAGE_SIZE) {
            throw new IllegalArgumentException(
                "Message exceeds maximum size"
            );
        }

        String[] parts = line.split("\t", -1);

        if (parts.length > MAX_FIELDS) {
            throw new IllegalArgumentException(
                "Too many message fields"
            );
        }

        String[] fields = new String[parts.length];

        for (int i = 0; i < parts.length; i++) {
            try {
                byte[] bytes =
                    Base64.getDecoder().decode(parts[i]);

                if (bytes.length > MAX_FIELD_SIZE) {
                    throw new IllegalArgumentException(
                        "Decoded field exceeds size limit"
                    );
                }

                fields[i] = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                        CodingErrorAction.REPORT
                    )
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException(
                    "Invalid UTF-8 data", e
                );

            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid message field " + (i + 1) +
                    ": " + e.getMessage(), e
                );
            }
        }

        return fields;
    }
}
