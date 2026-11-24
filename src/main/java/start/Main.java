package start;

import config.AppConfig;
import control.FeatureDatasetController;
import control.GitMetricsController;
import control.OoMetricsController;
import control.ReleaseExtractionController;
import control.SonarMetricsController;
import exception.AnalysisException;
import exception.ConfigException;
import exception.GitException;
import exception.JiraException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Esegue i passi della pipeline. Argomenti (tutti facoltativi, in qualsiasi ordine):
 * <ul>
 *   <li>un file {@code .properties}: configurazione (default {@code config.properties});</li>
 *   <li>i passi da eseguire tra {@code releases git ck sonar features} (default: tutti, in quest'ordine).</li>
 * </ul>
 * Esempio: {@code mvn exec:java -Dexec.args="ck sonar features"}.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
    }

    private static final List<String> ALL_STEPS = List.of("releases", "git", "ck", "sonar", "features");
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        Path configFile = Path.of(AppConfig.DEFAULT_FILE);
        List<String> steps = new ArrayList<>();
        for (String a : args) {
            if (a.endsWith(".properties")) {
                configFile = Path.of(a);
            } else {
                steps.add(a.toLowerCase(Locale.ROOT));
            }
        }
        if (steps.isEmpty()) {
            steps.addAll(ALL_STEPS);
        }
        try {
            AppConfig config = AppConfig.load(configFile);
            for (String step : ALL_STEPS) {
                if (steps.contains(step)) {
                    LOG.info(() -> "=== Passo: " + step + " ===");
                    runStep(step, config);
                }
            }
            steps.stream().filter(s -> !ALL_STEPS.contains(s))
                    .forEach(s -> LOG.warning(() -> "Passo sconosciuto ignorato: " + s + " (validi: " + ALL_STEPS + ")"));
        } catch (ConfigException | JiraException | GitException | PersistenceException | AnalysisException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void runStep(String step, AppConfig config)
            throws ConfigException, JiraException, GitException, PersistenceException, AnalysisException {
        switch (step) {
            case "releases" -> new ReleaseExtractionController(config).run();
            case "git" -> new GitMetricsController(config).run();
            case "ck" -> new OoMetricsController(config).run();
            case "sonar" -> new SonarMetricsController(config).run();
            case "features" -> new FeatureDatasetController(config).run();
            default -> throw new IllegalStateException("Passo non previsto: " + step);
        }
    }
}
