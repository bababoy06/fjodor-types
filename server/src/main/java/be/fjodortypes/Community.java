package be.fjodortypes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Discord server's own doors, all through buttons (no always-on gateway connection needed).
 * <ul>
 *   <li><b>verify</b>: the button under the rules. Gives the member role (Those Who Stayed), which opens the
 *       public channels. Until then @everyone only sees the rules.</li>
 *   <li><b>desk:open</b>: the button in the Night Desk channel. Opens a small form; the letter becomes a private
 *       thread that only the writer and the Night Desk role can see.</li>
 *   <li><b>desk:close:&lt;writer&gt;</b>: closes (archives and locks) that thread. The writer or the Night Desk.</li>
 * </ul>
 * Discord wants an answer within 3 seconds, so anything that needs Discord's API first answers "working on it"
 * (deferred) and finishes in the background.
 */
public final class Community {
    private final Config config;
    private final Discord discord;
    private final ExecutorService background;
    /** writer's user id -> their open ticket thread. Only in memory: after a restart the next letter just opens a new one. */
    private final Map<String, String> openTickets = new ConcurrentHashMap<>();

    public Community(Config config, Discord discord, ExecutorService background) {
        this.config = config;
        this.discord = discord;
        this.background = background;
    }

    public static boolean handles(String customId) {
        return customId.equals("verify") || customId.startsWith("desk:");
    }

