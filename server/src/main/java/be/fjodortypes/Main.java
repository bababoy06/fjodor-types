package be.fjodortypes;

import java.nio.file.Path;

/** Starts the site. Reads config.properties from the working folder (or the path given as the first argument). */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load(Path.of(args.length > 0 ? args[0] : "config.properties"));
        Store store = new Store(config.dataFile());
        new Server(config, store).start();
    }
}
