package control;

import config.AppConfig;
import dao.CsvTable;
import dao.MetricsDAO;
import entity.GitMetric;
import entity.OoMetrics;
import exception.ConfigException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Chiude il passo 3: unisce le metriche Git, CK e SonarCloud in {@value #FEATURES_FILE}, una riga per
 * coppia (classe, release), nell'ordine delle colonne concordato. La colonna Buggy si aggiunge al passo 4.
 */
public final class FeatureDatasetController {

    public static final String FEATURES_FILE = "features.csv";

    private static final Logger LOG = Logger.getLogger(FeatureDatasetController.class.getName());

    private final AppConfig config;

    public FeatureDatasetController(AppConfig config) {
        this.config = config;
    }

    /** Ordine finale delle colonne (dopo Project, Class, ReleaseID). */
    static List<String> featureColumns() {
        List<String> cols = new ArrayList<>(List.of("Size", "Complexity", "CognitiveComplexity"));
        cols.addAll(List.of(OoMetrics.COLUMNS));
        for (GitMetric m : GitMetric.values()) {
            cols.add(m.column());
        }
        cols.add("NSmells");
        return cols;
    }

    public CsvTable run() throws ConfigException, PersistenceException {
        Path out = config.outputDir();
        CsvTable git = CsvTable.read(out.resolve(MetricsDAO.GIT_METRICS_FILE));
        List<CsvTable> others = List.of(
                CsvTable.read(out.resolve(OoMetricsController.CK_METRICS_FILE)),
                CsvTable.read(out.resolve(SonarMetricsController.SONAR_METRICS_FILE)));
        CsvTable result = join(git, others);
        Path file = out.resolve(FEATURES_FILE);
        result.write(file);

        int incomplete = 0;
        for (List<String> row : result.rows()) {
            if (row.contains(CsvTable.MISSING)) {
                incomplete++;
            }
        }
        int rows = result.rows().size();
        int withMissing = incomplete;
        LOG.info(() -> String.format("%nDataset delle feature: %d righe, %d colonne, %d righe con valori mancanti%n"
                + "Scritto in %s%n", rows, result.header().size(), withMissing, file.toAbsolutePath()));
        return result;
    }

    /** Le righe sono quelle di {@code base}; i valori assenti nelle altre tabelle diventano "?". */
    static CsvTable join(CsvTable base, List<CsvTable> others) {
        Map<String, Map<String, String>> values = new HashMap<>();
        index(base, values);
        for (CsvTable t : others) {
            index(t, values);
        }
        List<String> header = new ArrayList<>(List.of("Project", "Class", "ReleaseID"));
        header.addAll(featureColumns());
        CsvTable result = new CsvTable(header);
        int project = base.column("Project");
        int cls = base.column("Class");
        int rel = base.column("ReleaseID");
        for (List<String> row : base.rows()) {
            Map<String, String> v = values.get(key(row.get(cls), row.get(rel)));
            List<String> line = new ArrayList<>(List.of(row.get(project), row.get(cls), row.get(rel)));
            for (String col : featureColumns()) {
                line.add(v.getOrDefault(col, CsvTable.MISSING));
            }
            result.add(line);
        }
        return result;
    }

    private static void index(CsvTable t, Map<String, Map<String, String>> values) {
        int cls = t.column("Class");
        int rel = t.column("ReleaseID");
        for (List<String> row : t.rows()) {
            Map<String, String> v = values.computeIfAbsent(key(row.get(cls), row.get(rel)), k -> new HashMap<>());
            for (int i = 0; i < t.header().size(); i++) {
                v.put(t.header().get(i), row.get(i));
            }
        }
    }

    private static String key(String cls, String release) {
        return cls + "@" + release;
    }
}
