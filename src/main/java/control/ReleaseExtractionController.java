package control;

import boundary.GitRepository;
import boundary.JiraVersionsSource;
import config.AppConfig;
import dao.ReleaseDAO;
import entity.ExcludedRelease;
import entity.JiraVersion;
import entity.Release;
import exception.ConfigException;
import exception.GitException;
import exception.JiraException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Passo 1 della milestone: individua le release del progetto con le relative date, le associa al
 * codice su Git e scarta l'ultimo 66%.
 *
 * <p>Output (in {@code output.dir}):
 * <ul>
 *   <li>{@value ReleaseDAO#ALL_RELEASES_FILE}: tutte le release considerate, servono al labeling;</li>
 *   <li>{@value ReleaseDAO#DATASET_RELEASES_FILE}: le prime release, quelle su cui si costruisce il dataset;</li>
 *   <li>{@value ReleaseDAO#EXCLUDED_RELEASES_FILE}: le versioni Jira scartate e il motivo.</li>
 * </ul>
 */
public final class ReleaseExtractionController {

    private static final Logger LOG = Logger.getLogger(ReleaseExtractionController.class.getName());

    private final AppConfig config;

    public ReleaseExtractionController(AppConfig config) {
        this.config = config;
    }

    public record Result(List<Release> all, List<Release> dataset, List<ExcludedRelease> excluded) {
    }

    public Result run() throws ConfigException, JiraException, GitException, PersistenceException {
        List<JiraVersion> versions = JiraVersionsSource.load(config.jiraVersionsSnapshot(), config.jiraProjectKey());
        LOG.info(() -> "Versioni lette da Jira: " + versions.size());

        ReleaseSelector.Selection selection = new ReleaseSelector(config.preReleasePattern()).select(versions);

        GitRepository git = GitRepository.openOrClone(config.gitLocalPath(), config.gitRemoteUrl());
        ReleaseGitMapper.Mapping mapping =
                new ReleaseGitMapper(git, config.tagSuffixes(), config.manualRefs()).map(selection.kept());

        List<ExcludedRelease> excluded = new ArrayList<>(selection.excluded());
        excluded.addAll(mapping.excluded());

        List<Release> all = mapping.releases();
        int datasetSize = ReleaseSelector.datasetSize(all.size(), config.keptPercentage());
        List<Release> dataset = all.subList(0, datasetSize);

        Path out = config.outputDir();
        ReleaseDAO.writeReleases(out.resolve(ReleaseDAO.ALL_RELEASES_FILE), all);
        ReleaseDAO.writeReleases(out.resolve(ReleaseDAO.DATASET_RELEASES_FILE), dataset);
        ReleaseDAO.writeExcluded(out.resolve(ReleaseDAO.EXCLUDED_RELEASES_FILE), excluded);

        report(all, dataset, excluded, out);
        return new Result(all, List.copyOf(dataset), List.copyOf(excluded));
    }

    private void report(List<Release> all, List<Release> dataset, List<ExcludedRelease> excluded, Path out)
            throws ConfigException {
        int pct = config.keptPercentage();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%nVersioni scartate: %d%n", excluded.size()));
        for (ExcludedRelease e : excluded) {
            sb.append(String.format("  - %-14s %s%n", e.name(), e.reason()));
        }
        sb.append(String.format("%nRelease considerate: %d  ->  nel dataset le prime %d (%d%%)%n",
                all.size(), dataset.size(), pct));
        for (Release r : all) {
            sb.append(String.format("  %s %2d  %-7s %s  %-28s %s%n",
                    r.getIndex() <= dataset.size() ? "*" : " ",
                    r.getIndex(), r.getVersion(), r.getReleaseDate(), r.getGitRef(), r.getCommit()));
        }
        sb.append(String.format("%nFile scritti in %s%n", out.toAbsolutePath()));
        LOG.info(sb::toString);
    }
}