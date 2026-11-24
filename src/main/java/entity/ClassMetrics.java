package entity;

import java.util.EnumMap;
import java.util.Map;

/** Valori delle metriche Git di una classe in una release. */
public final class ClassMetrics {

    private final String className;
    private final int releaseId;
    private final Map<GitMetric, Double> values = new EnumMap<>(GitMetric.class);

    public ClassMetrics(String className, int releaseId) {
        this.className = className;
        this.releaseId = releaseId;
        for (GitMetric m : GitMetric.values()) {
            values.put(m, 0.0);
        }
    }

    public String getClassName() {
        return className;
    }

    public int getReleaseId() {
        return releaseId;
    }

    public double get(GitMetric metric) {
        return values.get(metric);
    }

    public void set(GitMetric metric, double value) {
        values.put(metric, value);
    }
}