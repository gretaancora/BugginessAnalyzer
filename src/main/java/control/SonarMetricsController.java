package control;

import boundary.GitRepository;
import boundary.SonarCloudClient;
import boundary.SonarScanner;
import config.AppConfig;
import dao.CsvTable;
import dao.ReleaseDAO;
import entity.Release;
import exception.AnalysisException;
import exception.ConfigException;
import exception.GitException;
import exception.PersistenceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Passo 3, parte SonarCloud: un progetto per release ({@code <org>_<prefisso>-<versione>}), analisi con
 * SonarScanner sul codice estratto e lettura delle misure per file. Scrive {@value #SONAR_METRICS_FILE}.
 *
 * <p>Le release gia' analizzate non vengono rianalizzate (salvo {@code sonar.reanalyze=true}), quindi
 * il passo si puo' rilanciare dopo un'interruzione.
 */
public final class SonarMetricsController {

    public static final String SONAR_METRICS_FILE = "sonar_metrics.csv";

    /** Metrica SonarCloud -> colonna del dataset. */
    static final Map<String, String> METRICS = new LinkedHashMap<>();

    static {
        METRICS.put("ncloc", "Size");
        METRICS.put("complexity", "Complexity");
        METRICS.put("cognitive_complexity", "CognitiveComplexity");
        METRICS.put("code_smells", "NSmells");
    }

    private static final String EMPTY_BINARIES = ".sonar-empty-binaries";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Logger LOG = Logger.getLogger(SonarMetricsController.class.getName());

    private final AppConfig config;

    public SonarMetricsController(AppConfig config) {
        this.config = config;
    }

    public CsvTable run() throws ConfigException, GitException, PersistenceException, AnalysisException {
        Path out = config.outputDir();
        List<Release> dataset = ReleaseDAO.readReleases(out.resolve(ReleaseDAO.DATASET_RELEASES_FILE));
        GitRepository git = GitRepository.openOrClone(config.gitLocalPath(), config.gitRemoteUrl());
        ReleaseWorkspace workspace = new ReleaseWorkspace(git, config);
        AppConfig.Secret secret = config.sonarSecret();
        String token = secret.value();
        LOG.info(() -> "Token SonarCloud letto da " + secret.origin() + ": " + secret.masked());
        String scannerCommand = config.sonarScannerCommand();
        if (config.sonarScannerMode() == SonarScanner.Mode.CLI) {
            LOG.info(() -> "sonar-scanner: " + scannerCommand);
        }
        SonarScanner scanner = new SonarScanner(config.sonarScannerMode(), scannerCommand,
                config.sonarDockerImage());

        List<String> header = new ArrayList<>(List.of("Project", "Class", "ReleaseID"));
        header.addAll(METRICS.values());
        CsvTable table = new CsvTable(header);
        StringBuilder report = new StringBuilder(String.format("%nMetriche SonarCloud per release:%n"));

        try (SonarCloudClient sonar = new SonarCloudClient(config.sonarHostUrl(), config.sonarOrganization(), token)) {
            if (!sonar.tokenIsValid()) {
                throw new AnalysisException("SonarCloud non riconosce il token letto da " + secret.origin() + " ("
                        + secret.masked() + "). Controlla che sia il token completo generato in My Account > "
                        + "Security su " + config.sonarHostUrl() + ", che non sia scaduto o revocato e, se e' "
                        + "impostata, che la variabile d'ambiente SONAR_TOKEN non contenga un token diverso.");
            }
            LOG.info("Token SonarCloud valido");
            for (Release r : dataset) {
                String key = projectKey(r);
                boolean created = sonar.ensureProject(key, "OpenJPA " + r.getVersion() + " (BugginessAnalyzer)");
                List<String> classes = workspace.classes(r);
                String status;
                if (!config.sonarReanalyze() && !created && sonar.hasAnalysis(key)) {
                    status = "gia' analizzata";
                } else {
                    LOG.info(() -> "Analisi SonarCloud di " + r.getVersion() + " (" + key + ")...");
                    Path dir = workspace.sources(r);
                    String taskId = scanner.run(dir, scannerProperties(r, key, dir, classes), token);
                    waitFor(sonar, taskId);
                    status = "analizzata";
                }
                Map<String, Map<String, String>> measures = sonar.fileMeasures(key, List.copyOf(METRICS.keySet()));
                int missing = 0;
                for (String c : classes) {
                    Map<String, String> m = measures.get(c);
                    if (m == null) {
                        missing++;
                    }
                    List<String> row = new ArrayList<>(List.of(config.projectName(), c, String.valueOf(r.getIndex())));
                    for (String metric : METRICS.keySet()) {
                        row.add(m == null ? CsvTable.MISSING : m.getOrDefault(metric, CsvTable.MISSING));
                    }
                    table.add(row);
                }
                report.append(String.format("  %2d %-7s %-40s %-16s classi=%5d  senza misure=%d%n",
                        r.getIndex(), r.getVersion(), key, status, classes.size(), missing));
            }
        }
        Path file = out.resolve(SONAR_METRICS_FILE);
        table.write(file);
        report.append(String.format("Righe scritte: %d in %s%n", table.rows().size(), file.toAbsolutePath()));
        LOG.info(report::toString);
        return table;
    }

    private String projectKey(Release r) throws ConfigException {
        return config.sonarOrganization() + "_" + config.sonarProjectPrefix() + "-" + r.getVersion();
    }

    private Map<String, String> scannerProperties(Release r, String key, Path dir, List<String> classes)
            throws ConfigException, AnalysisException {
        try {
            Files.createDirectories(dir.resolve(EMPTY_BINARIES));
        } catch (IOException e) {
            throw new AnalysisException("Impossibile creare " + EMPTY_BINARIES + ": " + e.getMessage(), e);
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sonar.host.url", config.sonarHostUrl());
        p.put("sonar.organization", config.sonarOrganization());
        p.put("sonar.projectKey", key);
        p.put("sonar.projectName", "OpenJPA " + r.getVersion() + " (BugginessAnalyzer)");
        p.put("sonar.projectVersion", r.getVersion());
        p.put("sonar.sources", String.join(",", sourceDirs(classes)));
        p.put("sonar.inclusions", "**/*.java");
        p.put("sonar.java.binaries", EMPTY_BINARIES);
        p.put("sonar.sourceEncoding", config.sonarSourceEncoding());
        p.put("sonar.scm.disabled", "true");
        return p;
    }

    /** Cartelle sorgente delle classi analizzate, es. "openjpa-kernel/src/main/java". */
    static List<String> sourceDirs(List<String> classes) {
        TreeSet<String> dirs = new TreeSet<>();
        for (String c : classes) {
            int i = c.indexOf("src/main/java/");
            if (i >= 0) {
                dirs.add(c.substring(0, i + "src/main/java".length()));
            }
        }
        return List.copyOf(dirs);
    }

    private void waitFor(SonarCloudClient sonar, String taskId) throws AnalysisException, ConfigException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(config.sonarTaskTimeoutMinutes()));
        while (Instant.now().isBefore(deadline)) {
            String status = sonar.taskStatus(taskId);
            switch (status) {
                case "SUCCESS" -> {
                    return;
                }
                case "FAILED", "CANCELED" -> throw new AnalysisException(
                        "Elaborazione SonarCloud " + taskId + " terminata con stato " + status);
                default -> sleep();
            }
        }
        throw new AnalysisException("Elaborazione SonarCloud " + taskId + " non terminata entro il timeout");
    }

    private static void sleep() throws AnalysisException {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("Attesa dell'elaborazione SonarCloud interrotta", e);
        }
    }
}
