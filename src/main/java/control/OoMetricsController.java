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

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Passo 3, parte CK: metriche OO (CBO, FanIn, RFC, LCOM*, NOC) di ogni classe delle release del dataset.
 * Scrive {@value #CK_METRICS_FILE}. Le annotazioni ({@code @interface}), che CK non analizza, hanno RFC, LCOM* e
 * NOC = 0 e CBO, Fan-In mancanti ("?"); le altre classi che CK non riesce ad analizzare hanno tutto mancante.
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

        LOG.info(() -> String.format("CK: %d release da analizzare, memoria massima della JVM %d MB",
                dataset.size(), Runtime.getRuntime().maxMemory() >> 20));
        for (Release r : dataset) {
            List<String> classes = workspace.classes(r);
            LOG.info(() -> String.format("CK: release %d/%d (%s), %d classi...",
                    r.getIndex(), dataset.size(), r.getVersion(), classes.size()));
            long start = System.nanoTime();
            Path dir = workspace.classSources(r);
            CkAnalyzer.Result res = ck.analyze(dir, classes);
            long seconds = (System.nanoTime() - start) / 1_000_000_000L;
            LOG.info(() -> String.format("CK: %s completata in %d s (%d classi lette, %d file con errori)",
                    r.getVersion(), seconds, res.classes().size(), res.filesWithErrors().size()));
            Map<String, OoMetrics> perFile = selectPerFile(res.classes());
            int missing = 0;
            int annotations = 0;
            for (String c : classes) {
                OoMetrics m = perFile.get(c);
                List<String> row = new ArrayList<>(List.of(config.projectName(), c, String.valueOf(r.getIndex())));
                if (m == null && isAnnotationDeclaration(readSource(dir.resolve(c)), c)) {
                    // CK 0.7.0 non analizza le dichiarazioni @interface: RFC, LCOM* e NOC valgono 0 per
                    // definizione (nessuna chiamata, nessun attributo, non estendibili); CBO e Fan-In non si
                    // possono ricavare senza CK e restano mancanti
                    annotations++;
                    row.addAll(List.of(CsvTable.MISSING, CsvTable.MISSING, "0", "0.000", "0"));
                } else if (m == null) {
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
            report.append(String.format("  %2d %-7s classi=%5d  annotazioni=%d  senza metriche=%d  errori CK=%d%n",
                    r.getIndex(), r.getVersion(), classes.size(), annotations, missing,
                    res.filesWithErrors().size()));
        }
        Path file = out.resolve(CK_METRICS_FILE);
        table.write(file);
        report.append(String.format("Righe scritte: %d in %s%n", table.rows().size(), file.toAbsolutePath()));
        LOG.info(report::toString);
        return table;
    }

    private static String readSource(Path file) throws AnalysisException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                throw new AnalysisException("Impossibile leggere " + file + ": " + e2.getMessage(), e2);
            }
        } catch (IOException e) {
            throw new AnalysisException("Impossibile leggere " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code true} se il file dichiara un'annotazione ({@code @interface}) con lo stesso nome del file.
     * I commenti vengono ignorati, cosi' un {@code @interface} citato nella Javadoc non conta.
     */
    static boolean isAnnotationDeclaration(String source, String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String simpleName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        String code = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        return Pattern.compile("@\\s*interface\\s+" + Pattern.quote(simpleName) + "\\b").matcher(code).find();
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
