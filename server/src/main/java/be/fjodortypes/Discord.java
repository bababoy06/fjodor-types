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
    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Discord(Config config) {
        this.config = config;
    }

    public Map<String, Object> call(String method, String path, Object body) throws IOException {
        return call(method, path, body, null);
    }

    /** @param reason shown in the server's audit log (role changes, closed tickets) */
    public Map<String, Object> call(String method, String path, Object body, String reason) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
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
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("Discord answered " + r.statusCode() + " to " + method + " " + path + ": "
                        + r.body().substring(0, Math.min(300, r.body().length())));
            }
            return r.body().isBlank() ? Map.of() : Json.parseObject(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
