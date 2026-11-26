package be.fjodortypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Everything Discord sends to /discord/interactions: checks the signature, then hands each button or form to
 * the part it belongs to (the review channel, or the community's doors).
 */
public final class Interactions {
    private final Signature signature;
    private final Review review;        // null when the review channel isn't set
    private final Community community;

    public Interactions(Config config, Review review, Community community) {
        this.signature = new Signature(config.publicKey());
        this.review = review;
        this.community = community;
    }

    /** @return the JSON answer for Discord, or null when the signature is wrong (answer 401 then) */
    public String handle(byte[] body, String sig, String timestamp) throws IOException {
        if (!signature.valid(sig, timestamp, body)) {
            return null;
        }
        Map<String, Object> in = Json.parseObject(new String(body, StandardCharsets.UTF_8));
        long type = in.get("type") instanceof Number n ? n.longValue() : 0;
        if (type == 1) {
            return Json.write(Json.obj("type", 1)); // Discord checking the endpoint
        }
        Map<?, ?> data = in.get("data") instanceof Map<?, ?> d ? d : Map.of();
        String customId = String.valueOf(data.get("custom_id"));
        if (Community.handles(type, data, customId)) {
            return community.handle(in, type, data, customId);
        }
        if (review != null) {
            return review.handle(in, type, data, customId);
        }
        return whisper("I don't know that button.");
    }

    // ------------------------------------------------------------------ small helpers for both parts

    static Map<?, ?> user(Map<String, Object> in) {
        if (in.get("member") instanceof Map<?, ?> member && member.get("user") instanceof Map<?, ?> u) {
            return u;
        }
        return in.get("user") instanceof Map<?, ?> u ? u : Map.of();
    }

    static String name(Map<?, ?> user) {
        Object n = user.get("global_name");
        return String.valueOf(n != null ? n : user.get("username"));
    }

    /** A reply only the presser sees. */
    static String whisper(String text) {
        return Json.write(Json.obj("type", 4, "data", Json.obj("content", text, "flags", 64,
                "allowed_mentions", Json.obj("parse", List.of()))));
    }

    /** "Thinking…" that only the presser sees; finish it with followUp within 15 minutes. */
    static String thinking() {
        return Json.write(Json.obj("type", 5, "data", Json.obj("flags", 64)));
    }

    static void followUp(Discord discord, Map<String, Object> in, String text) {
        try {
            discord.call("PATCH", "/webhooks/" + in.get("application_id") + "/" + in.get("token")
                    + "/messages/@original", Json.obj("content", text, "allowed_mentions", Json.obj("parse", List.of())));
        } catch (IOException e) {
            System.err.println("Couldn't answer an interaction: " + e.getMessage());
        }
    }

    /** The value of a text field in a submitted form. */
    static String field(Map<?, ?> data, String id) {
        if (data.get("components") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> r && r.get("components") instanceof List<?> inputs) {
                    for (Object input : inputs) {
                        if (input instanceof Map<?, ?> i && id.equals(i.get("custom_id"))) {
                            return String.valueOf(i.get("value"));
                        }
                    }
                }
            }
        }
        return "";
    }
}
