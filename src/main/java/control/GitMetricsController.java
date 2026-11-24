package control;

import boundary.GitRepository;
import boundary.JiraTicketsSource;
import config.AppConfig;
import dao.MetricsDAO;
import dao.ReleaseDAO;
import entity.ClassMetrics;
import entity.Commit;
import entity.GitMetric;
import entity.Release;
import entity.Ticket;
import exception.ConfigException;
import exception.GitException;
import exception.JiraException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Passo 3, parte Git: per ogni release del dataset calcola le metriche di processo di ogni classe
 * e scrive {@value MetricsDAO#GIT_METRICS_FILE}. Richiede l'output del passo 1.
 */
public final class GitMetricsController {

    private static final Logger LOG = Logger.getLogger(GitMetricsController.class.getName());

    private final AppConfig config;

    public GitMetricsController(AppConfig config) {
        this.config = config;
    }

    public List<ClassMetrics> run() throws ConfigException, JiraException, GitException, PersistenceException {
        Path out = config.outputDir();
        List<Release> dataset = ReleaseDAO.readReleases(out.resolve(ReleaseDAO.DATASET_RELEASES_FILE));

        List<Ticket> tickets = JiraTicketsSource.load(config.jiraTicketsSnapshot(), config.jiraTicketsJql());
        Set<String> bugKeys = tickets.stream().map(Ticket::key).collect(Collectors.toSet());
        LOG.info(() -> "Ticket di bug corretti: " + bugKeys.size());
        if (bugKeys.isEmpty()) {
            LOG.warning("Nessun ticket di bug: NFix e NFix_Total saranno zero");
        }

        GitRepository git = GitRepository.openOrClone(config.gitLocalPath(), config.gitRemoteUrl());
        Pattern include = config.classesInclude();
        Pattern exclude = config.classesExclude();
        Predicate<String> isClass = p -> include.matcher(p).matches() && !exclude.matcher(p).matches();
        GitMetricsCalculator calculator = new GitMetricsCalculator(isClass, config.massCommitThreshold());
        FixCommitLinker linker = new FixCommitLinker(config.jiraProjectKey());

        List<ClassMetrics> all = new ArrayList<>();
        Set<String> massCommits = new LinkedHashSet<>();
        StringBuilder report = new StringBuilder(String.format("%nMetriche Git per release:%n"));
        Release previous = null;
        for (Release r : dataset) {
            List<Commit> history = git.history(r.getCommit());
            String range = previous == null ? r.getCommit() : previous.getCommit() + ".." + r.getCommit();
            Set<String> window = git.revList(range);
            Map<String, String> messages = git.messages(r.getCommit());
            Set<String> fixes = linker.fixCommits(messages, bugKeys);
            Instant releaseEnd = r.getReleaseDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

            GitMetricsCalculator.Result res = calculator.compute(r.getIndex(), releaseEnd, history, window,
                    fixes, git.listFiles(r.getCommit()));
            all.addAll(res.metrics());
            massCommits.addAll(res.massCommits());

            long touched = res.metrics().stream().filter(m -> m.get(GitMetric.NR) > 0).count();
            report.append(String.format("  %2d %-7s classi=%5d  toccate nella finestra=%5d  commit nella finestra=%5d"
                            + "  senza storia=%d%n",
                    r.getIndex(), r.getVersion(), res.metrics().size(), touched, window.size(),
                    res.classesWithoutHistory()));
            previous = r;
        }
        report.append(String.format("Commit di massa esclusi dalle metriche di processo: %d%n", massCommits.size()));
        for (String h : massCommits) {
            report.append("  ").append(h).append(System.lineSeparator());
        }

        Path file = out.resolve(MetricsDAO.GIT_METRICS_FILE);
        MetricsDAO.writeGitMetrics(file, config.projectName(), all);
        report.append(String.format("Righe scritte: %d in %s%n", all.size(), file.toAbsolutePath()));
        LOG.info(report::toString);
        return all;
    }
}