package be.fjodortypes;

import be.fjodortypes.Store.Status;
import be.fjodortypes.Store.Submission;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The review channel in Discord. Each submission is posted there with Approve / Reject / Edit pauses buttons.
 * Button presses arrive at /discord/interactions (Discord signs every request; unsigned ones are refused).
 */
public final class Review {
    private static final int GREY = 0x3a3936, GREEN = 0x4f7a52, RED = 0x8a3b36;
    private final Config config;
    private final Store store;
    private final Signature signature;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Review(Config config, Store store) {
        this.config = config;
        this.store = store;
        this.signature = new Signature(config.publicKey());
    }

    /** Posts a new submission for review. Runs in the background; a failure only costs the Discord message. */
    public void announce(Submission s) {
        try {
            Map<String, Object> msg = call("POST", "/channels/" + config.reviewChannelId() + "/messages",
                    Json.obj("content", "A new monologue was left in the archive.",
                            "embeds", List.of(embed(s)), "components", buttons(s)));
            synchronized (store) {
                s.messageId = String.valueOf(msg.getOrDefault("id", ""));
                store.save();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't post submission " + s.id + " to Discord: " + e.getMessage());
        }
    }

    /** @return the JSON answer for Discord, or null when the signature is wrong (answer 401 then) */
    public String handle(byte[] body, String sig, String timestamp) throws IOException {
        if (!signature.valid(sig, timestamp, body)) {
            return null;
        }
        Map<String, Object> in = Json.parseObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        long type = in.get("type") instanceof Number n ? n.longValue() : 0;
        if (type == 1) {
            return Json.write(Json.obj("type", 1)); // Discord checking the endpoint
        }
        Map<?, ?> data = in.get("data") instanceof Map<?, ?> d ? d : Map.of();
        String customId = String.valueOf(data.get("custom_id"));
        Map<?, ?> user = user(in);
        String userId = String.valueOf(user.get("id"));
        String userName = String.valueOf(user.containsKey("global_name") && user.get("global_name") != null
                ? user.get("global_name") : user.get("username"));
        if (!config.reviewerIds().isEmpty() && !config.reviewerIds().contains(userId)) {
            return whisper("Only the archivist can do that.");
        }
        int colon = customId.indexOf(':');
        if (colon < 0) {
            return whisper("I don't know that button.");
        }
        String action = customId.substring(0, colon);
        String id = customId.substring(colon + 1);
        Submission s = store.find(id).orElse(null);
        if (s == null) {
            return whisper("That submission isn't in the archive anymore.");
        }
        synchronized (store) {
            if (type == 5 && action.equals("edit")) {
                String script = Pauses.sanitize(modalValue(data));
                if (script.isBlank()) {
                    return whisper("The script can't be empty.");
                }
                s.script = script;
                store.save();
                return update(s);
            }
            if (type != 3) {
                return whisper("I don't know that.");
            }
            if (s.status != Status.PENDING) {
                return update(s); // someone already decided; just show it
            }
            switch (action) {
                case "approve", "reject" -> {
                    s.status = action.equals("approve") ? Status.APPROVED : Status.REJECTED;
                    s.reviewer = userName;
                    s.decided = System.currentTimeMillis();
                    store.save();
                    return update(s);
                }
                case "edit" -> {
                    return Json.write(Json.obj("type", 9, "data", Json.obj(
                            "custom_id", "edit:" + s.id,
                            "title", "Edit the pauses",
                            "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                                    "type", 4, "custom_id", "script", "style", 2, "label", "Script",
                                    "value", s.script.length() > 4000 ? s.script.substring(0, 4000) : s.script,
                                    "max_length", 4000, "required", true)))))));
                }
                default -> {
                    return whisper("I don't know that button.");
                }
            }
        }
    }

    private String update(Submission s) {
        return Json.write(Json.obj("type", 7, "data", Json.obj(
                "embeds", List.of(embed(s)),
                "components", s.status == Status.PENDING ? buttons(s) : List.of())));
    }

    private static String whisper(String text) {
        return Json.write(Json.obj("type", 4, "data", Json.obj("content", text, "flags", 64)));
    }

    private static Map<String, Object> embed(Submission s) {
        String script = s.script.replace("```", "`​``");
        if (script.length() > 3900) {
            script = script.substring(0, 3900) + "\n…";
        }
        String footer = switch (s.status) {
            case PENDING -> "Waiting for you · " + s.id;
            case APPROVED -> "Approved by " + s.reviewer + " · now on fjodor-types · " + s.id;
            case REJECTED -> "Rejected by " + s.reviewer + " · " + s.id;
        };
        return Json.obj(
                "title", s.status == Status.PENDING ? "Left in the archive" : s.status == Status.APPROVED
                        ? "Added to the archive" : "Put away",
                "description", "```\n" + script + "\n```",
                "color", s.status == Status.PENDING ? GREY : s.status == Status.APPROVED ? GREEN : RED,
                "fields", s.signature.isBlank() ? List.of()
                        : List.of(Json.obj("name", "Signed", "value", s.signature, "inline", true)),
                "footer", Json.obj("text", footer));
    }

    private static List<Object> buttons(Submission s) {
        return List.of(Json.obj("type", 1, "components", List.of(
                Json.obj("type", 2, "style", 3, "label", "Approve", "custom_id", "approve:" + s.id),
                Json.obj("type", 2, "style", 2, "label", "Edit pauses", "custom_id", "edit:" + s.id),
                Json.obj("type", 2, "style", 4, "label", "Reject", "custom_id", "reject:" + s.id))));
    }

    private static Map<?, ?> user(Map<String, Object> in) {
        if (in.get("member") instanceof Map<?, ?> member && member.get("user") instanceof Map<?, ?> u) {
            return u;
        }
        return in.get("user") instanceof Map<?, ?> u ? u : Map.of();
    }

    private static String modalValue(Map<?, ?> data) {
        if (data.get("components") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> r && r.get("components") instanceof List<?> inputs) {
                    for (Object input : inputs) {
                        if (input instanceof Map<?, ?> i && "script".equals(i.get("custom_id"))) {
                            return String.valueOf(i.get("value"));
                        }
                    }
                }
            }
        }
        return "";
    }

    private Map<String, Object> call(String method, String path, Object body) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                .header("Authorization", "Bot " + config.botToken())
                .header("User-Agent", "DiscordBot (https://" + config.siteHost() + ", 1)")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("Discord answered " + r.statusCode() + ": "
                        + r.body().substring(0, Math.min(300, r.body().length())));
            }
            return r.body().isBlank() ? Map.of() : Json.parseObject(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
