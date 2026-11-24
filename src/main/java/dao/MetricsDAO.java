package dao;

import entity.ClassMetrics;
import entity.GitMetric;
import exception.PersistenceException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Scrittura del CSV con le metriche Git (una riga per coppia classe-release). */
public final class MetricsDAO {

    public static final String GIT_METRICS_FILE = "git_metrics.csv";

    private MetricsDAO() {
    }

    public static void writeGitMetrics(Path file, String project, List<ClassMetrics> rows) throws PersistenceException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                StringBuilder header = new StringBuilder("Project,Class,ReleaseID");
                for (GitMetric m : GitMetric.values()) {
                    header.append(',').append(m.column());
                }
                w.write(header.toString());
                w.newLine();
                for (ClassMetrics row : rows) {
                    StringBuilder line = new StringBuilder()
                            .append(project).append(',')
                            .append(row.getClassName()).append(',')
                            .append(row.getReleaseId());
                    for (GitMetric m : GitMetric.values()) {
                        line.append(',').append(format(row.get(m)));
                    }
                    w.write(line.toString());
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Impossibile scrivere " + file + ": " + e.getMessage(), e);
        }
    }

    /** Interi senza decimali, gli altri valori con due decimali e il punto come separatore. */
    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}