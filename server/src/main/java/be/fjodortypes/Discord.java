package be.fjodortypes;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Talks to Discord's REST API as the bot. One instance is shared by the review and the community parts. */
public final class Discord {
    /** Discord's API. The system property is only for tests against a fake Discord. */
    private static final String API = System.getProperty("fjodor.discord-api", "https://discord.com/api/v10");
    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Discord(Config config) {
        this.config = config;
    }

    public Map<String, Object> call(String method, String path, Object body) throws IOException {
        return call(method, path, body, null);
    }

    /** For the few answers that are a list (roles, channels). */
    public Object callList(String method, String path) throws IOException {
        String body = send(method, path, null, null);
        return body.isBlank() ? java.util.List.of() : Json.parse(body);
    }

    /** @param reason shown in the server's audit log (role changes, closed letters) */
    public Map<String, Object> call(String method, String path, Object body, String reason) throws IOException {
        String answer = send(method, path, body, reason);
        return answer.isBlank() ? Map.of() : Json.parseObject(answer);
    }

    /** Waits and tries again (up to 3 times) when Discord says "slow down". */
    private String send(String method, String path, Object body, String reason) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(API + path))
                .header("Authorization", "Bot " + config.botToken())
                .header("User-Agent", "DiscordBot (https://" + config.siteHost() + ", 1)")
                .timeout(Duration.ofSeconds(15));
        if (reason != null) {
            req.header("X-Audit-Log-Reason", URLEncoder.encode(reason, StandardCharsets.UTF_8));
        }
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        try {
            for (int attempt = 0; ; attempt++) {
                HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429 && attempt < 3) {
                    double wait = 1;
                    try {
                        wait = ((Number) Json.parseObject(r.body()).getOrDefault("retry_after", 1)).doubleValue();
                    } catch (RuntimeException ignored) {
                        // keep the default
                    }
                    Thread.sleep((long) (Math.min(wait, 30) * 1000) + 100);
                    continue;
                }
                if (r.statusCode() / 100 != 2) {
                    throw new IOException("Discord answered " + r.statusCode() + " to " + method + " " + path + ": "
                            + r.body().substring(0, Math.min(300, r.body().length())));
                }
                return r.body();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
