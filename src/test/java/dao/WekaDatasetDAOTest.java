package dao;

import entity.ClassDataset;
import exception.PersistenceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WekaDatasetDAOTest {

    private static CsvTable table() {
        CsvTable t = new CsvTable(List.of("Project", "Class", "ReleaseID", "Size", "CBO", "Churn", "Buggy"));
        t.add(List.of("P", "b/B.java", "2", "10", "3", "-4", "yes"));
        t.add(List.of("P", "a/A.java", "1", "20", "?", "5", "no"));
        t.add(List.of("P", "c/C.java", "2", "30", "1", "0", "no"));
        return t;
    }

    @Test
    void convertsFeaturesMissingValuesAndSortsByRelease() throws PersistenceException {
        ClassDataset d = WekaDatasetDAO.fromTable(table(), "test");
        assertEquals(List.of("Size", "CBO", "Churn"), d.featureNames());
        assertEquals(3, d.size());
        // la riga della release 1 viene prima; l'ordine relativo nella release 2 resta quello del file
        assertEquals("a/A.java", d.className(0));
        assertEquals("b/B.java", d.className(1));
        assertEquals("c/C.java", d.className(2));
        assertEquals(1, d.releaseId(0));
        assertTrue(d.instances().instance(0).isMissing(3));          // CBO della prima riga
        assertEquals(-4, d.instances().instance(1).value(4), 0);   // Churn negativo conservato
        assertEquals(ClassDataset.YES, d.instances().instance(1).stringValue(d.instances().classIndex()));
        assertEquals(2, d.countsPerRelease().get(2)[0]);
        assertEquals(1, d.countsPerRelease().get(2)[1]);
    }

    @Test
    void rejectsInvalidLabels() {
        CsvTable t = new CsvTable(List.of("Project", "Class", "ReleaseID", "Size", "Buggy"));
        t.add(List.of("P", "A.java", "1", "3", "maybe"));
        try {
            WekaDatasetDAO.fromTable(t, "test");
            throw new AssertionError("Attesa PersistenceException");
        } catch (PersistenceException e) {
            assertTrue(e.getMessage().contains("Buggy"));
        }
    }
}