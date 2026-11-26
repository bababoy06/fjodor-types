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
 * @param reviewerIds     Discord users allowed to press the buttons (empty = anyone in the review channel)
 */
public record Config(String bind, int port, String siteHost, String dropHost, Path siteDir, Path dataFile,
                     String botToken, String publicKey, String reviewChannelId, Set<String> reviewerIds) {

    /** Review in Discord runs when the token, the public key and the channel are all filled in. */
    public boolean reviewEnabled() {
        return !botToken.isBlank() && !publicKey.isBlank() && !reviewChannelId.isBlank();
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
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet()));
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
