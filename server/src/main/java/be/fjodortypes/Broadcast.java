package be.fjodortypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 3 a.m. broadcast: every night the bot types one monologue, live, line by line, in the channel chosen with
 * /setup. Same texts as the site (monologues.js plus approved drops), same pauses. Hidden ones and ones that
 * only play in other hours are skipped. /broadcast starts one right away.
 */
public final class Broadcast {
    private static final Pattern ENTRY = Pattern.compile(
            "\\{\\s*id\\s*:\\s*'([^']+)'([^`]*?)text\\s*:\\s*`([^`]*)`", Pattern.DOTALL);
    private static final Pattern HOURS = Pattern.compile("hours\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]");
    private static final Pattern TOKEN = Pattern.compile("(\\[(?:p|del):\\d+])");
    private static final int REMEMBER = 10; // don't repeat any of the last 10 broadcasts
    private static final SecureRandom RANDOM = new SecureRandom();

    record Piece(String id, String text) {
    }

    private final Config config;
    private final Discord discord;
    private final Doors doors;
    private final Store store;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "broadcast");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();

    public Broadcast(Config config, Discord discord, Doors doors, Store store) {
        this.config = config;
        this.discord = discord;
        this.doors = doors;
        this.store = store;
    }

    public void start() {
        scheduleNext();
    }

    private void scheduleNext() {
        ZonedDateTime now = ZonedDateTime.now(config.broadcastZone());
        ZonedDateTime next = now.with(config.broadcastTime()).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long wait = Duration.between(now, next).toMillis();
        timer.schedule(() -> {
            try {
                if (!doors.broadcastChannel().isEmpty()) {
                    type(doors.broadcastChannel());
                }
            } catch (RuntimeException e) {
                System.err.println("Broadcast failed: " + e);
            } finally {
                scheduleNext();
            }
        }, wait, TimeUnit.MILLISECONDS);
        System.out.println("Next broadcast: " + next.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm z", Locale.ENGLISH)));
    }

    /** For /broadcast. @return false when one is already being typed */
    public boolean now() {
        if (busy.get() || doors.broadcastChannel().isEmpty()) {
            return false;
        }
        timer.submit(() -> {
            try {
                type(doors.broadcastChannel());
            } catch (RuntimeException e) {
                System.err.println("Broadcast failed: " + e);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ choosing

    /** Everything that may be said at this hour (or at any hour, with -1): the repo's monologues and the approved drops. */
    List<Piece> pool(int hour) {
        List<Piece> pieces = new ArrayList<>();
        try {
            String js = Files.readString(config.siteDir().resolve("monologues.js"), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", ""); // the explanation at the top has an example entry
            Matcher m = ENTRY.matcher(js);
            while (m.find()) {
                String fields = m.group(2);
                if (fields.contains("hidden")) {
                    continue;
                }
                Matcher h = HOURS.matcher(fields);
                if (hour >= 0 && h.find()) {
                    int from = Integer.parseInt(h.group(1)), to = Integer.parseInt(h.group(2));
                    boolean in = from < to ? hour >= from && hour < to : hour >= from || hour < to;
                    if (!in) {
                        continue;
                    }
                }
                pieces.add(new Piece(m.group(1), m.group(3)));
            }
        } catch (IOException e) {
            System.err.println("Broadcast: couldn't read monologues.js: " + e.getMessage());
        }
        for (Store.Submission s : store.approved()) {
            pieces.add(new Piece("drop-" + s.id, s.script));
        }
        return pieces;
    }

    private Piece pick(int hour) throws IOException {
        List<Piece> pool = pool(hour);
        if (pool.isEmpty()) {
            return null;
        }
        List<String> recent = new ArrayList<>(Arrays.asList(doors.get(Doors.BROADCAST_RECENT).split(",")));
        recent.removeIf(String::isBlank);
        List<Piece> fresh = pool.stream().filter(p -> !recent.contains(p.id())).toList();
        Piece piece = (fresh.isEmpty() ? pool : fresh).get(RANDOM.nextInt((fresh.isEmpty() ? pool : fresh).size()));
        recent.add(piece.id());
        while (recent.size() > Math.min(REMEMBER, Math.max(0, pool.size() - 1))) {
            recent.remove(0);
        }
        doors.set(Doors.BROADCAST_RECENT, String.join(",", recent));
        return piece;
    }

    // ------------------------------------------------------------------ typing

    private void type(String channel) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now(config.broadcastZone());
            Piece piece = pick(now.getHour());
            if (piece == null) {
                return;
            }
            System.out.println("Broadcasting " + piece.id() + " in " + channel);
            new Typist(channel, now).type(piece.text());
        } catch (IOException e) {
            System.err.println("Broadcast stopped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            busy.set(false);
        }
    }

    /** Types into one or more messages by editing them, a line at a time, with the script's pauses. */
    private final class Typist {
        private static final String CURSOR = " ▌";
        private final String channel;
        private final ZonedDateTime when;
        private final StringBuilder shown = new StringBuilder();
        private String messageId;

        Typist(String channel, ZonedDateTime when) {
            this.channel = channel;
            this.when = when;
        }

        void type(String script) throws IOException, InterruptedException {
            boolean first = true, gap = false;
            for (String raw : script.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("~") || line.equals("---")) {
                    Thread.sleep(3500);
                    gap = true;
                    continue;
                }
                long pause = 0;
                boolean quote = line.startsWith("> ");
                Matcher tokens = TOKEN.matcher(line);
                while (tokens.find()) {
                    String t = tokens.group(1);
                    int n = Integer.parseInt(t.replaceAll("\\D", ""));
                    if (t.startsWith("[p:")) {
                        pause += Math.min(n, 6000);
                    }
                }
                String clean = applyDeletes(fill(quote ? line.substring(2) : line));
                String out = escape(clean);
                if (quote && !out.isBlank()) {
                    out = "*" + out + "*";
                }
                discord.call("POST", "/channels/" + channel + "/typing", null);
                Thread.sleep(Math.min(6000, 900 + 35L * clean.length()) + pause);
                if (!first && shown.length() + out.length() + 60 > 1900) {
                    finish(""); // this message is full: start a new one
                    shown.setLength(0);
                    messageId = null;
                }
                if (shown.length() > 0) {
                    shown.append(gap ? "\n\n" : "\n");
                }
                gap = false;
                shown.append(out);
                show(shown + CURSOR);
                first = false;
                Thread.sleep(700);
            }
            String time = when.format(DateTimeFormatter.ofPattern("H:mm"));
            finish("\n\n-# typed at " + time + ", for whoever was awake · " + config.siteHost());
        }

        private void show(String content) throws IOException {
            if (messageId == null) {
                messageId = String.valueOf(discord.call("POST", "/channels/" + channel + "/messages",
                        Json.obj("content", content, "allowed_mentions", Json.obj("parse", List.of()))).get("id"));
            } else {
                discord.call("PATCH", "/channels/" + channel + "/messages/" + messageId, Json.obj("content", content));
            }
        }

        private void finish(String footer) throws IOException {
            if (messageId != null) {
                show(shown + footer);
            }
        }

        /** The same tokens the page fills in, said the way they fit 3 a.m. in a channel. */
        private String fill(String s) {
            return s.replace("{clock}", when.format(DateTimeFormatter.ofPattern("HH:mm")))
                    .replace("{weekday}", when.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    .replace("{part}", "night")
                    .replace("{visitline}", "I don't know how many of you are here. I can feel a few.")
                    .replace("{device}", "You're reading this in a channel, in the dark, when you should be asleep.");
        }
    }

    /** Text with [p:] removed and [del:n] applied, as the page would leave it on screen. */
    static String applyDeletes(String line) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TOKEN.matcher(line);
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            String t = m.group(1);
            if (t.startsWith("[del:")) {
                int n = Integer.parseInt(t.replaceAll("\\D", ""));
                sb.setLength(Math.max(0, sb.length() - n));
            }
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /** Keep Discord from turning the voice's punctuation into formatting. */
    static String escape(String s) {
        return s.replaceAll("([*_~`|\\\\])", "\\\\$1");
    }

    static LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s.length() == 4 ? "0" + s : s);
        } catch (RuntimeException e) {
            throw new IllegalStateException("broadcast.time must look like 03:00, got: " + s);
        }
    }

    static ZoneId parseZone(String s) {
        try {
            return ZoneId.of(s);
        } catch (RuntimeException e) {
            throw new IllegalStateException("broadcast.timezone must be like Europe/Brussels, got: " + s);
        }
    }
}
