package be.fjodortypes;

import be.fjodortypes.Store.Submission;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves both addresses (plain JDK HttpServer, no extra libraries). Listens on localhost only; nginx adds HTTPS.
 * <pre>
 * fjodor-types.com
 *   GET  /                    the page (index.html from the checkout)
 *   GET  /monologues.js       the monologues in the repo
 *   GET  /community.js        the approved submissions, added to the same list
 * drop.fjodor-types.com
 *   GET  /                    the submission page (drop/index.html)
 *   POST /api/preview         {text} -> {script}: how the pauses will fall
 *   POST /api/submit          {text, signature, own, website, opened} -> {ok}
 * either
 *   POST /discord/interactions   every Discord button: review, rules, Night Desk
 * </pre>
 */
public final class Server {
    static final int MIN_CHARS = 80, MAX_CHARS = 3000, MAX_SIGNATURE = 60, MAX_PENDING = 100;
    private static final int MAX_BODY = 32 * 1024;
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    private final Config config;
    private final Store store;
    private final Review review;            // posts new submissions once a review channel is chosen
    private final Interactions interactions; // all Discord buttons; null without a bot token and public key
    private final Community community;
    private final ExecutorService background = Executors.newFixedThreadPool(4);
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public Server(Config config, Store store) {
        this.config = config;
        this.store = store;
        if (config.botEnabled()) {
            Discord discord = new Discord(config);
            Doors doors;
            try {
                doors = new Doors(config);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read discord.json: " + e.getMessage(), e);
            }
            this.review = new Review(config, store, discord, doors);
            this.community = new Community(discord, doors, background);
            this.interactions = new Interactions(config, review, community);
        } else {
            this.review = null;
            this.community = null;
            this.interactions = null;
        }
    }

