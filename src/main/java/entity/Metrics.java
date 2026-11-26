package entity;

/**
 * Metriche di una ricetta su un insieme di valutazione; la classe positiva e' "yes" (buggy).
 * Precision vale NaN se nessuna classe e' predetta buggy (0/0), come in Weka.
 */
public record Metrics(double tp, double fp, double tn, double fn, double accuracy, double precision,
                      double recall, double auc, double kappa) {

    /** Nomi delle metriche, nell'ordine di {@link #values()}. */
    public static final String[] NAMES = {"Accuracy", "Precision", "Recall", "AUC", "Kappa"};

    public double[] values() {
        return new double[]{accuracy, precision, recall, auc, kappa};
    }

    public double value(String name) {
        return switch (name) {
            case "Accuracy" -> accuracy;
            case "Precision" -> precision;
            case "Recall" -> recall;
            case "AUC" -> auc;
            case "Kappa" -> kappa;
            default -> throw new IllegalArgumentException("Metrica sconosciuta: " + name);
        };
    }
}