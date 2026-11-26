package be.fjodortypes;

import be.fjodortypes.Store.Status;
import be.fjodortypes.Store.Submission;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The review channel in Discord. Each submission is posted there with Approve / Reject / Edit pauses buttons.
 * Button presses arrive through Interactions, which checks Discord's signature first.
 */
public final class Review {
    private static final int GREY = 0x3a3936, GREEN = 0x4f7a52, RED = 0x8a3b36;
    private final Config config;
    private final Store store;
    private final Discord discord;

    public Review(Config config, Store store, Discord discord) {
        this.config = config;
        this.store = store;
        this.discord = discord;
    }

    /** Posts a new submission for review. Runs in the background; a failure only costs the Discord message. */
    public void announce(Submission s) {
        try {
            Map<String, Object> msg = discord.call("POST", "/channels/" + config.reviewChannelId() + "/messages",
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

    /** A review button or the edit form. Interactions has already checked Discord's signature. */
    public String handle(Map<String, Object> in, long type, Map<?, ?> data, String customId) throws IOException {
        Map<?, ?> user = Interactions.user(in);
        String userId = String.valueOf(user.get("id"));
        String userName = Interactions.name(user);
        if (!config.reviewerIds().isEmpty() && !config.reviewerIds().contains(userId)) {
            return Interactions.whisper("Only the archivist can do that.");
        }
        int colon = customId.indexOf(':');
        if (colon < 0) {
            return Interactions.whisper("I don't know that button.");
        }
        String action = customId.substring(0, colon);
        String id = customId.substring(colon + 1);
        Submission s = store.find(id).orElse(null);
        if (s == null) {
            return Interactions.whisper("That submission isn't in the archive anymore.");
        }
        synchronized (store) {
            if (type == 5 && action.equals("edit")) {
                String script = Pauses.sanitize(Interactions.field(data, "script"));
                if (script.isBlank()) {
                    return Interactions.whisper("The script can't be empty.");
                }
                s.script = script;
                store.save();
                return update(s);
            }
            if (type != 3) {
                return Interactions.whisper("I don't know that.");
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
                    return Interactions.whisper("I don't know that button.");
                }
            }
        }
    }

    private String update(Submission s) {
        return Json.write(Json.obj("type", 7, "data", Json.obj(
                "embeds", List.of(embed(s)),
                "components", s.status == Status.PENDING ? buttons(s) : List.of())));
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



}
