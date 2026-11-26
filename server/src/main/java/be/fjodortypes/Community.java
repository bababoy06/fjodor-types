package be.fjodortypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Discord server's doors.
 * <ul>
 *   <li><b>/setup</b> (Manage Server only): you choose the rules channel, the Night Desk channel, the member role,
 *       the Night Desk role, and optionally a visitor role and the review channel. The bot remembers them
 *       (discord.json), can set the permissions on those channels, and posts the two buttons.</li>
 *   <li><b>Joining</b>: someone new gets the visitor role, if you chose one (needs the always-on connection).</li>
 *   <li><b>verify</b>: the button under the rules. Gives the member role and takes the visitor role away.</li>
 *   <li><b>desk:open</b>: a small form; the letter becomes a private thread for the writer and the Night Desk.</li>
 *   <li><b>desk:close:&lt;writer&gt;</b>: archives and locks that thread. The writer or the Night Desk.</li>
 * </ul>
 * Discord wants an answer within 3 seconds, so anything that needs Discord's API first answers "thinking"
 * and finishes in the background.
 */
public final class Community {
    // Discord permission bits
    private static final long VIEW = 1L << 10, SEND = 1L << 11, EMBED = 1L << 14, HISTORY = 1L << 16,
            REACT = 1L << 6, MANAGE_THREADS = 1L << 34, PUBLIC_THREADS = 1L << 35, PRIVATE_THREADS = 1L << 36,
            SEND_IN_THREADS = 1L << 38;
    private static final long BOT_IN_CHANNEL = VIEW | SEND | EMBED | HISTORY | PRIVATE_THREADS | SEND_IN_THREADS | MANAGE_THREADS;

    private final Discord discord;
    private final Doors doors;
    private final ExecutorService background;
    /** writer's user id -> their open letter. Only in memory: after a restart the next letter just opens a new one. */
    private final Map<String, String> openTickets = new ConcurrentHashMap<>();

    public Community(Discord discord, Doors doors, ExecutorService background) {
        this.discord = discord;
        this.doors = doors;
        this.background = background;
    }

    public static boolean handles(long type, Map<?, ?> data, String customId) {
        return type == 2 && "setup".equals(data.get("name"))
                || customId.equals("verify") || customId.startsWith("desk:");
    }

    // ------------------------------------------------------------------ the /setup command

    /** Registers /setup with Discord. Runs at every start; Discord ignores it when nothing changed. */
    public void registerCommands() {
        try {
            String app = String.valueOf(discord.call("GET", "/oauth2/applications/@me", null).get("id"));
            discord.call("PUT", "/applications/" + app + "/commands", List.of(Json.obj(
                    "name", "setup",
                    "description", "Choose the channels and roles for the rules check, the Night Desk and the review",
                    "default_member_permissions", "32", // Manage Server
                    "contexts", List.of(0),
                    "options", List.of(
                            option(7, "rules", "The channel with your rules. The verify button goes here.", true, List.of(0, 5)),
                            option(7, "night_desk", "Where people write to the Night Desk. Letters become private threads here.", true, List.of(0)),
                            option(8, "member_role", "Given after reading the rules. Opens the public channels.", true, null),
                            option(8, "night_desk_role", "Reads and answers the letters.", true, null),
                            option(8, "visitor_role", "Given when someone joins, taken away once they've read the rules.", false, null),
                            option(7, "review_channel", "Where new drop submissions are posted for review.", false, List.of(0)),
                            option(5, "fix_permissions", "Set the permissions on these channels for you. Default: yes.", false, null),
                            option(5, "hide_other_channels", "Hide every other channel until people have read the rules. Default: no.", false, null)))));
            System.out.println("Registered /setup");
        } catch (IOException e) {
            System.err.println("Couldn't register /setup: " + e.getMessage());
        }
    }

    private static Map<String, Object> option(int type, String name, String description, boolean required, List<Integer> channelTypes) {
        Map<String, Object> o = Json.obj("type", type, "name", name, "description", description, "required", required);
        if (channelTypes != null) {
            o.put("channel_types", channelTypes);
        }
        return o;
    }

