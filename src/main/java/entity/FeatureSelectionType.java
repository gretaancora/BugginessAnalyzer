package entity;

/** Feature selection applicata al training set. L'ordine va dalla piu' semplice alla piu' complessa. */
public enum FeatureSelectionType {
    /** Tutte le feature. */
    NONE,
    /** Filtro CFS (CfsSubsetEval) con ricerca GreedyStepwise all'indietro. */
    CFS
}