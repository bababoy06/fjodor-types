package be.fjodortypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every submission, in one small JSON file (a few hundred monologues is a few hundred kilobytes).
 * Each change rewrites the file through a temporary copy, so a crash never leaves half a file.
 */
public final class Store {

    public enum Status { PENDING, APPROVED, REJECTED }

    public static final class Submission {
        public final String id;
        public final String text;       // what the writer sent, untouched
        public String script;           // with pauses; the reviewer can edit it
        public final String signature;  // optional, only shown to the reviewer
        public final long created;
        public Status status = Status.PENDING;
        public String messageId = "";   // the review message in Discord
        public String reviewer = "";
        public long decided;

        Submission(String id, String text, String script, String signature, long created) {
            this.id = id;
            this.text = text;
            this.script = script;
            this.signature = signature;
            this.created = created;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final List<Submission> all = new ArrayList<>();

    public Store(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        all.add(read(m));
                    }
                }
            }
        }
    }

    public synchronized Submission add(String text, String script, String signature) throws IOException {
        String id;
        do {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            id = HexFormat.of().formatHex(b);
        } while (find(id).isPresent());
        Submission s = new Submission(id, text, script, signature, System.currentTimeMillis());
        all.add(s);
        write();
        return s;
    }

    public synchronized Optional<Submission> find(String id) {
        return all.stream().filter(s -> s.id.equals(id)).findFirst();
    }

    /** Call after changing a submission. */
    public synchronized void save() throws IOException {
        write();
    }

    public synchronized List<Submission> approved() {
        return all.stream().filter(s -> s.status == Status.APPROVED)
                .sorted((a, b) -> Long.compare(a.decided, b.decided)).toList();
    }

    /** Waiting for review but never posted to Discord (it was down, or review was off). */
    public synchronized List<Submission> unannounced() {
        return all.stream().filter(s -> s.status == Status.PENDING && s.messageId.isEmpty()).toList();
    }

    public synchronized long pending() {
        return all.stream().filter(s -> s.status == Status.PENDING).count();
    }

    private void write() throws IOException {
        List<Object> list = new ArrayList<>();
        for (Submission s : all) {
            list.add(Json.obj("id", s.id, "text", s.text, "script", s.script, "signature", s.signature,
                    "created", s.created, "status", s.status.name().toLowerCase(), "messageId", s.messageId,
                    "reviewer", s.reviewer, "decided", s.decided));
        }
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".submissions", ".tmp");
        Files.writeString(tmp, Json.write(list), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Submission read(Map<?, ?> m) {
        Submission s = new Submission(str(m, "id"), str(m, "text"), str(m, "script"), str(m, "signature"),
                num(m, "created"));
        s.status = Status.valueOf(str(m, "status").isEmpty() ? "PENDING" : str(m, "status").toUpperCase());
        s.messageId = str(m, "messageId");
        s.reviewer = str(m, "reviewer");
        s.decided = num(m, "decided");
        return s;
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static long num(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.longValue() : 0;
    }
}
