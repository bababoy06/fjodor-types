package be.fjodortypes;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings from config.properties next to the jar. Every key can also be set as an environment
 * variable (e.g. discord.bot-token -> DISCORD_BOT_TOKEN), which wins.
 *
 * @param siteHost        the main page's host, e.g. fjodor-types.com
 * @param dropHost        the submission page's host, e.g. drop.fjodor-types.com
 * @param siteDir         the repo checkout: index.html, monologues.js and drop/index.html are read from here
 * @param dataFile        where submissions are kept (a JSON file)
 * The role and channel IDs below can be left empty: /setup in Discord chooses them (see Doors).
 * @param reviewerIds     Discord users allowed to press the review buttons (empty = anyone in the review channel)
 * @param memberRoleId    given by the rules button (e.g. Those Who Stayed); opens the public channels
 * @param visitorRoleId   optional: taken away by the rules button, if new members get one
 * @param nightDeskRoleId sees and answers the tickets
 * @param deskChannelId   the Night Desk channel; tickets are private threads inside it
 * @param gateway         keep an always-on connection to Discord (needed to give the visitor role on join)
 */
public record Config(String bind, int port, String siteHost, String dropHost, Path siteDir, Path dataFile,
                     String botToken, String publicKey, String reviewChannelId, Set<String> reviewerIds,
                     String memberRoleId, String visitorRoleId, String nightDeskRoleId,
                     String rulesChannelId, String deskChannelId, boolean gateway) {

    /** The bot answers buttons once it has a token and the public key. */
    public boolean botEnabled() {
        return !botToken.isBlank() && !publicKey.isBlank();
    }

    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        String port = get(p, "web.port", "8810");
        if (!port.matches("\\d{1,5}")) {
            throw new IllegalStateException("web.port must be a number, got: " + port);
        }
        return new Config(
                get(p, "web.bind", "127.0.0.1"),
                Integer.parseInt(port),
                get(p, "site.host", "fjodor-types.com").toLowerCase(Locale.ROOT),
                get(p, "drop.host", "drop.fjodor-types.com").toLowerCase(Locale.ROOT),
                Path.of(get(p, "site.dir", "src")),
                Path.of(get(p, "data.file", "submissions.json")),
                get(p, "discord.bot-token", ""),
                get(p, "discord.public-key", ""),
                get(p, "discord.review-channel-id", ""),
                Arrays.stream(get(p, "discord.reviewer-ids", "").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet()),
                get(p, "discord.member-role-id", ""),
                get(p, "discord.visitor-role-id", ""),
                get(p, "discord.night-desk-role-id", ""),
                get(p, "discord.rules-channel-id", ""),
                get(p, "discord.desk-channel-id", ""),
                !get(p, "discord.gateway", "on").equalsIgnoreCase("off"));
    }

    private static String get(Properties p, String key, String fallback) {
        String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
