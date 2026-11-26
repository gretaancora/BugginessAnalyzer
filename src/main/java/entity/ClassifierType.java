package entity;

/** Classificatori confrontati nella Milestone 2. L'ordine e' quello usato per spareggiare le scelte. */
public enum ClassifierType {
    RANDOM_FOREST("RandomForest"),
    NAIVE_BAYES("NaiveBayes"),
    IBK("IBk");

    private final String label;

    ClassifierType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Solo IBk ha un iperparametro (k) scelto sul validation set. */
    public boolean hasK() {
        return this == IBK;
    }
}