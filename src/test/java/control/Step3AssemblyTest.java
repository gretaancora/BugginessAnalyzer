package control;

import dao.CsvTable;
import entity.OoMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Step3AssemblyTest {

    private static OoMetrics oo(String file, String cls, String type, int cbo) {
        return new OoMetrics(file, cls, type, cbo, 0, 0, 0, 0);
    }

    @Test
    void onePerFileChoosesTopLevelClassNamedAsFile() {
        String f = "m/src/main/java/p/A.java";
        Map<String, OoMetrics> chosen = OoMetricsController.selectPerFile(List.of(
                oo(f, "p.Helper", "class", 1),          // seconda classe di primo livello nello stesso file
                oo(f, "p.A$Inner", "innerclass", 2),
                oo(f, "p.A$Anonymous1", "anonymous", 3),
                oo(f, "p.A", "class", 4),
                oo("m/src/main/java/p/I.java", "p.I", "interface", 5),
                oo("m/src/main/java/p/X.java", "p.Other", "class", 6)));  // nessuna classe col nome del file
        assertEquals(4, chosen.get(f).cbo());
        assertEquals(5, chosen.get("m/src/main/java/p/I.java").cbo());
        assertEquals(6, chosen.get("m/src/main/java/p/X.java").cbo());
        assertEquals(3, chosen.size());
    }

    @Test
    void sourceDirsFromClasses() {
        assertEquals(List.of("a/src/main/java", "b/c/src/main/java", "src/main/java"),
                SonarMetricsController.sourceDirs(List.of(
                        "b/c/src/main/java/x/Y.java", "a/src/main/java/p/Q.java",
                        "a/src/main/java/p/R.java", "src/main/java/Z.java")));
    }

    @Test
    void joinKeepsBaseRowsAndMarksMissingValues() {
        CsvTable git = new CsvTable(List.of("Project", "Class", "ReleaseID", "NR"));
        git.add(List.of("P", "A.java", "1", "3"));
        git.add(List.of("P", "B.java", "1", "0"));
        CsvTable sonar = new CsvTable(List.of("Project", "Class", "ReleaseID", "Size", "NSmells"));
        sonar.add(List.of("P", "A.java", "1", "120", "7"));
        sonar.add(List.of("P", "A.java", "2", "999", "9"));     // release non presente nella base: ignorata

        CsvTable out = FeatureDatasetController.join(git, List.of(sonar));
        List<String> h = out.header();
        assertEquals(List.of("Project", "Class", "ReleaseID", "Size", "Complexity", "CognitiveComplexity",
                "CBO", "FanIn", "RFC", "LCOM*", "NOC", "NR", "NFix", "NAuth", "LOC_Touched", "MAX_LOC_Added",
                "Churn", "AVG_ChgSet", "NR_Total", "NFix_Total", "NAuth_Total", "LOC_Touched_Total", "Age",
                "WeightedAge", "NSmells"), h);
        assertEquals(2, out.rows().size());
        List<String> a = out.rows().get(0);
        assertEquals("120", a.get(h.indexOf("Size")));
        assertEquals("7", a.get(h.indexOf("NSmells")));
        assertEquals("3", a.get(h.indexOf("NR")));
        assertEquals(CsvTable.MISSING, a.get(h.indexOf("CBO")));
        assertEquals(CsvTable.MISSING, out.rows().get(1).get(h.indexOf("Size")));
    }
}
