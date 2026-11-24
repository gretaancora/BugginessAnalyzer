package start;

import config.AppConfig;
import control.GitMetricsController;
import control.ReleaseExtractionController;
import exception.ConfigException;
import exception.GitException;
import exception.JiraException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
    }

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    /** @param args opzionale: percorso del file di configurazione (default config.properties) */
    public static void main(String[] args) {
        Path configFile = Path.of(args.length > 0 ? args[0] : AppConfig.DEFAULT_FILE);
        try {
            AppConfig config = AppConfig.load(configFile);
            new ReleaseExtractionController(config).run();
            new GitMetricsController(config).run();
        } catch (ConfigException | JiraException | GitException | PersistenceException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
    }
}