    public void start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        System.out.println("fjodor-types listening on " + config.bind() + ":" + config.port()
                + " for " + config.siteHost() + " and " + config.dropHost()
                + (interactions == null ? " (the Discord bot is off: fill in discord.bot-token and discord.public-key)" : ""));
        if (interactions != null) {
            background.submit(community::registerCommands);
            if (config.gateway()) {
                new Gateway(config.botToken(), (event, data) -> {
                    if (event.equals("GUILD_MEMBER_ADD")) {
                        community.memberJoined(data);
                    }
                }).start();
            }
            if (review.ready()) {
                for (Submission s : store.unannounced()) {
                    background.submit(() -> review.announce(s));
                }
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String host = String.valueOf(ex.getRequestHeaders().getFirst("Host")).toLowerCase(Locale.ROOT)
                    .replaceFirst(":\\d+$", "");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");

            if (path.equals("/discord/interactions") && method.equals("POST")) {
                interactions(ex);
            } else if (host.equals(config.dropHost())) {
                switch (method + " " + path) {
                    case "GET /", "GET /index.html" -> file(ex, config.siteDir().resolve("drop/index.html"), "text/html");
                    case "POST /api/preview" -> preview(ex);
                    case "POST /api/submit" -> submit(ex);
                    default -> send(ex, 404, "text/plain", "Not here.");
                }
            } else {
                switch (method + " " + path) {
                    case "GET /", "GET /index.html" -> file(ex, config.siteDir().resolve("index.html"), "text/html");
                    case "GET /monologues.js" -> file(ex, config.siteDir().resolve("monologues.js"), "application/javascript");
                    case "GET /community.js" -> community(ex);
                    default -> send(ex, 404, "text/plain", "Not here.");
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Request failed: " + e);
            try {
                send(ex, 500, "application/json", Json.write(Json.obj("error", "Something went wrong.")));
            } catch (IOException | RuntimeException ignored) {
                // the answer may already be underway
            }
        }
    }

    // ------------------------------------------------------------------ the main page

    /** Approved submissions, in the same shape as monologues.js, added to its list. */
    private void community(HttpExchange ex) throws IOException {
        List<Object> list = new ArrayList<>();
        for (Submission s : store.approved()) {
            list.add(Json.obj("id", "drop-" + s.id, "text", s.script));
        }
        String js = "window.MONOLOGUES=(window.MONOLOGUES||[]).concat("
                + Json.write(list).replace("<", "\\u003c") + ");\n";
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, "application/javascript", js);
    }

    // ------------------------------------------------------------------ the drop page

    private void preview(HttpExchange ex) throws IOException {
        Map<String, Object> in = body(ex);
        if (in == null) {
            return;
        }
        String text = str(in, "text");
        if (text.length() > MAX_CHARS * 2) {
            text = text.substring(0, MAX_CHARS * 2);
        }
        send(ex, 200, "application/json", Json.write(Json.obj("script", Pauses.format(text))));
    }

    private void submit(HttpExchange ex) throws IOException {
        Map<String, Object> in = body(ex);
        if (in == null) {
            return;
        }
        String text = str(in, "text").strip();
        String signature = str(in, "signature").strip().replaceAll("\\s+", " ");
        long opened = in.get("opened") instanceof Number n ? n.longValue() : 0;

        // bots fill in the hidden field, or send within seconds of loading the page: pretend it worked
        if (!str(in, "website").isEmpty() || opened == 0 || System.currentTimeMillis() - opened < 5000) {
            send(ex, 200, "application/json", Json.write(Json.obj("ok", true)));
            return;
        }
        String problem = text.length() < MIN_CHARS ? "That's a little short. Give it at least " + MIN_CHARS + " characters."
                : text.length() > MAX_CHARS ? "That's too long for one sitting. Keep it under " + MAX_CHARS + " characters."
                : signature.length() > MAX_SIGNATURE ? "Keep the signature under " + MAX_SIGNATURE + " characters."
                : !Boolean.TRUE.equals(in.get("own")) ? "Only send something you wrote yourself."
                : null;
        if (problem != null) {
            send(ex, 400, "application/json", Json.write(Json.obj("error", problem)));
            return;
        }
        if (!allowed(clientIp(ex))) {
            send(ex, 429, "application/json", Json.write(Json.obj("error",
                    "You've left a lot already. Come back tomorrow.")));
            return;
        }
        if (store.pending() >= MAX_PENDING) {
            send(ex, 429, "application/json", Json.write(Json.obj("error",
                    "The archive is full for now. Try again in a few days.")));
            return;
        }
        String script = Pauses.format(text);
        if (script.isBlank()) {
            send(ex, 400, "application/json", Json.write(Json.obj("error", "There's nothing to read in that.")));
            return;
        }
        Submission s = store.add(text, script, signature);
        System.out.println("New submission " + s.id + " (" + text.length() + " characters)");
        if (review != null && review.ready()) {
            background.submit(() -> review.announce(s));
        }
        send(ex, 200, "application/json", Json.write(Json.obj("ok", true)));
    }

    /** At most 3 an hour and 6 a day from one address. Only kept in memory, never written down. */
    private boolean allowed(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > DAY) {
                times.pollFirst();
            }
            long lastHour = times.stream().filter(t -> now - t < HOUR).count();
            if (lastHour >= 3 || times.size() >= 6) {
                return false;
            }
            times.addLast(now);
        }
        if (recent.size() > 10_000) {
            recent.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > DAY);
        }
        return true;
    }

    /** nginx adds the visitor's address as the last entry of X-Forwarded-For. */
    private static String clientIp(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            return parts[parts.length - 1].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------ Discord

    private void interactions(HttpExchange ex) throws IOException {
        if (interactions == null) {
            send(ex, 404, "text/plain", "The bot is off.");
            return;
        }
        byte[] body = read(ex);
        if (body == null) {
            send(ex, 413, "text/plain", "Too big.");
            return;
        }
        String answer = interactions.handle(body, ex.getRequestHeaders().getFirst("X-Signature-Ed25519"),
                ex.getRequestHeaders().getFirst("X-Signature-Timestamp"));
        if (answer == null) {
            send(ex, 401, "text/plain", "Bad signature.");
        } else {
            send(ex, 200, "application/json", answer);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = read(ex);
        if (raw == null) {
            send(ex, 413, "application/json", Json.write(Json.obj("error", "That's far too long.")));
            return null;
        }
        try {
            return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            send(ex, 400, "application/json", Json.write(Json.obj("error", "I couldn't read that.")));
            return null;
        }
    }

    private static byte[] read(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            return b.length > MAX_BODY ? null : b;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : "";
    }

    private static void file(HttpExchange ex, Path file, String type) throws IOException {
        if (!Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not here.");
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, type, Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}
