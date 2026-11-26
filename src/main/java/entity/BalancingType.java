package entity;

/** Balancing applicato al training set dopo la feature selection. Ordine: dalla piu' semplice alla piu' complessa. */
public enum BalancingType {
    /** Distribuzione naturale. */
    NONE,
    /** SpreadSubsample: si scartano classi non buggy fino al rapporto 1:1. */
    UNDERSAMPLING,
    /** SMOTE: si generano classi buggy sintetiche fino al rapporto 1:1. */
    SMOTE
}