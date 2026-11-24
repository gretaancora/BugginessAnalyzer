package entity;

/** Metriche calcolate dalla storia Git, nell'ordine delle colonne del CSV. */
public enum GitMetric {
    NR("NR"),
    NFIX("NFix"),
    NAUTH("NAuth"),
    LOC_TOUCHED("LOC_Touched"),
    MAX_LOC_ADDED("MAX_LOC_Added"),
    CHURN("Churn"),
    AVG_CHG_SET("AVG_ChgSet"),
    NR_TOTAL("NR_Total"),
    NFIX_TOTAL("NFix_Total"),
    NAUTH_TOTAL("NAuth_Total"),
    LOC_TOUCHED_TOTAL("LOC_Touched_Total"),
    AGE("Age"),
    WEIGHTED_AGE("WeightedAge");

    private final String column;

    GitMetric(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }
}