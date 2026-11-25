package control;

import boundary.GitRepository;
import boundary.JiraTicketsSource;
import boundary.JiraVersionsSource;
import config.AppConfig;
import dao.CsvTable;
import dao.ReleaseDAO;
import entity.Commit;
import entity.FileChange;
import entity.Release;
import entity.Ticket;
import entity.TicketLifecycle;
import exception.ConfigException;
import exception.GitException;
import exception.JiraException;
import exception.PersistenceException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Passo 4: labeling. Determina il ciclo di vita dei bug (OV, FV, IV con Proportion), trova le classi
 * modificate dai commit di fix e aggiunge a {@value FeatureDatasetController#FEATURES_FILE} la colonna
 * Buggy (yes/no), producendo il dataset finale. Usa tutte le release e tutti i ticket, non solo le release
 * del dataset: un bug introdotto nelle prime release puo' essere scoperto e corretto molto dopo.
 */
public final class LabelingController {

    public static final String TICKETS_FILE = "tickets_lifecycle.csv";
    public static final String BUGGY_COLUMN = "Buggy";

    private static final Logger LOG = Logger.getLogger(LabelingController.class.getName());

    private final AppConfig config;

    public LabelingController(AppConfig config) {
        this.config = config;
    }

    public String datasetFileName() throws ConfigException {
        return config.projectName() + "_dataset.csv";
    }

    public CsvTable run() throws ConfigException, JiraException, GitException, PersistenceException {
        Path out = config.outputDir();
        List<Release> all = ReleaseDAO.readReleases(out.resolve(ReleaseDAO.ALL_RELEASES_FILE));
        List<Release> dataset = ReleaseDAO.readReleases(out.resolve(ReleaseDAO.DATASET_RELEASES_FILE));
        GitRepository git = GitRepository.openOrClone(config.gitLocalPath(), config.gitRemoteUrl());
        ReleaseWorkspace workspace = new ReleaseWorkspace(git, config);
        Predicate<String> isClass = workspace.isClass();

        // 1. sequenza delle release con le date di riferimento
        List<String> versions = new ArrayList<>();
        List<LocalDate> refDates = new ArrayList<>();
        for (Release r : all) {
            LocalDate ref = r.getReleaseDate();
            LocalDate last = git.lastChangeTime(r.getCommit()).map(i -> i.atOffset(ZoneOffset.UTC).toLocalDate())
                    .orElse(ref);
            versions.add(r.getVersion());
            refDates.add(last.isAfter(ref) ? last : ref);
        }
        ReleaseTimeline timeline = new ReleaseTimeline(versions, refDates,
                JiraVersionsSource.load(config.jiraVersionsSnapshot(), config.jiraProjectKey()));

        // 2. ciclo di vita dei ticket
        List<Ticket> tickets = JiraTicketsSource.load(config.jiraTicketsSnapshot(), config.jiraTicketsJql());
        ProportionCalculator.Result prop = new ProportionCalculator(timeline).compute(tickets);
        Set<String> keys = new HashSet<>();
        for (TicketLifecycle t : prop.lifecycles()) {
            keys.add(t.key());
        }

        // 3. commit di fix (tutti i rami) e rinomine
        List<Commit> history = git.history("--all");
        Map<String, String> messages = git.messages("--all");
        FixCommitLinker linker = new FixCommitLinker(config.jiraProjectKey());
        ClassIdentity identity = new ClassIdentity(git::isAncestor);
        Map<String, List<Commit>> fixesByTicket = new HashMap<>();
        Map<String, Integer> fixCommits = new HashMap<>();
        int massLinked = 0;
        for (Commit c : history) {
            identity.commit(c.hash(), c.changes().stream()
                    .filter(ch -> ch.isRename() && ch.path().endsWith(".java")).toList());
            Set<String> cited = linker.keysIn(messages.getOrDefault(c.hash(), ""));
            cited.retainAll(keys);
            if (cited.isEmpty()) {
                continue;
            }
            long classes = c.changes().stream()
                    .filter(FileChange::changesLines).map(FileChange::path).filter(isClass).count();
            if (classes > config.massCommitThreshold()) {
                massLinked++;
                continue;
            }
            for (String k : cited) {
                fixCommits.merge(k, 1, Integer::sum);
                fixesByTicket.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            }
        }
        // identita' delle classi toccate, nella storia del commit di fix (dopo aver registrato tutte le rinomine)
        Map<String, Set<String>> touched = new HashMap<>();
        Map<String, Set<String>> touchedPaths = new HashMap<>();
        for (Map.Entry<String, List<Commit>> e : fixesByTicket.entrySet()) {
            for (Commit c : e.getValue()) {
                for (FileChange ch : c.changes()) {
                    if (ch.changesLines() && isClass.test(ch.path())) {
                        touched.computeIfAbsent(e.getKey(), x -> new HashSet<>()).add(identity.find(ch.path(), c.hash()));
                        touchedPaths.computeIfAbsent(e.getKey(), x -> new HashSet<>()).add(ch.path());
                    }
                }
            }
        }

        // 4. etichette sulle release del dataset
        Map<Integer, Map<String, String>> identitiesByRelease = new HashMap<>();
        for (Release r : dataset) {
            Map<String, String> ids = new HashMap<>();
            for (String cls : workspace.classes(r)) {
                ids.put(cls, identity.find(cls, r.getCommit()));
            }
            identitiesByRelease.put(r.getIndex(), ids);
        }
        BugLabeler.Result labels = BugLabeler.label(prop.lifecycles(), touched, identitiesByRelease);

        // 5. dataset finale e ciclo di vita dei ticket
        CsvTable features = CsvTable.read(out.resolve(FeatureDatasetController.FEATURES_FILE));
        CsvTable result = addBuggyColumn(features, labels.buggyByRelease());
        Path datasetFile = out.resolve(datasetFileName());
        result.write(datasetFile);
        Path ticketsFile = out.resolve(TICKETS_FILE);
        ticketsTable(prop.lifecycles(), timeline, fixCommits, touchedPaths, labels.labelsByTicket()).write(ticketsFile);

        LOG.info(report(prop, timeline, dataset, result, fixCommits, touchedPaths, labels, identity, massLinked,
                tickets.size(), datasetFile, ticketsFile));
        return result;
    }

    /** Aggiunge Buggy (yes/no) in fondo: yes se la coppia (classe, release) e' tra quelle etichettate. */
    static CsvTable addBuggyColumn(CsvTable features, Map<Integer, Set<String>> buggyByRelease) {
        List<String> header = new ArrayList<>(features.header());
        header.add(BUGGY_COLUMN);
        CsvTable result = new CsvTable(header);
        int cls = features.column("Class");
        int rel = features.column("ReleaseID");
        for (List<String> row : features.rows()) {
            List<String> line = new ArrayList<>(row);
            boolean buggy = buggyByRelease.getOrDefault(Integer.parseInt(row.get(rel)), Set.of()).contains(row.get(cls));
            line.add(buggy ? "yes" : "no");
            result.add(line);
        }
        return result;
    }

    private static CsvTable ticketsTable(List<TicketLifecycle> lifecycles, ReleaseTimeline timeline,
                                         Map<String, Integer> fixCommits, Map<String, Set<String>> touched,
                                         Map<String, Integer> labelsByTicket) {
        CsvTable t = new CsvTable(List.of("Ticket", "Created", "Resolved", "OV", "FV", "IV", "IVSource",
                "OVVersion", "FVVersion", "IVVersion", "FixCommits", "ClassesTouched", "DatasetLabels"));
        for (TicketLifecycle l : lifecycles) {
            t.add(List.of(l.key(), l.created().toString(), l.resolved().toString(),
                    String.valueOf(l.ov()), String.valueOf(l.fv()), String.valueOf(l.iv()), l.ivSource().name(),
                    timeline.version(l.ov()), timeline.version(l.fv()), timeline.version(l.iv()),
                    String.valueOf(fixCommits.getOrDefault(l.key(), 0)),
                    String.valueOf(touched.getOrDefault(l.key(), Set.of()).size()),
                    String.valueOf(labelsByTicket.getOrDefault(l.key(), 0))));
        }
        return t;
    }

    private static String report(ProportionCalculator.Result prop, ReleaseTimeline timeline, List<Release> dataset,
                                 CsvTable result, Map<String, Integer> fixCommits, Map<String, Set<String>> touched,
                                 BugLabeler.Result labels, ClassIdentity identity, int massLinked, int ticketCount,
                                 Path datasetFile, Path ticketsFile) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%nLabeling%n"));
        Map<TicketLifecycle.IvSource, Integer> bySource = new EnumMap<>(TicketLifecycle.IvSource.class);
        int sameRelease = 0;
        for (TicketLifecycle l : prop.lifecycles()) {
            bySource.merge(l.ivSource(), 1, Integer::sum);
            if (l.ov() == l.fv()) {
                sameRelease++;
            }
        }
        sb.append(String.format(Locale.ROOT, "  ticket: %d, con OV e FV: %d (esclusi %d: aperti o risolti dopo "
                + "l'ultima release)%n", ticketCount, prop.lifecycles().size(), prop.withoutOvOrFv()));
        sb.append(String.format(Locale.ROOT, "  aperti e corretti durante la stessa release (FV = OV): %d%n", sameRelease));
        sb.append(String.format(Locale.ROOT, "  IV da affected version: %d | stimata con Proportion: %d senza AV, "
                        + "%d con AV incoerenti%n",
                bySource.getOrDefault(TicketLifecycle.IvSource.AFFECTED_VERSION, 0),
                bySource.getOrDefault(TicketLifecycle.IvSource.PROPORTION_NO_AV, 0),
                bySource.getOrDefault(TicketLifecycle.IvSource.PROPORTION_INCOHERENT_AV, 0)));
        sb.append(String.format(Locale.ROOT, "  P = %.3f (media su %d ticket con AV coerenti)%n", prop.p(),
                prop.ticketsForP()));
        long withFix = prop.lifecycles().stream().filter(l -> fixCommits.containsKey(l.key())).count();
        long withClasses = prop.lifecycles().stream()
                .filter(l -> !touched.getOrDefault(l.key(), Set.of()).isEmpty()).count();
        long labelling = labels.labelsByTicket().values().stream().filter(v -> v > 0).count();
        sb.append(String.format(Locale.ROOT, "  ticket con commit di fix: %d, che toccano classi: %d, che etichettano "
                + "classi del dataset: %d%n", withFix, withClasses, labelling));
        sb.append(String.format(Locale.ROOT, "  rinomine di file .java: %d (di cui verso un percorso gia' usato da un'altra classe: %d) | "
                        + "identita' ambigue nelle release: %d | commit di massa citati da ticket (ignorati): %d%n",
                identity.renames(), identity.reused(), labels.ambiguousMatches(), massLinked));
        sb.append(String.format(Locale.ROOT, "  classi buggy per release:%n"));
        int cls = result.column("ReleaseID");
        int buggyCol = result.column(BUGGY_COLUMN);
        int totalYes = 0;
        for (Release r : dataset) {
            int n = 0;
            int yes = 0;
            for (List<String> row : result.rows()) {
                if (Integer.parseInt(row.get(cls)) == r.getIndex()) {
                    n++;
                    if ("yes".equals(row.get(buggyCol))) {
                        yes++;
                    }
                }
            }
            totalYes += yes;
            sb.append(String.format(Locale.ROOT, "    %2d %-7s (%s) %5d classi, buggy %4d (%4.1f%%)%n", r.getIndex(),
                    r.getVersion(), timeline.date(r.getIndex()), n, yes, n == 0 ? 0 : 100.0 * yes / n));
        }
        int rows = result.rows().size();
        sb.append(String.format(Locale.ROOT, "  totale: %d righe, buggy %d (%.1f%%)%n", rows, totalYes,
                rows == 0 ? 0 : 100.0 * totalYes / rows));
        sb.append(String.format(Locale.ROOT, "Scritti %s e %s%n", datasetFile.toAbsolutePath(), ticketsFile.toAbsolutePath()));
        return sb.toString();
    }
}
