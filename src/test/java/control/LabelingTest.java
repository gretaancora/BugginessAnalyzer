package control;

import dao.CsvTable;
import entity.FileChange;
import entity.JiraVersion;
import entity.Ticket;
import entity.TicketLifecycle;
import entity.TicketLifecycle.IvSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelingTest {

    /** Release 1..4 il 1° febbraio, aprile, giugno, agosto 2020; la "2.0-beta" (esclusa) a marzo. */
    private static ReleaseTimeline timeline() {
        return new ReleaseTimeline(List.of("1.0", "1.1", "2.0", "2.1"),
                List.of(LocalDate.of(2020, 2, 1), LocalDate.of(2020, 4, 1), LocalDate.of(2020, 6, 1),
                        LocalDate.of(2020, 8, 1)),
                List.of(new JiraVersion("9", "2.0-beta", true, LocalDate.of(2020, 3, 1)),
                        new JiraVersion("10", "3.0", false, null)));
    }

    private static OffsetDateTime at(int month, int day) {
        return OffsetDateTime.of(2020, month, day, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private static Ticket ticket(String key, OffsetDateTime created, OffsetDateTime resolved, String... av) {
        return new Ticket(key, created, resolved, List.of(av), List.of());
    }

    @Test
    void timelineMapsDatesAndExcludedVersions() {
        ReleaseTimeline t = timeline();
        assertEquals(Optional.of(1), t.firstOnOrAfter(LocalDate.of(2020, 1, 10)));
        assertEquals(Optional.of(1), t.firstOnOrAfter(LocalDate.of(2020, 2, 1)));   // il giorno stesso
        assertEquals(Optional.of(2), t.firstOnOrAfter(LocalDate.of(2020, 2, 2)));
        assertEquals(Optional.empty(), t.firstOnOrAfter(LocalDate.of(2020, 9, 1)));
        assertEquals(Optional.of(3), t.indexOf("2.0"));
        assertEquals(Optional.of(2), t.indexOf("2.0-beta"));   // marzo -> prima release dopo: 1.1 (aprile)
        assertEquals(Optional.empty(), t.indexOf("3.0"));       // mai rilasciata
    }

    @Test
    void proportionUsesCoherentAffectedVersionsAndEstimatesTheOthers() {
        List<Ticket> tickets = List.of(
                ticket("A", at(3, 1), at(7, 1), "1.0"),   // OV 2, FV 4, IV 1: P = 3/2 = 1.5
                ticket("B", at(5, 1), at(5, 20), "1.1"),  // OV 3, FV 3, IV 2: P = 1/1 = 1 (FV = OV -> denominatore 1)
                ticket("C", at(5, 1), at(7, 1)),          // senza AV: OV 3, FV 4
                ticket("D", at(3, 1), at(5, 1), "2.1"),   // AV incoerente (IV > OV): OV 2, FV 3
                ticket("E", at(9, 1), at(9, 2), "1.0"),   // aperto dopo l'ultima release: escluso
                ticket("F", at(3, 1), null));             // senza risoluzione: escluso
        ProportionCalculator.Result r = new ProportionCalculator(timeline()).compute(tickets);

        assertEquals(1.25, r.p(), 1e-9);
        assertEquals(2, r.ticketsForP());
        assertEquals(2, r.withoutOvOrFv());
        Map<String, TicketLifecycle> byKey = new HashMap<>();
        r.lifecycles().forEach(l -> byKey.put(l.key(), l));
        assertEquals(new TicketLifecycle("A", LocalDate.of(2020, 3, 1), LocalDate.of(2020, 7, 1), 2, 4, 1,
                IvSource.AFFECTED_VERSION), byKey.get("A"));
        assertEquals(2, byKey.get("B").iv());
        // C: IV = round(4 - 1 * 1.25) = 3 (= OV)
        assertEquals(3, byKey.get("C").iv());
        assertEquals(IvSource.PROPORTION_NO_AV, byKey.get("C").ivSource());
        // D: IV = round(3 - 1 * 1.25) = 2
        assertEquals(2, byKey.get("D").iv());
        assertEquals(IvSource.PROPORTION_INCOHERENT_AV, byKey.get("D").ivSource());
    }

    @Test
    void estimateIsRoundedAndClampedBetweenOneAndOv() {
        assertEquals(1, ProportionCalculator.estimate(2, 3, 5.0));   // 3 - 5 = -2 -> 1
        assertEquals(3, ProportionCalculator.estimate(3, 3, 0.2));   // 3 - 0.2 = 2.8 -> 3
        assertEquals(1, ProportionCalculator.estimate(3, 3, 1.87));  // round(3 - 1.87 = 1.13) = 1
    }

    @Test
    void labelsFromIvIncludedToFvExcluded() {
        Map<Integer, Map<String, String>> ids = Map.of(
                1, Map.of("p/A.java", "A", "p/B.java", "B"),
                2, Map.of("p/A.java", "A", "p/B.java", "B"),
                3, Map.of("q/A.java", "A", "p/B.java", "B"));        // A spostata di package nella release 3
        List<TicketLifecycle> lc = List.of(
                new TicketLifecycle("T1", null, null, 3, 4, 2, IvSource.AFFECTED_VERSION),
                new TicketLifecycle("T2", null, null, 2, 2, 2, IvSource.PROPORTION_NO_AV));   // IV = FV: nulla
        BugLabeler.Result r = BugLabeler.label(lc, Map.of("T1", Set.of("A"), "T2", Set.of("B")), ids);

        assertEquals(Set.of("p/A.java"), r.buggyByRelease().get(2));
        assertEquals(Set.of("q/A.java"), r.buggyByRelease().get(3));
        assertTrue(!r.buggyByRelease().containsKey(1));
        assertEquals(2, r.labelsByTicket().get("T1").intValue());
        assertEquals(0, r.ambiguousMatches());
    }

    @Test
    void buggyColumnIsAddedAtTheEnd() {
        CsvTable f = new CsvTable(List.of("Project", "Class", "ReleaseID", "Size"));
        f.add(List.of("P", "p/A.java", "1", "10"));
        f.add(List.of("P", "p/A.java", "2", "12"));
        CsvTable out = LabelingController.addBuggyColumn(f, Map.of(2, Set.of("p/A.java")));
        assertEquals(List.of("Project", "Class", "ReleaseID", "Size", "Buggy"), out.header());
        assertEquals("no", out.rows().get(0).get(4));
        assertEquals("yes", out.rows().get(1).get(4));
    }

    // ---- identita' delle classi ----

    /** Storia lineare c1 -> c2 -> ... : ogni commit e' antenato dei successivi. */
    private static ClassIdentity linear() {
        return new ClassIdentity((a, b) -> Integer.parseInt(a.substring(1)) <= Integer.parseInt(b.substring(1)));
    }

    private static FileChange ren(String from, String to) {
        return new FileChange(from, to, 0, 0);
    }

    @Test
    void renameKeepsIdentity() throws Exception {
        ClassIdentity id = linear();
        id.commit("c2", List.of(ren("a/X.java", "b/X.java")));
        assertEquals(id.find("a/X.java", "c1"), id.find("b/X.java", "c3"));
    }

    @Test
    void reusedPathIsAnotherClass() throws Exception {
        // OPENJPA-240: XMLMetaData -> XMLFieldMetaData, poi XMLMapping -> XMLMetaData
        ClassIdentity id = linear();
        id.commit("c2", List.of(ren("m/XMLMetaData.java", "m/XMLFieldMetaData.java")));
        id.commit("c3", List.of(ren("m/XMLMapping.java", "m/XMLMetaData.java")));
        String oldMeta = id.find("m/XMLMetaData.java", "c1");
        String field = id.find("m/XMLFieldMetaData.java", "c4");
        String newMeta = id.find("m/XMLMetaData.java", "c4");
        assertEquals(oldMeta, field);
        assertEquals(id.find("m/XMLMapping.java", "c1"), newMeta);
        assertTrue(!newMeta.equals(field));
        assertEquals(1, id.reused());
    }

    @Test
    void chainInTheSameCommit() throws Exception {
        ClassIdentity id = linear();
        id.commit("c2", List.of(ren("A.java", "B.java"), ren("B.java", "C.java")));
        assertEquals(id.find("A.java", "c1"), id.find("B.java", "c3"));
        assertEquals(id.find("B.java", "c1"), id.find("C.java", "c3"));
        assertTrue(!id.find("B.java", "c1").equals(id.find("B.java", "c3")));
    }

    @Test
    void parallelBranchesDoNotReusePaths() throws Exception {
        // trunk: t1 -> t2 (rinomina D -> DJ); ramo: t1 -> b1 (copia temporanea tmp/D -> D, operazione SVN)
        Map<String, Set<String>> history = Map.of(
                "t1", Set.of("t1"), "t2", Set.of("t1", "t2"), "b1", Set.of("t1", "b1"));
        ClassIdentity id = new ClassIdentity((a, b) -> history.get(b).contains(a));
        id.commit("t2", List.of(ren("s/D.java", "s/DJ.java")));
        id.commit("b1", List.of(ren("tmp/s/D.java", "s/D.java")));
        assertEquals(0, id.reused());
        assertEquals(id.find("s/D.java", "t1"), id.find("s/D.java", "b1"));
        assertEquals(id.find("s/D.java", "t1"), id.find("s/DJ.java", "t2"));
    }
}
