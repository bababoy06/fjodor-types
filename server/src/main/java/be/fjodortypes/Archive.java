package be.fjodortypes;

import be.fjodortypes.ArchiveStore.Entry;
import be.fjodortypes.ArchiveStore.Note;
import be.fjodortypes.ArchiveStore.Status;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The archive: a hall in Discord that everyone walks through alone. Pressing "Enter the archive" opens a view only
 * you can see; every button after that changes that same view. You never see the others, only what they left:
 * notes in the margins, underlined lines, and their weekly entries in the Visitors' Ledger.
 * <pre>
 * Hall ─┬─ The Aisle of Voices   (the monologues)        V-001 …
 *       ├─ The Aisle of Letters  (approved drops)         L-001 …
 *       ├─ The Visitors' Ledger  (weekly entries)         W48·3 …
 *       ├─ Search the catalogue
 *       └─ File an entry         (once a week; reviewed like the drops)
 * Card ─ Underline a line · Leave a note · Notes in the margin (report)
 * </pre>
 * All ids start with "arc:". Card keys: v.&lt;monologue id&gt;, l.&lt;drop id&gt;, w.&lt;entry id&gt; (no colons: ids are split on them).
 */
public final class Archive {
    private static final int GREY = 0x3a3936, GREEN = 0x4f7a52, RED = 0x8a3b36, PAGE = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] AMBIENCE = {
            "The lights hum. Rows of shelves disappear into the dark.",
            "Somewhere far off, a drawer closes.",
            "A lamp flickers twice, then decides to stay on.",
            "Footsteps, two aisles over. Then nothing.",
            "The air smells of paper and cold radiators.",
            "A card lies on the floor that wasn't there a minute ago.",
            "Somebody left a chair pulled out at the reading table.",
            "The clock above the door stopped at 3:00. Nobody has fixed it.",
            "Dust turns slowly in the light from the high windows.",
            "A trolley of unsorted letters stands in the middle of the aisle.",
            "Your footsteps are the loudest thing here.",
            "Someone has written a word in the dust on the catalogue. It's been smudged out."};
    private static final String[] NOTHING = {
            "Nothing filed under \"%s\". Someone took that drawer out years ago.",
            "The catalogue has a card for \"%s\". It's blank.",
            "\"%s\"? No. Not here. Not yet.",
            "You search for \"%s\" until the lamp above you buzzes. Nothing."};

    record Card(String key, String number, String aisle, String title, String script, String byline, long sort) {
    }

    private final Config config;
    private final Discord discord;
    private final Doors doors;
    private final Broadcast broadcast;   // reads monologues.js and the approved drops
    private final ArchiveStore store;
    private final ExecutorService background;
    private final Map<String, Long> inTheHall = new ConcurrentHashMap<>();

    public Archive(Config config, Discord discord, Doors doors, Broadcast broadcast, ArchiveStore store,
                   ExecutorService background) {
        this.config = config;
        this.discord = discord;
        this.doors = doors;
        this.broadcast = broadcast;
        this.store = store;
        this.background = background;
    }

    public static boolean handles(String customId) {
        return customId.startsWith("arc:");
    }

    // ------------------------------------------------------------------ the catalogue

    private List<Card> aisle(String aisle) {
        List<Card> cards = new ArrayList<>();
        switch (aisle) {
            case "v" -> {
                int i = 0;
                for (Broadcast.Piece p : broadcast.pool(-1)) {
                    if (!p.id().startsWith("drop-")) {
                        cards.add(new Card("v." + p.id(), "V-%03d".formatted(++i), "The Aisle of Voices",
                                firstLine(p.text()), p.text(), "", i));
                    }
                }
            }
            case "l" -> {
                int i = 0;
                for (Broadcast.Piece p : broadcast.pool(-1)) {
                    if (p.id().startsWith("drop-")) {
                        cards.add(new Card("l." + p.id().substring(5), "L-%03d".formatted(++i), "The Aisle of Letters",
                                firstLine(p.text()), p.text(), "left at the drop by a stranger", i));
                    }
                }
            }
            case "w" -> {
                for (Entry e : store.approvedEntries()) {
                    cards.add(new Card("w." + e.id, e.number, "The Visitors' Ledger", e.title, e.script,
                            "filed by " + e.byline(), e.created));
                }
                cards.sort((a, b) -> Long.compare(b.sort(), a.sort())); // newest first
            }
            default -> {
            }
        }
        return cards;
    }

    private Card card(String key) {
        String aisle = key.isEmpty() ? "" : key.substring(0, 1);
        return aisle(aisle).stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ handling

    public String handle(Map<String, Object> in, long type, Map<?, ?> data, String customId) throws IOException {
        Map<?, ?> user = Interactions.user(in);
        String userId = String.valueOf(user.get("id"));
        String name = Interactions.name(user);
        inTheHall.put(userId, System.currentTimeMillis());
        String[] part = customId.split(":", 4);
        String action = part.length > 1 ? part[1] : "";
        String arg = part.length > 2 ? part[2] : "";
        String arg2 = part.length > 3 ? part[3] : "";
        String value = data.get("values") instanceof List<?> v && !v.isEmpty() ? String.valueOf(v.get(0)) : "";

        switch (action) {
            case "enter":
                return reply(4, hall(userId, ""));
            case "hall":
                return reply(7, hall(userId, ""));
            case "leave":
                inTheHall.remove(userId);
                return reply(7, view("The door closes behind you. The hum of the lights goes on without you.",
                        List.of(), List.of()));
            case "aisle":
                return reply(7, aisleView(arg, parse(arg2)));
            case "pick":
                return openCard(in, value, "");
            case "card":
                return reply(7, cardView(card(arg), ""));
            case "ul":
                return reply(7, underlinePicker(card(arg)));
            case "ulpick": {
                Card c = card(arg);
                if (c == null) {
                    return gone();
                }
                int line = parse(value);
                if (line < 0 || line >= plainLines(c).size()) {
                    return reply(7, cardView(c, "That line isn't on this card."));
                }
                boolean fresh = store.underline(c.key(), line, userId);
                return reply(7, cardView(c, fresh ? "You press the pencil into the paper. The line is yours too, now."
                        : "You'd already underlined that one."));
            }
            case "note":
                if (store.notesToday(userId) >= 10) {
                    return reply(7, cardView(card(arg), "Your pencil is worn down. Come back tomorrow."));
                }
                return reply(7, signChoice("arc:nsign:" + arg + ":", "A note in the margin. Sign it, or leave it unsigned?",
                        "arc:card:" + arg));
            case "nsign":
                return modal("arc:nmodal:" + arg + ":" + arg2, "A note in the margin", List.of(
                        input("note", "Your note", 2, 3, 200, "What did this make you think of?")));
            case "nmodal": {
                Card c = card(arg);
                if (c == null) {
                    return gone();
                }
                String text = Interactions.field(data, "note").strip().replaceAll("\\s+", " ");
                if (text.isEmpty()) {
                    return reply(7, cardView(c, "The note was empty."));
                }
                store.addNote(c.key(), text, userId, name, arg2.equals("s"));
                return reply(7, cardView(c, "Your note is in the margin now. Someone will find it."));
            }
            case "notes":
                return reply(7, notesView(card(arg), ""));
            case "report":
                return report(card(arg), value, userId);
            case "search":
                return modal("arc:smodal", "The card catalogue", List.of(
                        input("q", "What are you looking for?", 1, 2, 60, "a word, a feeling, a door")));
            case "smodal":
                return reply(7, searchView(Interactions.field(data, "q").strip()));
            case "file":
                if (store.filedThisWeek(userId, week())) {
                    return reply(7, hall(userId, "You've already filed an entry this week. The ledger opens again on Monday."));
                }
                return reply(7, signChoice("arc:fsign:", "An entry for the Visitors' Ledger. Sign it, or leave it unsigned?",
                        "arc:hall"));
            case "fsign":
                return modal("arc:fmodal:" + arg, "An entry for the ledger", List.of(
                        input("title", "A title", 1, 2, 80, "What is it called?"),
                        input("text", "Your entry", 2, 80, 1500, "Write it the way the voice would say it.")));
            case "fmodal":
                return fileEntry(data, userId, name, arg.equals("s"));
            case "ok", "no":
                return decideEntry(in, arg, action.equals("ok"), name, userId);
            case "rm", "keep":
                return decideNote(in, arg, action.equals("rm"), name, userId);
            default:
                return Interactions.whisper("The archive doesn't know that door.");
        }
    }

    // ------------------------------------------------------------------ views

    private Map<String, Object> hall(String userId, String notice) {
        long now = System.currentTimeMillis();
        inTheHall.values().removeIf(t -> now - t > 10 * 60_000);
        long others = inTheHall.entrySet().stream()
                .filter(e -> !e.getKey().equals(userId) && now - e.getValue() < 5 * 60_000).count();
        int voices = aisle("v").size(), letters = aisle("l").size(), ledger = aisle("w").size();
        String presence = others == 0 ? "You seem to be alone in the hall."
                : others == 1 ? "There is one other in the hall. You won't see them."
                : "There are " + others + " others in the hall. You won't see them.";
        return view(notice, List.of(Json.obj("color", GREY, "title", "THE ARCHIVE · Main Hall",
                        "description", AMBIENCE[RANDOM.nextInt(AMBIENCE.length)] + "\n\n*" + presence + "*",
                        "footer", Json.obj("text", (voices + letters + ledger) + " cards on the shelves"))),
                List.of(row(button(2, "The Aisle of Voices", "arc:aisle:v:0"),
                                button(2, "The Aisle of Letters", "arc:aisle:l:0"),
                                button(2, "The Visitors' Ledger", "arc:aisle:w:0")),
                        row(button(1, "Search the catalogue", "arc:search"),
                                button(2, "File an entry", "arc:file"),
                                button(2, "Leave", "arc:leave"))));
    }

    private Map<String, Object> aisleView(String aisle, int page) {
        List<Card> cards = aisle(aisle);
        String title = switch (aisle) {
            case "v" -> "The Aisle of Voices";
            case "l" -> "The Aisle of Letters";
            default -> "The Visitors' Ledger";
        };
        String blurb = switch (aisle) {
            case "v" -> "Everything the voice has ever said, filed in order.";
            case "l" -> "Letters left at the drop by strangers, and kept.";
            default -> "One entry a week, from whoever walks here. Newest first.";
        };
        if (cards.isEmpty()) {
            return view("", List.of(Json.obj("color", GREY, "title", title, "description",
                            blurb + "\n\n*This aisle is empty. The shelves are dusted anyway.*")),
                    List.of(row(button(2, "Back to the hall", "arc:hall"))));
        }
        int pages = (cards.size() + PAGE - 1) / PAGE;
        page = Math.max(0, Math.min(page, pages - 1));
        List<Card> shown = cards.subList(page * PAGE, Math.min(cards.size(), (page + 1) * PAGE));
        StringBuilder list = new StringBuilder(blurb).append("\n\n");
        for (Card c : shown) {
            list.append('`').append(c.number()).append("` ").append(escape(cut(c.title(), 70))).append('\n');
        }
        List<Object> rows = new ArrayList<>();
        rows.add(row(select("arc:pick:" + aisle, "Pull a card…", shown.stream().map(c -> option(
                c.number() + " · " + cut(c.title(), 80), c.key(), notesLabel(c.key()))).toList())));
        List<Object> nav = new ArrayList<>();
        if (page > 0) {
            nav.add(button(2, "◂ Previous drawer", "arc:aisle:" + aisle + ":" + (page - 1)));
        }
        if (page < pages - 1) {
            nav.add(button(2, "Next drawer ▸", "arc:aisle:" + aisle + ":" + (page + 1)));
        }
        nav.add(button(2, "Back to the hall", "arc:hall"));
        rows.add(row(nav.toArray()));
        return view("", List.of(Json.obj("color", GREY, "title", title, "description", list.toString().strip(),
                "footer", Json.obj("text", "Drawer " + (page + 1) + " of " + pages + " · " + cards.size() + (cards.size() == 1 ? " card" : " cards")))), rows);
    }

    private String openCard(Map<String, Object> in, String key, String notice) {
        Card c = card(key);
        if (c == null) {
            return gone();
        }
        // reveal it paragraph by paragraph, like the voice typing it
        List<String> paragraphs = paragraphs(c);
        if (paragraphs.size() <= 1) {
            return reply(7, cardView(c, notice));
        }
        background.submit(() -> {
            try {
                for (int i = 2; i <= paragraphs.size(); i++) {
                    TimeUnit.MILLISECONDS.sleep(1300);
                    Map<String, Object> partial = i == paragraphs.size() ? cardView(c, notice)
                            : view("", List.of(cardEmbed(c, String.join("\n\n", paragraphs.subList(0, i)) + " ▌")), List.of());
                    discord.call("PATCH", "/webhooks/" + in.get("application_id") + "/" + in.get("token")
                            + "/messages/@original", partial);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Archive: couldn't turn the page: " + e.getMessage());
            }
        });
        return reply(7, view("", List.of(cardEmbed(c, paragraphs.get(0) + " ▌")), List.of()));
    }

    private Map<String, Object> cardView(Card c, String notice) {
        if (c == null) {
            return view("That card isn't on the shelf anymore.", List.of(), List.of(row(button(2, "Back to the hall", "arc:hall"))));
        }
        String aisle = c.key().substring(0, 1);
        int notes = store.notes(c.key()).size();
        return view(notice, List.of(cardEmbed(c, String.join("\n\n", paragraphs(c)))),
                List.of(row(button(2, "Underline a line", "arc:ul:" + c.key()),
                                button(2, "Leave a note", "arc:note:" + c.key()),
                                button(notes > 0 ? 1 : 2, notes == 0 ? "No notes in the margin" : notes == 1
                                        ? "1 note in the margin" : notes + " notes in the margin", "arc:notes:" + c.key())),
                        row(button(2, "Back to the aisle", "arc:aisle:" + aisle + ":0"),
                                button(2, "Back to the hall", "arc:hall"))));
    }

    private Map<String, Object> cardEmbed(Card c, String body) {
        String footer = c.aisle() + (c.byline().isEmpty() ? "" : " · " + c.byline());
        return Json.obj("color", GREY, "title", c.number() + (c.key().startsWith("w.") ? " · " + cut(c.title(), 200) : ""),
                "description", cut(body, 4000), "footer", Json.obj("text", footer));
    }

    /** The card's text as paragraphs, underlined lines marked with how many people underlined them. */
    private List<String> paragraphs(Card c) {
        Map<Integer, Integer> underlined = store.underlines(c.key());
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        for (String raw : c.script().split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("~") || line.equals("---")) {
                if (!current.isEmpty()) {
                    paragraphs.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            String text = renderLine(line);
            int count = underlined.getOrDefault(index++, 0);
            if (count > 0 && !text.isBlank()) {
                text = "__" + text + "__ " + superscript(count);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(text);
        }
        if (!current.isEmpty()) {
            paragraphs.add(current.toString());
        }
        return paragraphs;
    }

    /** The lines you can underline, numbered the same way paragraphs() counts them. */
    private List<String> plainLines(Card c) {
        List<String> lines = new ArrayList<>();
        for (String raw : c.script().split("\n")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.equals("~") && !line.equals("---")) {
                lines.add(Broadcast.applyDeletes(fill(line.startsWith("> ") ? line.substring(2) : line)));
            }
        }
        return lines;
    }

    private Map<String, Object> underlinePicker(Card c) {
        if (c == null) {
            return cardView(null, "");
        }
        List<String> lines = plainLines(c);
        List<Object> options = new ArrayList<>();
        for (int i = 0; i < lines.size() && options.size() < 25; i++) {
            if (!lines.get(i).isBlank()) {
                options.add(option(cut(lines.get(i), 95), String.valueOf(i), null));
            }
        }
        return view("Which line stays with you?", List.of(cardEmbed(c, String.join("\n\n", paragraphs(c)))),
                List.of(row(select("arc:ulpick:" + c.key(), "Choose a line to underline…", options)),
                        row(button(2, "Never mind", "arc:card:" + c.key()))));
    }

    private Map<String, Object> notesView(Card c, String notice) {
        if (c == null) {
            return cardView(null, "");
        }
        List<Note> notes = new ArrayList<>(store.notes(c.key()));
        notes.sort((a, b) -> Long.compare(b.created, a.created));
        if (notes.size() > 15) {
            notes = notes.subList(0, 15);
        }
        StringBuilder sb = new StringBuilder();
        List<Object> options = new ArrayList<>();
        int i = 0;
        for (Note n : notes) {
            i++;
            sb.append("**").append(i).append(".** “").append(escape(n.text)).append("”\n-# ")
                    .append(escape(n.byline())).append(", ").append(ago(n.created)).append("\n\n");
            options.add(option("Report note " + i, n.id, cut(n.text, 90)));
        }
        if (notes.isEmpty()) {
            sb.append("*The margins are clean. Nobody has written here yet.*");
        }
        List<Object> rows = new ArrayList<>();
        if (!options.isEmpty()) {
            rows.add(row(select("arc:report:" + c.key(), "Something here that shouldn't be? Report it…", options)));
        }
        rows.add(row(button(2, "Leave a note", "arc:note:" + c.key()), button(2, "Back to the card", "arc:card:" + c.key())));
        return view(notice, List.of(Json.obj("color", GREY, "title", "In the margins of " + c.number(),
                "description", cut(sb.toString().strip(), 4000))), rows);
    }

    private Map<String, Object> searchView(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Card> found = new ArrayList<>();
        if (q.length() >= 2) {
            for (String aisle : List.of("v", "l", "w")) {
                for (Card c : aisle(aisle)) {
                    String all = (c.title() + "\n" + String.join("\n", plainLines(c))).toLowerCase(Locale.ROOT);
                    if (all.contains(q)) {
                        found.add(c);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            String line = NOTHING[RANDOM.nextInt(NOTHING.length)].formatted(escape(cut(query, 40)));
            return view("", List.of(Json.obj("color", GREY, "title", "The card catalogue", "description", "*" + line + "*")),
                    List.of(row(button(1, "Search again", "arc:search"), button(2, "Back to the hall", "arc:hall"))));
        }
        List<Card> shown = found.size() > 25 ? found.subList(0, 25) : found;
        StringBuilder sb = new StringBuilder("You pull the drawer marked **" + escape(cut(query, 40)) + "**.\n\n");
        for (Card c : shown) {
            sb.append('`').append(c.number()).append("` ").append(escape(cut(c.title(), 70))).append('\n');
        }
        return view("", List.of(Json.obj("color", GREY, "title", "The card catalogue", "description", sb.toString().strip(),
                        "footer", Json.obj("text", found.size() + (found.size() == 1 ? " card" : " cards")
                                + (found.size() > 25 ? " · showing the first 25" : "")))),
                List.of(row(select("arc:pick:s", "Pull a card…", shown.stream().map(c -> option(
                                c.number() + " · " + cut(c.title(), 80), c.key(), null)).toList())),
                        row(button(1, "Search again", "arc:search"), button(2, "Back to the hall", "arc:hall"))));
    }

    private Map<String, Object> signChoice(String prefix, String question, String back) {
        return view(question, List.of(), List.of(row(button(1, "Sign it with my name", prefix + "s"),
                button(2, "Leave it unsigned", prefix + "a"), button(2, "Never mind", back))));
    }

    // ------------------------------------------------------------------ entries and reports

    private String fileEntry(Map<?, ?> data, String userId, String name, boolean signed) throws IOException {
        String week = week();
        if (store.filedThisWeek(userId, week)) {
            return reply(7, hall(userId, "You've already filed an entry this week. The ledger opens again on Monday."));
        }
        String title = Interactions.field(data, "title").strip().replaceAll("\\s+", " ");
        String text = Interactions.field(data, "text").strip();
        String script = Pauses.format(text);
        if (title.isEmpty() || script.isBlank()) {
            return reply(7, hall(userId, "The entry was empty."));
        }
        Entry e = store.addEntry(week, title, text, script, userId, name, signed);
        if (!doors.reviewChannel().isEmpty()) {
            background.submit(() -> {
                try {
                    Map<String, Object> msg = discord.call("POST", "/channels/" + doors.reviewChannel() + "/messages",
                            Json.obj("content", "A new entry for the Visitors' Ledger.",
                                    "allowed_mentions", Json.obj("parse", List.of()),
                                    "embeds", List.of(entryEmbed(e)), "components", entryButtons(e)));
                    synchronized (store) {
                        e.messageId = String.valueOf(msg.get("id"));
                        store.save();
                    }
                } catch (IOException ex) {
                    System.err.println("Archive: couldn't post entry " + e.id + " for review: " + ex.getMessage());
                }
            });
        }
        return reply(7, hall(userId, "Your entry is with the archivist. If it's kept, it will be filed in the Visitors' Ledger."));
    }

    private Map<String, Object> entryEmbed(Entry e) {
        String script = e.script.replace("```", "`​``");
        String state = switch (e.status) {
            case PENDING -> "Waiting for you";
            case APPROVED -> "Filed as " + e.number + " by " + e.reviewer;
            case REJECTED -> "Put away by " + e.reviewer;
        };
        return Json.obj("title", cut(e.title, 250),
                "description", "```\n" + cut(script, 3800) + "\n```",
                "color", e.status == Status.PENDING ? GREY : e.status == Status.APPROVED ? GREEN : RED,
                "fields", List.of(Json.obj("name", "By", "value", e.authorName + " (<@" + e.authorId + ">)"
                        + (e.signed ? ", signed" : ", unsigned: readers see \"a visitor\""), "inline", false)),
                "footer", Json.obj("text", state + " · " + e.week + " · " + e.id));
    }

    private static List<Object> entryButtons(Entry e) {
        return e.status != Status.PENDING ? List.of() : List.of(row(button(3, "File it", "arc:ok:" + e.id),
                button(4, "Put it away", "arc:no:" + e.id)));
    }

    private String decideEntry(Map<String, Object> in, String id, boolean keep, String reviewer, String userId)
            throws IOException {
        if (!mayReview(in, userId)) {
            return Interactions.whisper("Only the archivist can do that.");
        }
        Entry e = store.entry(id).orElse(null);
        if (e == null) {
            return Interactions.whisper("That entry isn't in the archive anymore.");
        }
        synchronized (store) {
            if (e.status == Status.PENDING) {
                e.status = keep ? Status.APPROVED : Status.REJECTED;
                e.reviewer = reviewer;
                if (keep) {
                    e.number = store.nextNumber(e.week);
                }
                store.save();
            }
        }
        return reply(7, view("", List.of(entryEmbed(e)), entryButtons(e)));
    }

    private String report(Card c, String noteId, String userId) throws IOException {
        Note n = store.note(noteId).orElse(null);
        if (c == null || n == null || n.removed) {
            return reply(7, notesView(c, "That note is already gone."));
        }
        boolean first;
        synchronized (store) {
            first = n.reportedBy.add(userId);
            store.save();
        }
        if (first && !doors.reviewChannel().isEmpty()) {
            background.submit(() -> {
                try {
                    discord.call("POST", "/channels/" + doors.reviewChannel() + "/messages", Json.obj(
                            "content", "A note in the archive was reported.",
                            "allowed_mentions", Json.obj("parse", List.of()),
                            "embeds", List.of(noteEmbed(n, c, "Waiting for you")),
                            "components", List.of(row(button(4, "Remove it", "arc:rm:" + n.id),
                                    button(2, "Keep it", "arc:keep:" + n.id)))));
                } catch (IOException e) {
                    System.err.println("Archive: couldn't post a report: " + e.getMessage());
                }
            });
        }
        return reply(7, notesView(c, "Reported. The Night Desk will look at it. Thank you."));
    }

    private Map<String, Object> noteEmbed(Note n, Card c, String state) {
        return Json.obj("color", GREY, "title", "Note on " + (c == null ? n.card : c.number()),
                "description", "“" + escape(n.text) + "”",
                "fields", List.of(Json.obj("name", "By", "value", n.authorName + " (<@" + n.authorId + ">)"
                                + (n.signed ? ", signed" : ", unsigned"), "inline", true),
                        Json.obj("name", "Reports", "value", String.valueOf(n.reportedBy.size()), "inline", true)),
                "footer", Json.obj("text", state + " · " + n.id));
    }

    private String decideNote(Map<String, Object> in, String id, boolean remove, String reviewer, String userId)
            throws IOException {
        if (!mayReview(in, userId)) {
            return Interactions.whisper("Only the archivist can do that.");
        }
        Note n = store.note(id).orElse(null);
        if (n == null) {
            return Interactions.whisper("That note isn't in the archive anymore.");
        }
        synchronized (store) {
            if (remove) {
                n.removed = true;
            }
            n.reportedBy.clear();
            store.save();
        }
        return reply(7, view("", List.of(noteEmbed(n, card(n.card), (remove ? "Removed by " : "Kept by ") + reviewer)), List.of()));
    }

    private boolean mayReview(Map<String, Object> in, String userId) {
        if (config.reviewerIds().contains(userId)) {
            return true;
        }
        String desk = doors.nightDeskRole();
        return !desk.isEmpty() && in.get("member") instanceof Map<?, ?> m && m.get("roles") instanceof List<?> roles
                && roles.contains(desk);
    }

    // ------------------------------------------------------------------ the door in the channel

    public static Map<String, Object> panel() {
        return Json.obj(
                "embeds", List.of(Json.obj("color", GREY, "title", "The Archive",
                        "description", "A heavy door, slightly open. Behind it, rows of shelves you can't see the end of.\n\n"
                                + "Inside, you'll be alone. Everyone is.\n"
                                + "*But people have been here before you. You'll see what they left.*")),
                "components", List.of(row(button(2, "Enter the archive", "arc:enter"))));
    }

    // ------------------------------------------------------------------ small helpers

    private String week() {
        ZonedDateTime now = ZonedDateTime.now(config.broadcastZone());
        return now.get(IsoFields.WEEK_BASED_YEAR) + "-W" + "%02d".formatted(now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private String renderLine(String line) {
        boolean quote = line.startsWith("> ");
        String text = escape(Broadcast.applyDeletes(fill(quote ? line.substring(2) : line)));
        return quote && !text.isBlank() ? "*" + text + "*" : text;
    }

    private String fill(String s) {
        ZonedDateTime now = ZonedDateTime.now(config.broadcastZone());
        int h = now.getHour();
        String part = h < 5 ? "night" : h < 12 ? "morning" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night";
        return s.replace("{clock}", now.format(DateTimeFormatter.ofPattern("HH:mm")))
                .replace("{weekday}", now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .replace("{part}", part)
                .replace("{visitline}", "I don't know how many times you've walked these aisles.")
                .replace("{device}", "You're holding a card, in a hall with nobody else in it.");
    }

    private static String firstLine(String script) {
        for (String raw : script.split("\n")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.equals("~") && !line.equals("---")) {
                String clean = Broadcast.applyDeletes(line.startsWith("> ") ? line.substring(2) : line)
                        .replaceAll("\\{\\w+}", "…");
                return clean.isBlank() ? "(untitled)" : clean;
            }
        }
        return "(untitled)";
    }

    private String notesLabel(String key) {
        int n = store.notes(key).size();
        return n == 0 ? null : n == 1 ? "1 note in the margin" : n + " notes in the margin";
    }

    private static String superscript(int n) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) {
            sb.append(digits.charAt(c - '0'));
        }
        return sb.toString();
    }

    private static String ago(long millis) {
        Duration d = Duration.between(Instant.ofEpochMilli(millis), Instant.now());
        long days = d.toDays();
        if (days >= 14) {
            return (days / 7) + " weeks ago";
        }
        if (days >= 1) {
            return days == 1 ? "yesterday" : days + " days ago";
        }
        long hours = d.toHours();
        return hours >= 1 ? (hours == 1 ? "an hour ago" : hours + " hours ago") : "just now";
    }

    private static String escape(String s) {
        return Broadcast.escape(s);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String gone() {
        return reply(7, view("That card isn't on the shelf anymore.", List.of(),
                List.of(row(button(2, "Back to the hall", "arc:hall")))));
    }

    private static Map<String, Object> view(String content, List<Object> embeds, List<Object> rows) {
        return Json.obj("content", content, "embeds", embeds, "components", rows,
                "allowed_mentions", Json.obj("parse", List.of()));
    }

    /** 4 = a new message only the presser sees; 7 = change the message the button is on. */
    private static String reply(int type, Map<String, Object> data) {
        if (type == 4) {
            data.put("flags", 64);
        }
        return Json.write(Json.obj("type", type, "data", data));
    }

    private static String modal(String id, String title, List<Object> inputs) {
        List<Object> rows = new ArrayList<>();
        inputs.forEach(i -> rows.add(row(i)));
        return Json.write(Json.obj("type", 9, "data", Json.obj("custom_id", id, "title", title, "components", rows)));
    }

    private static Map<String, Object> input(String id, String label, int style, int min, int max, String placeholder) {
        return Json.obj("type", 4, "custom_id", id, "label", label, "style", style, "min_length", min,
                "max_length", max, "required", true, "placeholder", placeholder);
    }

    private static Map<String, Object> row(Object... components) {
        return Json.obj("type", 1, "components", List.of(components));
    }

    private static Map<String, Object> button(int style, String label, String id) {
        return Json.obj("type", 2, "style", style, "label", label, "custom_id", id);
    }

    private static Map<String, Object> select(String id, String placeholder, List<?> options) {
        return Json.obj("type", 3, "custom_id", id, "placeholder", placeholder, "options", options);
    }

    private static Map<String, Object> option(String label, String value, String description) {
        return Json.obj("label", label, "value", value, "description", description);
    }
}