    public String handle(Map<String, Object> in, long type, Map<?, ?> data, String customId) {
        Map<?, ?> user = Interactions.user(in);
        String userId = String.valueOf(user.get("id"));
        List<?> roles = in.get("member") instanceof Map<?, ?> m && m.get("roles") instanceof List<?> r ? r : List.of();
        String guildId = String.valueOf(in.get("guild_id"));

        if (customId.equals("verify") && type == 3) {
            return verify(in, guildId, userId, roles);
        }
        if (customId.equals("desk:open") && type == 3) {
            return openForm(userId);
        }
        if (customId.equals("desk:letter") && type == 5) {
            return sendLetter(in, data, userId, Interactions.name(user));
        }
        if (customId.startsWith("desk:close:") && type == 3) {
            String writer = customId.substring("desk:close:".length());
            boolean desk = !config.nightDeskRoleId().isBlank() && roles.contains(config.nightDeskRoleId());
            if (!userId.equals(writer) && !desk) {
                return Interactions.whisper("Only the one who wrote it, or the Night Desk, can close this.");
            }
            String thread = String.valueOf(in.get("channel_id"));
            openTickets.remove(writer, thread);
            background.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(1500); // let the closing message land first
                    discord.call("PATCH", "/channels/" + thread, Json.obj("archived", true, "locked", true),
                            "Ticket closed by " + Interactions.name(user));
                } catch (IOException | InterruptedException e) {
                    System.err.println("Couldn't close ticket " + thread + ": " + e.getMessage());
                }
            });
            return Json.write(Json.obj("type", 4, "data", Json.obj(
                    "content", "*This letter is closed.* " + Interactions.name(user) + " closed it.",
                    "allowed_mentions", Json.obj("parse", List.of()))));
        }
        return Interactions.whisper("I don't know that button.");
    }

    // ------------------------------------------------------------------ verification

    private String verify(Map<String, Object> in, String guildId, String userId, List<?> roles) {
        String role = config.memberRoleId();
        if (role.isBlank()) {
            return Interactions.whisper("The doors aren't set up yet. Tell the Night Desk.");
        }
        if (roles.contains(role)) {
            return Interactions.whisper("You already stayed. Everything is open to you.");
        }
        background.submit(() -> {
            String answer;
            try {
                discord.call("PUT", "/guilds/" + guildId + "/members/" + userId + "/roles/" + role, null,
                        "Read the rules");
                String visitor = config.visitorRoleId();
                if (!visitor.isBlank() && roles.contains(visitor)) {
                    discord.call("DELETE", "/guilds/" + guildId + "/members/" + userId + "/roles/" + visitor, null,
                            "Read the rules");
                }
                answer = "You read them. Good.\nThe rest of the house is open to you now.";
            } catch (IOException e) {
                System.err.println("Couldn't verify " + userId + ": " + e.getMessage());
                answer = "Something stuck in the lock. Try again in a minute, or tell the Night Desk.";
            }
            Interactions.followUp(discord, in, answer);
        });
        return Interactions.thinking();
    }

    // ------------------------------------------------------------------ the Night Desk

    private String openForm(String userId) {
        if (config.deskChannelId().isBlank()) {
            return Interactions.whisper("The Night Desk isn't set up yet.");
        }
        String open = openTickets.get(userId);
        if (open != null) {
            return Interactions.whisper("You already have a letter open: <#" + open + ">. Write there.");
        }
        return Json.write(Json.obj("type", 9, "data", Json.obj(
                "custom_id", "desk:letter",
                "title", "Write to the Night Desk",
                "components", List.of(
                        Json.obj("type", 1, "components", List.of(Json.obj(
                                "type", 4, "custom_id", "subject", "style", 1, "label", "What is it about?",
                                "placeholder", "A question, a problem, something private", "max_length", 80,
                                "required", true))),
                        Json.obj("type", 1, "components", List.of(Json.obj(
                                "type", 4, "custom_id", "message", "style", 2, "label", "Your letter",
                                "max_length", 1500, "required", true)))))));
    }

    private String sendLetter(Map<String, Object> in, Map<?, ?> data, String userId, String name) {
        String subject = Interactions.field(data, "subject").strip();
        String message = Interactions.field(data, "message").strip();
        if (subject.isEmpty() || message.isEmpty()) {
            return Interactions.whisper("The letter was empty.");
        }
        if (openTickets.containsKey(userId)) {
            return Interactions.whisper("You already have a letter open: <#" + openTickets.get(userId) + ">.");
        }
        background.submit(() -> {
            String answer;
            try {
                String title = (name + " · " + subject).replaceAll("\\s+", " ");
                Map<String, Object> thread = discord.call("POST", "/channels/" + config.deskChannelId() + "/threads",
                        Json.obj("name", title.length() > 90 ? title.substring(0, 90) : title,
                                "type", 12, "invitable", false, "auto_archive_duration", 10080),
                        "Ticket from " + name);
                String threadId = String.valueOf(thread.get("id"));
                openTickets.put(userId, threadId);
                discord.call("PUT", "/channels/" + threadId + "/thread-members/" + userId, null);
                String desk = config.nightDeskRoleId();
                discord.call("POST", "/channels/" + threadId + "/messages", Json.obj(
                        "content", (desk.isBlank() ? "" : "<@&" + desk + "> ") + "<@" + userId + ">",
                        "allowed_mentions", Json.obj("roles", desk.isBlank() ? List.of() : List.of(desk),
                                "users", List.of(userId)),
                        "embeds", List.of(Json.obj("title", subject, "description", message, "color", 0x3a3936,
                                "footer", Json.obj("text", "A letter from " + name
                                        + ". Only you and the Night Desk can see this thread."))),
                        "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                                "type", 2, "style", 2, "label", "Close this letter",
                                "custom_id", "desk:close:" + userId))))));
                answer = "Your letter is on the desk: <#" + threadId + ">\nSomeone will read it. Maybe tonight.";
            } catch (IOException e) {
                System.err.println("Couldn't open a ticket for " + userId + ": " + e.getMessage());
                openTickets.remove(userId);
                answer = "The letter couldn't be delivered. Try again in a minute.";
            }
            Interactions.followUp(discord, in, answer);
        });
        return Interactions.thinking();
    }

    // ------------------------------------------------------------------ the panels

    /** Posts the two button messages. Run once: java -jar fjodor.jar panels */
    public static void postPanels(Config config, Discord discord) throws IOException {
        if (!config.rulesChannelId().isBlank()) {
            discord.call("POST", "/channels/" + config.rulesChannelId() + "/messages", Json.obj(
                    "embeds", List.of(Json.obj("color", 0x3a3936, "description",
                            "Read everything above.\nWhen you're done, press the button.\n\n*That's how I'll know you stayed.*")),
                    "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                            "type", 2, "style", 2, "label", "I've read the rules", "custom_id", "verify"))))));
            System.out.println("Posted the rules button in channel " + config.rulesChannelId());
        }
        if (!config.deskChannelId().isBlank()) {
            discord.call("POST", "/channels/" + config.deskChannelId() + "/messages", Json.obj(
                    "embeds", List.of(Json.obj("color", 0x3a3936, "title", "The Night Desk", "description",
                            "A question. A problem. Something you'd rather not say where everyone can hear.\n\n"
                                    + "Write a letter. Only you and the Night Desk will see it.")),
                    "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                            "type", 2, "style", 2, "label", "Write to the Night Desk", "custom_id", "desk:open"))))));
            System.out.println("Posted the Night Desk button in channel " + config.deskChannelId());
        }
    }
}
