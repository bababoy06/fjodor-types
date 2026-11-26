package be.fjodortypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The role and channel IDs the bot uses. /setup fills them in and keeps them in discord.json
 * (next to submissions.json), so nobody has to copy IDs by hand. A value set in config.properties always wins.
 */
public final class Doors {
    public static final String MEMBER_ROLE = "memberRole", VISITOR_ROLE = "visitorRole", NIGHT_DESK_ROLE = "nightDeskRole",
            RULES_CHANNEL = "rulesChannel", DESK_CHANNEL = "deskChannel", REVIEW_CHANNEL = "reviewChannel",
            BROADCAST_CHANNEL = "broadcastChannel", BROADCAST_RECENT = "broadcastRecent",
            RULES_PANEL = "rulesPanel", DESK_PANEL = "deskPanel";

    private final Path file;
    private final Map<String, String> fixed = new LinkedHashMap<>();  // from config.properties
    private final Map<String, String> saved = new LinkedHashMap<>();  // from /setup

    public Doors(Config config) throws IOException {
        this.file = config.dataFile().toAbsolutePath().resolveSibling("discord.json");
        put(fixed, MEMBER_ROLE, config.memberRoleId());
        put(fixed, VISITOR_ROLE, config.visitorRoleId());
        put(fixed, NIGHT_DESK_ROLE, config.nightDeskRoleId());
        put(fixed, RULES_CHANNEL, config.rulesChannelId());
        put(fixed, DESK_CHANNEL, config.deskChannelId());
        put(fixed, REVIEW_CHANNEL, config.reviewChannelId());
        put(fixed, BROADCAST_CHANNEL, config.broadcastChannelId());
        if (Files.exists(file)) {
            Json.parseObject(Files.readString(file, StandardCharsets.UTF_8))
                    .forEach((k, v) -> put(saved, k, String.valueOf(v)));
        }
    }

    public synchronized String get(String key) {
        return fixed.getOrDefault(key, saved.getOrDefault(key, ""));
    }

    public synchronized boolean fixedInConfig(String key) {
        return fixed.containsKey(key);
    }

    public synchronized void set(String key, String value) throws IOException {
        if (value == null || value.isBlank()) {
            saved.remove(key);
        } else {
            put(saved, key, value);
        }
        Path tmp = Files.createTempFile(file.getParent(), ".discord", ".tmp");
        Files.writeString(tmp, Json.write(new LinkedHashMap<>(saved)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public String memberRole() {
        return get(MEMBER_ROLE);
    }

    public String visitorRole() {
        return get(VISITOR_ROLE);
    }

    public String nightDeskRole() {
        return get(NIGHT_DESK_ROLE);
    }

    public String rulesChannel() {
        return get(RULES_CHANNEL);
    }

    public String deskChannel() {
        return get(DESK_CHANNEL);
    }

    public String reviewChannel() {
        return get(REVIEW_CHANNEL);
    }

    public String broadcastChannel() {
        return get(BROADCAST_CHANNEL);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank() && !v.equals("null")) {
            m.put(k, v);
        }
    }
}
