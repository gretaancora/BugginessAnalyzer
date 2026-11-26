package entity;

/**
 * Ricetta completa: configurazione piu' il valore di k (solo per IBk; 0 per gli altri classificatori).
 * E' la procedura che si addestra su un training set e si valuta su un altro insieme.
 */
public record Recipe(ModelConfiguration configuration, int k) {

    public Recipe {
        if (configuration.classifier().hasK() && k < 1) {
            throw new IllegalArgumentException("IBk richiede k >= 1: " + k);
        }
        if (!configuration.classifier().hasK() && k != 0) {
            throw new IllegalArgumentException(configuration.classifier() + " non ha k: " + k);
        }
    }

    /** Ricetta senza k, per RandomForest e NaiveBayes. */
    public static Recipe of(ModelConfiguration configuration) {
        return new Recipe(configuration, 0);
    }

    public ClassifierType classifier() {
        return configuration.classifier();
    }

    public String label() {
        return configuration.label() + (configuration.classifier().hasK() ? "/k=" + k : "");
    }
}