    private String setup(Map<String, Object> in, Map<?, ?> data) {
        Map<String, Object> opts = new HashMap<>();
        if (data.get("options") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    opts.put(String.valueOf(m.get("name")), m.get("value"));
                }
            }
        }
        String guild = String.valueOf(in.get("guild_id"));
        String rules = str(opts, "rules"), desk = str(opts, "night_desk"), member = str(opts, "member_role"),
                nightDesk = str(opts, "night_desk_role"), visitor = str(opts, "visitor_role"),
                review = str(opts, "review_channel");
        boolean fix = !Boolean.FALSE.equals(opts.get("fix_permissions"));
        boolean hideOthers = Boolean.TRUE.equals(opts.get("hide_other_channels"));

        if (member.equals(guild) || nightDesk.equals(guild) || visitor.equals(guild)) {
            return Interactions.whisper("@everyone can't be one of the roles. Pick or make a real role.");
        }
        if (member.equals(visitor)) {
            return Interactions.whisper("The member role and the visitor role have to be different.");
        }
        if (rules.equals(desk)) {
            return Interactions.whisper("The rules and the Night Desk need their own channels.");
        }

        background.submit(() -> {
            List<String> done = new ArrayList<>(), warnings = new ArrayList<>();
            try {
                for (String[] kv : new String[][]{{Doors.RULES_CHANNEL, rules}, {Doors.DESK_CHANNEL, desk},
                        {Doors.MEMBER_ROLE, member}, {Doors.NIGHT_DESK_ROLE, nightDesk},
                        {Doors.VISITOR_ROLE, visitor}, {Doors.REVIEW_CHANNEL, review}}) {
                    if (doors.fixedInConfig(kv[0])) {
                        warnings.add("`" + kv[0] + "` is set in config.properties, so that value is used instead.");
                    }
                    doors.set(kv[0], kv[1]);
                }
                done.add("Remembered your choices.");

                String botId = String.valueOf(discord.call("GET", "/users/@me", null).get("id"));
                checkRoles(guild, botId, member, visitor, warnings);
                if (fix) {
                    fixPermissions(guild, botId, rules, desk, review, member, nightDesk, done, warnings);
                }
                if (hideOthers) {
                    hideOtherChannels(guild, member, nightDesk, List.of(rules, desk, review), done, warnings);
                }
                postPanels(done, warnings);
            } catch (IOException | RuntimeException e) {
                System.err.println("/setup failed: " + e.getMessage());
                warnings.add("Something went wrong halfway: " + e.getMessage());
            }
            StringBuilder msg = new StringBuilder("**Setup**\n");
            done.forEach(d -> msg.append("✓ ").append(d).append('\n'));
            warnings.forEach(w -> msg.append("⚠ ").append(w).append('\n'));
            if (warnings.isEmpty()) {
                msg.append("\nEverything is in place. Write your rules above the button in <#").append(rules).append(">.");
            }
            Interactions.followUp(discord, in, msg.length() > 1990 ? msg.substring(0, 1990) + "…" : msg.toString());
        });
        return Interactions.thinking();
    }

    /** The bot can only hand out roles that sit below its own highest role. */
    private void checkRoles(String guild, String botId, String member, String visitor, List<String> warnings) throws IOException {
        Map<String, Object> me = discord.call("GET", "/guilds/" + guild + "/members/" + botId, null);
        Map<String, Integer> positions = new HashMap<>();
        if (discord.callList("GET", "/guilds/" + guild + "/roles") instanceof List<?> roles) {
            for (Object o : roles) {
                if (o instanceof Map<?, ?> r && r.get("position") instanceof Number p) {
                    positions.put(String.valueOf(r.get("id")), p.intValue());
                }
            }
        }
        int top = 0;
        if (me.get("roles") instanceof List<?> mine) {
            for (Object id : mine) {
                top = Math.max(top, positions.getOrDefault(String.valueOf(id), 0));
            }
        }
        for (String role : List.of(member, visitor)) {
            if (!role.isEmpty() && positions.getOrDefault(role, 0) >= top) {
                warnings.add("<@&" + role + "> sits above the bot's own role, so the bot can't give it out. "
                        + "Drag the bot's role above it (Server Settings → Roles).");
            }
        }
    }

    private void fixPermissions(String guild, String botId, String rules, String desk, String review, String member,
                                String nightDesk, List<String> done, List<String> warnings) {
        // #rules: everyone reads, nobody talks
        overwrite(rules, guild, 0, VIEW | HISTORY, SEND | REACT | PUBLIC_THREADS | PRIVATE_THREADS, warnings);
        overwrite(rules, botId, 1, VIEW | SEND | EMBED | HISTORY, 0, warnings);
        done.add("<#" + rules + ">: everyone can read it, nobody can write in it.");

        // the Night Desk: members only write inside their own letter
        overwrite(desk, guild, 0, 0, VIEW, warnings);
        overwrite(desk, member, 0, VIEW | HISTORY | SEND_IN_THREADS, SEND | PUBLIC_THREADS | PRIVATE_THREADS, warnings);
        overwrite(desk, nightDesk, 0, VIEW | HISTORY | SEND | SEND_IN_THREADS | MANAGE_THREADS, 0, warnings);
        overwrite(desk, botId, 1, BOT_IN_CHANNEL, 0, warnings);
        done.add("<#" + desk + ">: members can only write inside their own letter; <@&" + nightDesk + "> sees them all.");

        if (!review.isEmpty()) {
            overwrite(review, guild, 0, 0, VIEW, warnings);
            overwrite(review, nightDesk, 0, VIEW | HISTORY, 0, warnings);
            overwrite(review, botId, 1, VIEW | SEND | EMBED | HISTORY, 0, warnings);
            done.add("<#" + review + ">: hidden from everyone except <@&" + nightDesk + ">.");
        }
        try {
            discord.call("PATCH", "/guilds/" + guild + "/roles/" + nightDesk, Json.obj("mentionable", true), "/setup");
            done.add("<@&" + nightDesk + "> can be mentioned, so it's pulled into every new letter.");
        } catch (IOException e) {
            warnings.add("Couldn't make <@&" + nightDesk + "> mentionable. Turn on \"Allow anyone to @mention this role\".");
        }
    }

    /** Every channel @everyone can see now (other than the ones above) becomes members-only. */
    private void hideOtherChannels(String guild, String member, String nightDesk, List<String> skip,
                                   List<String> done, List<String> warnings) throws IOException {
        int changed = 0;
        if (discord.callList("GET", "/guilds/" + guild + "/channels") instanceof List<?> channels) {
            for (Object o : channels) {
                if (!(o instanceof Map<?, ?> c)) {
                    continue;
                }
                String id = String.valueOf(c.get("id"));
                if (skip.contains(id) || hidesFromEveryone(c, guild)) {
                    continue;
                }
                if (overwrite(id, guild, 0, 0, VIEW, warnings) && overwrite(id, member, 0, VIEW, 0, warnings)) {
                    overwrite(id, nightDesk, 0, VIEW, 0, warnings);
                    changed++;
                }
            }
        }
        done.add(changed + " other channel" + (changed == 1 ? "" : "s") + " now only visible after reading the rules.");
    }

    private static boolean hidesFromEveryone(Map<?, ?> channel, String guild) {
        if (channel.get("permission_overwrites") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> ow && guild.equals(String.valueOf(ow.get("id")))
                        && (Long.parseLong(String.valueOf(ow.get("deny"))) & VIEW) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Sets one role's (type 0) or member's (type 1) permissions on a channel. */
    private boolean overwrite(String channel, String target, int type, long allow, long deny, List<String> warnings) {
        try {
            discord.call("PUT", "/channels/" + channel + "/permissions/" + target, Json.obj(
                    "type", type, "allow", Long.toString(allow), "deny", Long.toString(deny)), "/setup");
            return true;
        } catch (IOException e) {
            warnings.add("Couldn't set permissions in <#" + channel + ">. Give the bot **Manage Roles** and "
                    + "**Manage Channels**, or set them by hand.");
            return false;
        }
    }

    // ------------------------------------------------------------------ the panels

    /** Posts the two button messages, replacing the ones posted before. Also: java -jar fjodor.jar panels */
    public void postPanels(List<String> done, List<String> warnings) {
        panel(Doors.RULES_CHANNEL, Doors.RULES_PANEL, Json.obj(
                "embeds", List.of(Json.obj("color", 0x3a3936, "description",
                        "Read everything above.\nWhen you're done, press the button.\n\n*That's how I'll know you stayed.*")),
                "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                        "type", 2, "style", 2, "label", "I've read the rules", "custom_id", "verify"))))),
                "the rules button", done, warnings);
        panel(Doors.DESK_CHANNEL, Doors.DESK_PANEL, Json.obj(
                "embeds", List.of(Json.obj("color", 0x3a3936, "title", "The Night Desk", "description",
                        "A question. A problem. Something you'd rather not say where everyone can hear.\n\n"
                                + "Write a letter. Only you and the Night Desk will see it.")),
                "components", List.of(Json.obj("type", 1, "components", List.of(Json.obj(
                        "type", 2, "style", 2, "label", "Write to the Night Desk", "custom_id", "desk:open"))))),
                "the Night Desk button", done, warnings);
    }

    private void panel(String channelKey, String panelKey, Map<String, Object> message, String what,
                       List<String> done, List<String> warnings) {
        String channel = doors.get(channelKey);
        if (channel.isEmpty()) {
            return;
        }
        String[] old = doors.get(panelKey).split("/");
        if (old.length == 2) {
            try {
                discord.call("DELETE", "/channels/" + old[0] + "/messages/" + old[1], null);
            } catch (IOException ignored) {
                // already gone
            }
        }
        try {
            String id = String.valueOf(discord.call("POST", "/channels/" + channel + "/messages", message).get("id"));
            doors.set(panelKey, channel + "/" + id);
            done.add("Posted " + what + " in <#" + channel + ">.");
        } catch (IOException e) {
            warnings.add("Couldn't post " + what + " in <#" + channel + ">: the bot needs View Channel, "
                    + "Send Messages and Embed Links there.");
        }
    }

    // ------------------------------------------------------------------ buttons and forms

    public String handle(Map<String, Object> in, long type, Map<?, ?> data, String customId) {
        if (type == 2) {
            return setup(in, data);
        }
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
            String deskRole = doors.nightDeskRole();
            boolean desk = !deskRole.isBlank() && roles.contains(deskRole);
            if (!userId.equals(writer) && !desk) {
                return Interactions.whisper("Only the one who wrote it, or the Night Desk, can close this.");
            }
            String thread = String.valueOf(in.get("channel_id"));
            openTickets.remove(writer, thread);
            background.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(1500); // let the closing message land first
                    discord.call("PATCH", "/channels/" + thread, Json.obj("archived", true, "locked", true),
                            "Letter closed by " + Interactions.name(user));
                } catch (IOException | InterruptedException e) {
                    System.err.println("Couldn't close letter " + thread + ": " + e.getMessage());
                }
            });
            return Json.write(Json.obj("type", 4, "data", Json.obj(
                    "content", "*This letter is closed.* " + Interactions.name(user) + " closed it.",
                    "allowed_mentions", Json.obj("parse", List.of()))));
        }
        return Interactions.whisper("I don't know that button.");
    }

    /** Someone joined (from the always-on connection): give the visitor role, if there is one. */
    public void memberJoined(Map<?, ?> event) {
        String visitor = doors.visitorRole();
        if (visitor.isEmpty() || !(event.get("user") instanceof Map<?, ?> user) || Boolean.TRUE.equals(user.get("bot"))) {
            return;
        }
        String guild = String.valueOf(event.get("guild_id"));
        String userId = String.valueOf(user.get("id"));
        background.submit(() -> {
            try {
                discord.call("PUT", "/guilds/" + guild + "/members/" + userId + "/roles/" + visitor, null, "Joined");
            } catch (IOException e) {
                System.err.println("Couldn't give the visitor role to " + userId + ": " + e.getMessage());
            }
        });
    }

    private String verify(Map<String, Object> in, String guildId, String userId, List<?> roles) {
        String role = doors.memberRole();
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
                String visitor = doors.visitorRole();
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

    private String openForm(String userId) {
        if (doors.deskChannel().isBlank()) {
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
                Map<String, Object> thread = discord.call("POST", "/channels/" + doors.deskChannel() + "/threads",
                        Json.obj("name", title.length() > 90 ? title.substring(0, 90) : title,
                                "type", 12, "invitable", false, "auto_archive_duration", 10080),
                        "Letter from " + name);
                String threadId = String.valueOf(thread.get("id"));
                openTickets.put(userId, threadId);
                discord.call("PUT", "/channels/" + threadId + "/thread-members/" + userId, null);
                String desk = doors.nightDeskRole();
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
                System.err.println("Couldn't open a letter for " + userId + ": " + e.getMessage());
                openTickets.remove(userId);
                answer = "The letter couldn't be delivered. Try again in a minute.";
            }
            Interactions.followUp(discord, in, answer);
        });
        return Interactions.thinking();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
