package control;

import dao.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Dataset sintetico con la stessa struttura di quello finale, per i test (le feature hanno un po' di segnale). */
final class SyntheticDataset {

    static final List<String> HEADER = List.of("Project", "Class", "ReleaseID", "Size", "Complexity", "CBO",
            "Churn", "NFix", "Buggy");

    private SyntheticDataset() {
    }

    /** {@code releases} release da {@code perRelease} classi; circa il 20% buggy; CBO mancante per qualche riga. */
    static CsvTable table(int releases, int perRelease, long seed) {
        Random rnd = new Random(seed);
        CsvTable t = new CsvTable(HEADER);
        for (int r = releases; r >= 1; r--) {          // release in ordine inverso: il DAO deve riordinarle
            for (int c = 0; c < perRelease; c++) {
                boolean buggy = rnd.nextDouble() < 0.2;
                double scale = buggy ? 3 : 1;
                long size = Math.round(Math.exp(rnd.nextGaussian() + Math.log(50 * scale)));
                long complexity = Math.round(size / 5.0 * (0.5 + rnd.nextDouble()));
                String cbo = rnd.nextDouble() < 0.05 ? CsvTable.MISSING : Long.toString(rnd.nextInt(buggy ? 20 : 8));
                long churn = Math.round(rnd.nextGaussian() * 30 * scale);
                long nfix = buggy ? rnd.nextInt(4) : (rnd.nextDouble() < 0.1 ? 1 : 0);
                t.add(List.of("TEST", "pkg/C" + c + ".java", Integer.toString(r), Long.toString(size),
                        Long.toString(complexity), cbo, Long.toString(churn), Long.toString(nfix),
                        buggy ? "yes" : "no"));
            }
        }
        return t;
    }

    static List<String> features() {
        return new ArrayList<>(HEADER.subList(3, HEADER.size() - 1));
    }
}