package control;

import entity.ClassMetrics;
import entity.Commit;
import entity.FileChange;
import entity.GitMetric;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Calcola le metriche Git delle classi di una release a partire dalla sua storia.
 *
 * <p>La storia va passata dal commit piu' vecchio al piu' recente. Per ogni commit:
 * <ol>
 *   <li>le rinomine spostano la storia accumulata dal vecchio al nuovo percorso;</li>
 *   <li>un percorso mai visto prima nasce in quel commit (data usata per {@code Age});</li>
 *   <li>i commit di massa (piu' di {@code massThreshold} classi modificate) non contano nelle metriche
 *       di processo;</li>
 *   <li>gli altri commit aggiornano le metriche cumulative e, se fanno parte della finestra della
 *       release, anche quelle "nella release".</li>
 * </ol>
 * Si considerano solo i file {@code .java} e solo le modifiche che cambiano almeno una riga
 * (la storia deve essere letta con {@code -w}).
 */
public final class GitMetricsCalculator {

    private static final double SECONDS_PER_WEEK = 7 * 24 * 3600.0;

    private final Predicate<String> isClass;
    private final int massThreshold;

    public GitMetricsCalculator(Predicate<String> isClass, int massThreshold) {
        this.isClass = isClass;
        this.massThreshold = massThreshold;
    }

    public record Result(List<ClassMetrics> metrics, List<String> massCommits, int classesWithoutHistory) {
    }

    /**
     * @param releaseId     indice della release
     * @param releaseDate   data della release (fine giornata)
     * @param history       storia della release, in ordine cronologico
     * @param window        hash dei commit che appartengono alla release (intervallo Git)
     * @param fixCommits    hash dei commit che citano un ticket di bug corretto
     * @param filesAtRelease file presenti nel commit della release
     */
    public Result compute(int releaseId, Instant releaseDate, List<Commit> history, Set<String> window,
                          Set<String> fixCommits, List<String> filesAtRelease) {
        Map<String, Accumulator> byPath = new HashMap<>();
        List<String> massCommits = new ArrayList<>();
        Instant lastChange = Instant.EPOCH;

        for (Commit commit : history) {
            List<FileChange> javaChanges = commit.changes().stream()
                    .filter(c -> c.path().endsWith(".java")).toList();
            for (FileChange c : javaChanges) {
                if (c.isRename()) {
                    Accumulator moved = byPath.remove(c.oldPath());
                    if (moved != null) {
                        byPath.putIfAbsent(c.path(), moved);
                    }
                }
                byPath.computeIfAbsent(c.path(), p -> new Accumulator(commit.time()));
            }

            List<FileChange> counted = javaChanges.stream().filter(FileChange::changesLines).toList();
            if (counted.isEmpty()) {
                continue;
            }
            if (commit.time().isAfter(lastChange)) {
                lastChange = commit.time();
            }
            long classesChanged = counted.stream().filter(c -> isClass.test(c.path())).count();
            if (classesChanged > massThreshold) {
                massCommits.add(commit.hash());
                continue;
            }
            boolean inWindow = window.contains(commit.hash());
            boolean fix = fixCommits.contains(commit.hash());
            int otherFiles = counted.size() - 1;
            for (FileChange c : counted) {
                byPath.get(c.path()).add(commit, c, otherFiles, fix, inWindow);
            }
        }

        Instant reference = releaseDate.isAfter(lastChange) ? releaseDate : lastChange;
        List<ClassMetrics> result = new ArrayList<>();
        int withoutHistory = 0;
        for (String file : filesAtRelease) {
            if (!isClass.test(file)) {
                continue;
            }
            ClassMetrics m = new ClassMetrics(file, releaseId);
            Accumulator acc = byPath.get(file);
            if (acc == null) {
                withoutHistory++;
            } else {
                acc.fill(m, reference);
            }
            result.add(m);
        }
        return new Result(List.copyOf(result), List.copyOf(massCommits), withoutHistory);
    }

    private static double weeksBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).getSeconds()) / SECONDS_PER_WEEK;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Valori accumulati per un percorso, nella finestra e su tutta la storia. */
    private static final class Accumulator {
        private final Instant created;

        private int nr;
        private int nFix;
        private final Set<String> authors = new HashSet<>();
        private long locTouched;
        private int maxLocAdded;
        private long churn;
        private long chgSetSum;

        private int nrTotal;
        private int nFixTotal;
        private final Set<String> authorsTotal = new HashSet<>();
        private long locTouchedTotal;
        /** Somma di (righe toccate x istante del commit, in secondi), per WeightedAge. */
        private double touchedTimeSum;

        Accumulator(Instant created) {
            this.created = created;
        }

        void add(Commit commit, FileChange change, int otherFiles, boolean fix, boolean inWindow) {
            int touched = change.touched();
            nrTotal++;
            authorsTotal.add(commit.author());
            locTouchedTotal += touched;
            touchedTimeSum += (double) touched * commit.time().getEpochSecond();
            if (fix) {
                nFixTotal++;
            }
            if (inWindow) {
                nr++;
                authors.add(commit.author());
                locTouched += touched;
                maxLocAdded = Math.max(maxLocAdded, change.added());
                churn += change.added() - change.deleted();
                chgSetSum += otherFiles;
                if (fix) {
                    nFix++;
                }
            }
        }

        void fill(ClassMetrics m, Instant reference) {
            m.set(GitMetric.NR, nr);
            m.set(GitMetric.NFIX, nFix);
            m.set(GitMetric.NAUTH, authors.size());
            m.set(GitMetric.LOC_TOUCHED, locTouched);
            m.set(GitMetric.MAX_LOC_ADDED, maxLocAdded);
            m.set(GitMetric.CHURN, churn);
            m.set(GitMetric.AVG_CHG_SET, nr == 0 ? 0 : round2((double) chgSetSum / nr));
            m.set(GitMetric.NR_TOTAL, nrTotal);
            m.set(GitMetric.NFIX_TOTAL, nFixTotal);
            m.set(GitMetric.NAUTH_TOTAL, authorsTotal.size());
            m.set(GitMetric.LOC_TOUCHED_TOTAL, locTouchedTotal);
            double age = weeksBetween(created, reference);
            m.set(GitMetric.AGE, round2(age));
            double weighted = age;
            if (locTouchedTotal > 0) {
                double meanChangeTime = touchedTimeSum / locTouchedTotal;
                weighted = Math.max(0, reference.getEpochSecond() - meanChangeTime) / SECONDS_PER_WEEK;
            }
            m.set(GitMetric.WEIGHTED_AGE, round2(weighted));
        }
    }
}