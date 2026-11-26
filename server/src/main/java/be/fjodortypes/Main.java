package be.fjodortypes;

import java.nio.file.Path;

/**
 * Starts the site. Reads config.properties from the working folder.
 * <pre>
 * java -jar fjodor.jar                  run the site (and the bot's buttons)
 * java -jar fjodor.jar panels           post the rules button and the Night Desk button once, then stop
 * java -jar fjodor.jar [panels] FILE    the same, with another config file
 * </pre>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        boolean panels = args.length > 0 && args[0].equals("panels");
        int file = panels ? 1 : 0;
        Config config = Config.load(Path.of(args.length > file ? args[file] : "config.properties"));
        if (panels) {
            if (!config.botEnabled()) {
                System.err.println("Fill in discord.bot-token and discord.public-key first.");
                System.exit(1);
            }
            Community.postPanels(config, new Discord(config));
            return;
        }
        Store store = new Store(config.dataFile());
        new Server(config, store).start();
    }
}
