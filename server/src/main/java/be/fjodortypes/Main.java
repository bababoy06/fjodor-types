package be.fjodortypes;

import java.nio.file.Path;

/**
 * Starts the site. Reads config.properties from the working folder.
 * <pre>
 * java -jar fjodor.jar                  run the site (and the bot's buttons)
 * java -jar fjodor.jar panels           post the rules and Night Desk buttons again (replacing the old ones), then stop
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
            java.util.List<String> done = new java.util.ArrayList<>(), warnings = new java.util.ArrayList<>();
            new Community(new Discord(config), new Doors(config), null).postPanels(done, warnings);
            done.forEach(d -> System.out.println("OK  " + d));
            warnings.forEach(w -> System.out.println("!!  " + w));
            if (done.isEmpty() && warnings.isEmpty()) {
                System.out.println("Nothing to post: run /setup in Discord first.");
            }
            return;
        }
        Store store = new Store(config.dataFile());
        new Server(config, store).start();
    }
}
