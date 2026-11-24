package control;

import entity.ClassMetrics;
import entity.Commit;
import entity.FileChange;
import entity.GitMetric;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitMetricsCalculatorTest {

    private static final long WEEK = 7 * 24 * 3600L;
    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
    private static final String A = "mod/src/main/java/p/A.java";
    private static final String B = "mod/src/main/java/p/B.java";
    private static final String TEST = "mod/src/test/java/p/ATest.java";

    private static Instant week(int n) {
        return T0.plusSeconds(n * WEEK);
    }

    private static FileChange ch(String path, int added, int deleted) {
        return new FileChange(null, path, added, deleted);
    }

    private static Map<String, ClassMetrics> byClass(GitMetricsCalculator.Result r) {
        return r.metrics().stream().collect(Collectors.toMap(ClassMetrics::getClassName, Function.identity()));
    }

    private final GitMetricsCalculator calc = new GitMetricsCalculator(p -> p.contains("/src/main/java/"), 2);

    @Test
    void windowAndCumulativeMetrics() {
        List<Commit> history = List.of(
                new Commit("c1", "alice", week(0), List.of(ch(A, 100, 0), ch(B, 50, 0))),
                new Commit("c2", "bob", week(1), List.of(ch(A, 10, 4), ch(TEST, 20, 0))),
                new Commit("c3", "carol", week(2), List.of(ch(A, 6, 2))));
        Set<String> window = Set.of("c2", "c3");
        var res = calc.compute(2, week(4), history, window, Set.of("c3"), List.of(A, B, TEST, "README.txt"));
        Map<String, ClassMetrics> m = byClass(res);

        assertEquals(2, m.size());                       // solo le classi di produzione
        ClassMetrics a = m.get(A);
        assertEquals(2, a.get(GitMetric.NR));
        assertEquals(1, a.get(GitMetric.NFIX));
        assertEquals(2, a.get(GitMetric.NAUTH));
        assertEquals(22, a.get(GitMetric.LOC_TOUCHED));  // (10+4) + (6+2)
        assertEquals(10, a.get(GitMetric.MAX_LOC_ADDED));
        assertEquals(10, a.get(GitMetric.CHURN));        // (10-4) + (6-2)
        assertEquals(0.5, a.get(GitMetric.AVG_CHG_SET)); // c2: 1 altro file .java, c3: 0
        assertEquals(3, a.get(GitMetric.NR_TOTAL));
        assertEquals(1, a.get(GitMetric.NFIX_TOTAL));
        assertEquals(3, a.get(GitMetric.NAUTH_TOTAL));
        assertEquals(122, a.get(GitMetric.LOC_TOUCHED_TOTAL));
        assertEquals(4, a.get(GitMetric.AGE));
        // eta' delle modifiche: 4 settimane x100, 3 x14, 2 x8  -> (400+42+16)/122
        assertEquals(Math.round(458.0 / 122 * 100) / 100.0, a.get(GitMetric.WEIGHTED_AGE));

        ClassMetrics b = m.get(B);
        assertEquals(0, b.get(GitMetric.NR));            // non toccata nella finestra
        assertEquals(1, b.get(GitMetric.NR_TOTAL));
        assertEquals(0, b.get(GitMetric.AVG_CHG_SET));    // media a zero se NR = 0
        assertEquals(4, b.get(GitMetric.WEIGHTED_AGE));
    }

    @Test
    void renameKeepsHistory() {
        String moved = "mod/src/main/java/q/A.java";
        List<Commit> history = List.of(
                new Commit("c1", "alice", week(0), List.of(ch(A, 30, 0))),
                new Commit("c2", "bob", week(1), List.of(new FileChange(A, moved, 2, 1))));
        var res = calc.compute(1, week(2), history, Set.of("c1", "c2"), Set.of(), List.of(moved));
        ClassMetrics m = res.metrics().getFirst();

        assertEquals(moved, m.getClassName());
        assertEquals(2, m.get(GitMetric.NR_TOTAL));
        assertEquals(2, m.get(GitMetric.AGE));           // nata con A, alla settimana 0
    }

    @Test
    void massCommitsAndWhitespaceOnlyChangesAreIgnored() {
        List<FileChange> mass = new ArrayList<>(List.of(ch(A, 5, 5), ch(B, 5, 5)));
        mass.add(ch("mod/src/main/java/p/C.java", 5, 5));
        List<Commit> history = List.of(
                new Commit("c1", "alice", week(0), List.of(ch(A, 10, 0))),
                new Commit("c2", "bob", week(1), mass),                 // 3 classi > soglia 2
                new Commit("c3", "carol", week(2), List.of(ch(A, 0, 0)))); // solo spazi con -w
        var res = calc.compute(1, week(3), history, Set.of("c1", "c2", "c3"), Set.of(), List.of(A, B));
        Map<String, ClassMetrics> m = byClass(res);

        assertEquals(List.of("c2"), res.massCommits());
        assertEquals(1, m.get(A).get(GitMetric.NR_TOTAL));
        assertEquals(10, m.get(A).get(GitMetric.LOC_TOUCHED_TOTAL));
        assertEquals(0, m.get(B).get(GitMetric.NR_TOTAL));
        assertEquals(2, m.get(B).get(GitMetric.AGE));    // nata nel commit di massa: vale per l'eta'
    }

    @Test
    void referenceDateIsLastChangeWhenAfterReleaseDate() {
        List<Commit> history = List.of(new Commit("c1", "alice", week(5), List.of(ch(A, 10, 0))));
        var res = calc.compute(1, week(1), history, Set.of("c1"), Set.of(), List.of(A));
        assertEquals(0, res.metrics().getFirst().get(GitMetric.AGE));
    }
}