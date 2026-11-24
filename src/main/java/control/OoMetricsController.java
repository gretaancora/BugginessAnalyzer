package control;

import boundary.CkAnalyzer;
import boundary.GitRepository;
import config.AppConfig;
import dao.CsvTable;
import dao.ReleaseDAO;
import entity.OoMetrics;
import entity.Release;
import exception.AnalysisException;
import exception.ConfigException;
import exception.GitException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Passo 3, parte CK: metriche OO (CBO, FanIn, RFC, LCOM*, NOC) di ogni classe delle release del dataset.
 * Scrive {@value #CK_METRICS_FILE}; le classi che CK non riesce ad analizzare hanno valori mancanti ("?").
 */
public final class OoMetricsController {

    public static final String CK_METRICS_FILE = "ck_metrics.csv";

    private static final Logger LOG = Logger.getLogger(OoMetricsController.class.getName());

    private final AppConfig config;

    public OoMetricsController(AppConfig config) {
        this.config = config;
    }

    public CsvTable run() throws ConfigException, GitException, PersistenceException, AnalysisException {
        Path out = config.outputDir();
        List<Release> dataset = ReleaseDAO.readReleases(out.resolve(ReleaseDAO.DATASET_RELEASES_FILE));
        GitRepository git = GitRepository.openOrClone(config.gitLocalPath(), config.gitRemoteUrl());
        ReleaseWorkspace workspace = new ReleaseWorkspace(git, config);
        CkAnalyzer ck = new CkAnalyzer();

        List<String> header = new ArrayList<>(List.of("Project", "Class", "ReleaseID"));
        header.addAll(List.of(OoMetrics.COLUMNS));
        CsvTable table = new CsvTable(header);
        StringBuilder report = new StringBuilder(String.format("%nMetriche CK per release:%n"));

        for (Release r : dataset) {
            Path dir = workspace.sources(r);
            List<String> classes = workspace.classes(r);
            CkAnalyzer.Result res = ck.analyze(dir, classes);
            Map<String, OoMetrics> perFile = selectPerFile(res.classes());
            int missing = 0;
            for (String c : classes) {
                OoMetrics m = perFile.get(c);
                List<String> row = new ArrayList<>(List.of(config.projectName(), c, String.valueOf(r.getIndex())));
                if (m == null) {
                    missing++;
                    for (int i = 0; i < OoMetrics.COLUMNS.length; i++) {
                        row.add(CsvTable.MISSING);
                    }
                } else {
                    row.add(String.valueOf(m.cbo()));
                    row.add(String.valueOf(m.fanIn()));
                    row.add(String.valueOf(m.rfc()));
                    row.add(String.format(Locale.ROOT, "%.3f", m.lcomStar()));
                    row.add(String.valueOf(m.noc()));
                }
                table.add(row);
            }
            report.append(String.format("  %2d %-7s classi=%5d  senza metriche=%d  errori CK=%d%n",
                    r.getIndex(), r.getVersion(), classes.size(), missing, res.filesWithErrors().size()));
        }
        Path file = out.resolve(CK_METRICS_FILE);
        table.write(file);
        report.append(String.format("Righe scritte: %d in %s%n", table.rows().size(), file.toAbsolutePath()));
        LOG.info(report::toString);
        return table;
    }

    /**
     * Una riga per file: la classe di primo livello con lo stesso nome del file; se non c'e', la prima
     * classe di primo livello del file. Le classi annidate e anonime non hanno una riga propria: il
     * dataset ha una riga per file e la classe che lo rappresenta e' quella di primo livello.
     */
    static Map<String, OoMetrics> selectPerFile(List<OoMetrics> classes) {
        Map<String, OoMetrics> chosen = new HashMap<>();
        for (OoMetrics m : classes) {
            if (!m.isTopLevel()) {
                continue;
            }
            String fileName = m.file().substring(m.file().lastIndexOf('/') + 1);
            boolean matchesFile = fileName.equals(m.simpleName() + ".java");
            OoMetrics current = chosen.get(m.file());
            boolean currentMatches = current != null
                    && fileName.equals(current.simpleName() + ".java");
            if (current == null || (matchesFile && !currentMatches)) {
                chosen.put(m.file(), m);
            }
        }
        return chosen;
    }
}
