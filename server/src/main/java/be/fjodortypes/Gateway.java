package be.fjodortypes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The always-on connection to Discord, for the things a button can't see: someone joining the server.
 * Uses the JDK's own WebSocket. Needs the <b>Server Members Intent</b> switched on (Developer Portal → Bot).
 * Reconnects by itself, with a growing pause, whenever the connection drops.
 */
public final class Gateway {
    private static final String URL = System.getProperty("fjodor.gateway", "wss://gateway.discord.gg/?v=10&encoding=json");
    private static final int INTENTS = 1 | 1 << 1; // GUILDS, GUILD_MEMBERS

    private final String token;
    private final BiConsumer<String, Map<?, ?>> onEvent;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway");
        t.setDaemon(true);
        return t;
    });
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile Long sequence;
    private volatile boolean acked = true;
    private volatile int failures;

    /** @param onEvent called with the event name (e.g. GUILD_MEMBER_ADD) and its data */
    public Gateway(String token, BiConsumer<String, Map<?, ?>> onEvent) {
        this.token = token;
        this.onEvent = onEvent;
    }

    public void start() {
        connect();
    }

    private void connect() {
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(URL), new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        System.err.println("Gateway: couldn't connect: " + err.getMessage());
                        retry(null);
                    }
                });
    }

    /** Reconnect once per dropped connection, however many ways it reports dropping. */
    private synchronized void retry(WebSocket from) {
        if (from != socket) {
            return; // an older connection, already replaced
        }
        socket = null;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        int wait = Math.min(300, 5 * (1 << Math.min(failures++, 6)));
        timer.schedule(this::connect, wait, TimeUnit.SECONDS);
    }

    private void send(Object payload) {
        WebSocket ws = socket;
        if (ws != null) {
            synchronized (ws) {
                ws.sendText(Json.write(payload), true).join();
            }
        }
    }

    /** {"op":1,"d":null} before the first event: Json.obj would drop the null, so build it by hand. */
    private Map<String, Object> heartbeatPayload() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("op", 1);
        m.put("d", sequence);
        return m;
    }

    private void handle(WebSocket ws, Map<String, Object> msg) {
        long op = msg.get("op") instanceof Number n ? n.longValue() : -1;
        if (msg.get("s") instanceof Number s) {
            sequence = s.longValue();
        }
        switch ((int) op) {
            case 10 -> { // Hello: start the heartbeat, then identify
                long interval = ((Number) ((Map<?, ?>) msg.get("d")).get("heartbeat_interval")).longValue();
                acked = true;
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                heartbeat = timer.scheduleAtFixedRate(() -> {
                    if (!acked) {
                        System.err.println("Gateway: no heartbeat answer, reconnecting");
                        ws.abort();
                        retry(ws);
                        return;
                    }
                    acked = false;
                    send(heartbeatPayload());
                }, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                send(Json.obj("op", 2, "d", Json.obj("token", token, "intents", INTENTS,
                        "properties", Json.obj("os", "linux", "browser", "fjodor-types", "device", "fjodor-types"))));
            }
            case 11 -> acked = true;
            case 1 -> send(heartbeatPayload());
            case 7, 9 -> { // Discord asks us to reconnect, or the session is gone: start fresh
                ws.sendClose(4000, "reconnect").whenComplete((x, err) -> {
                    ws.abort();
                    retry(ws);
                });
            }
            case 0 -> {
                String event = String.valueOf(msg.get("t"));
                if (event.equals("READY")) {
                    failures = 0;
                    System.out.println("Gateway: connected");
                }
                if (msg.get("d") instanceof Map<?, ?> d) {
                    try {
                        onEvent.accept(event, d);
                    } catch (RuntimeException e) {
                        System.err.println("Gateway: " + event + " failed: " + e);
                    }
                }
            }
            default -> {
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            sequence = null; // a fresh session counts from the start
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(ws, Json.parseObject(text));
                } catch (RuntimeException e) {
                    System.err.println("Gateway: couldn't read a message: " + e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (code == 4004) {
                System.err.println("Gateway: Discord refused the bot token. Check discord.bot-token.");
                return null;
            }
            if (code == 4014) {
                System.err.println("Gateway: turn on the Server Members Intent (Developer Portal -> Bot), then restart.");
                return null;
            }
            System.err.println("Gateway: closed (" + code + " " + reason + "), reconnecting");
            retry(ws);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            System.err.println("Gateway: " + error.getMessage() + ", reconnecting");
            retry(ws);
        }
    }
}
