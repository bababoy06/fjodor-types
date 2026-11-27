package be.fjodortypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What visitors leave in the archive: weekly entries, notes in the margin, and underlines. Kept in archive.json
 * next to submissions.json. Readers only see names when the writer chose to sign; the file always keeps who wrote
 * what, so the Night Desk can act on reports.
 */
public final class ArchiveStore {

    public enum Status { PENDING, APPROVED, REJECTED }

    public static final class Entry {
        public final String id, week, title, text, authorId, authorName;
        public final boolean signed;
        public final long created;
        public String script;
        public Status status = Status.PENDING;
        public String number = "";      // W48·3, given when approved
        public String messageId = "";   // the review message
        public String reviewer = "";

        Entry(String id, String week, String title, String text, String script, String authorId, String authorName,
              boolean signed, long created) {
            this.id = id;
            this.week = week;
            this.title = title;
            this.text = text;
            this.script = script;
            this.authorId = authorId;
            this.authorName = authorName;
            this.signed = signed;
            this.created = created;
        }

        public String byline() {
            return signed ? authorName : "a visitor";
        }
    }

    public static final class Note {
        public final String id, card, text, authorId, authorName;
        public final boolean signed;
        public final long created;
        public boolean removed;
        public final Set<String> reportedBy = new LinkedHashSet<>();

        Note(String id, String card, String text, String authorId, String authorName, boolean signed, long created) {
            this.id = id;
            this.card = card;
            this.text = text;
            this.authorId = authorId;
            this.authorName = authorName;
            this.signed = signed;
            this.created = created;
        }

        public String byline() {
            return signed ? authorName : "a visitor";
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    /** card -> line number -> users who underlined it */
    private final Map<String, Map<Integer, Set<String>>> underlines = new LinkedHashMap<>();

    public ArchiveStore(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        if (root.get("entries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Entry e = new Entry(s(m, "id"), s(m, "week"), s(m, "title"), s(m, "text"), s(m, "script"),
                            s(m, "authorId"), s(m, "authorName"), Boolean.TRUE.equals(m.get("signed")), n(m, "created"));
                    e.status = Status.valueOf(s(m, "status").isEmpty() ? "PENDING" : s(m, "status").toUpperCase());
                    e.number = s(m, "number");
                    e.messageId = s(m, "messageId");
                    e.reviewer = s(m, "reviewer");
                    entries.add(e);
                }
            }
        }
        if (root.get("notes") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Note note = new Note(s(m, "id"), s(m, "card"), s(m, "text"), s(m, "authorId"),
                            s(m, "authorName"), Boolean.TRUE.equals(m.get("signed")), n(m, "created"));
                    note.removed = Boolean.TRUE.equals(m.get("removed"));
                    if (m.get("reportedBy") instanceof List<?> r) {
                        r.forEach(x -> note.reportedBy.add(String.valueOf(x)));
                    }
                    notes.add(note);
                }
            }
        }
        if (root.get("underlines") instanceof Map<?, ?> cards) {
            cards.forEach((card, lines) -> {
                if (lines instanceof Map<?, ?> byLine) {
                    byLine.forEach((line, users) -> {
                        if (users instanceof List<?> u) {
                            Set<String> set = underlines.computeIfAbsent(String.valueOf(card), k -> new LinkedHashMap<>())
                                    .computeIfAbsent(Integer.parseInt(String.valueOf(line)), k -> new LinkedHashSet<>());
                            u.forEach(x -> set.add(String.valueOf(x)));
                        }
                    });
                }
            });
        }
    }

    // ------------------------------------------------------------------ entries

    public synchronized Entry addEntry(String week, String title, String text, String script, String authorId,
                                       String authorName, boolean signed) throws IOException {
        Entry e = new Entry(newId(), week, title, text, script, authorId, authorName, signed, System.currentTimeMillis());
        entries.add(e);
        save();
        return e;
    }

    /** Pending or kept entries by this person this week (a rejected one doesn't use up the week). */
    public synchronized boolean filedThisWeek(String authorId, String week) {
        return entries.stream().anyMatch(e -> e.authorId.equals(authorId) && e.week.equals(week)
                && e.status != Status.REJECTED);
    }

    public synchronized Optional<Entry> entry(String id) {
        return entries.stream().filter(e -> e.id.equals(id)).findFirst();
    }

    public synchronized List<Entry> approvedEntries() {
        return entries.stream().filter(e -> e.status == Status.APPROVED).toList();
    }

    /** The next catalogue number in a week: W48·1, W48·2, ... */
    public synchronized String nextNumber(String week) {
        long kept = entries.stream().filter(e -> e.week.equals(week) && !e.number.isEmpty()).count();
        return "W" + week.substring(week.indexOf('W') + 1) + "·" + (kept + 1);
    }

    // ------------------------------------------------------------------ notes

    public synchronized Note addNote(String card, String text, String authorId, String authorName, boolean signed)
            throws IOException {
        Note n = new Note(newId(), card, text, authorId, authorName, signed, System.currentTimeMillis());
        notes.add(n);
        save();
        return n;
    }

    public synchronized List<Note> notes(String card) {
        return notes.stream().filter(n -> n.card.equals(card) && !n.removed).toList();
    }

    public synchronized Optional<Note> note(String id) {
        return notes.stream().filter(n -> n.id.equals(id)).findFirst();
    }

    public synchronized long notesToday(String authorId) {
        long since = System.currentTimeMillis() - 24 * 3_600_000L;
        return notes.stream().filter(n -> n.authorId.equals(authorId) && n.created > since).count();
    }

    // ------------------------------------------------------------------ underlines

    /** @return false when this person had already underlined that line */
    public synchronized boolean underline(String card, int line, String userId) throws IOException {
        boolean added = underlines.computeIfAbsent(card, k -> new LinkedHashMap<>())
                .computeIfAbsent(line, k -> new LinkedHashSet<>()).add(userId);
        if (added) {
            save();
        }
        return added;
    }

    /** line number -> how many people underlined it */
    public synchronized Map<Integer, Integer> underlines(String card) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        underlines.getOrDefault(card, Map.of()).forEach((line, users) -> counts.put(line, users.size()));
        return counts;
    }

    // ------------------------------------------------------------------ saving

    public synchronized void save() throws IOException {
        List<Object> es = new ArrayList<>();
        for (Entry e : entries) {
            es.add(Json.obj("id", e.id, "week", e.week, "title", e.title, "text", e.text, "script", e.script,
                    "authorId", e.authorId, "authorName", e.authorName, "signed", e.signed, "created", e.created,
                    "status", e.status.name().toLowerCase(), "number", e.number, "messageId", e.messageId,
                    "reviewer", e.reviewer));
        }
        List<Object> ns = new ArrayList<>();
        for (Note n : notes) {
            ns.add(Json.obj("id", n.id, "card", n.card, "text", n.text, "authorId", n.authorId,
                    "authorName", n.authorName, "signed", n.signed, "created", n.created, "removed", n.removed,
                    "reportedBy", new ArrayList<>(n.reportedBy)));
        }
        Map<String, Object> ul = new LinkedHashMap<>();
        underlines.forEach((card, lines) -> {
            Map<String, Object> byLine = new LinkedHashMap<>();
            lines.forEach((line, users) -> byLine.put(String.valueOf(line), new ArrayList<>(users)));
            ul.put(card, byLine);
        });
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".archive", ".tmp");
        Files.writeString(tmp, Json.write(Json.obj("entries", es, "notes", ns, "underlines", ul)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String newId() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String s(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static long n(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number x ? x.longValue() : 0;
    }
}
