package entity;

/**
 * Metriche di una ricetta su un insieme di valutazione; la classe positiva e' "yes" (buggy).
 * <ul>
 *   <li>Precision vale NaN se nessuna classe e' predetta buggy (0/0), come in Weka.</li>
 *   <li>NPofB10 e NPofB20 sono metriche effort-aware: la quota di classi buggy trovate ispezionando, in ordine di
 *   probabilita' predetta / Size, solo il 10% o il 20% delle righe dell'insieme.</li>
 *   <li>Due riferimenti che non dipendono dal modello e non sono tra le metriche confrontate con i test statistici:
 *   {@code optimalNPofB10/20}, il massimo raggiungibile su quell'insieme (prima le classi buggy, dalla piu'
 *   piccola), e {@code smallestFirstNPofB10/20}, l'ispezione delle classi dalla piu' piccola senza alcun modello.
 *   Siccome NPofBX conta classi e non righe, un modello e' utile solo se supera il secondo.</li>
 * </ul>
 */
public record Metrics(double tp, double fp, double tn, double fn, double accuracy, double precision,
                      double recall, double auc, double kappa, double npofb10, double npofb20,
                      double optimalNPofB10, double optimalNPofB20, double smallestFirstNPofB10,
                      double smallestFirstNPofB20) {

    /** Percentuali di righe ispezionate per NPofBX. */
    public static final int[] NPOFB_LEVELS = {10, 20};

    /** Nomi delle metriche confrontate, nell'ordine di {@link #values()}. */
    public static final String[] NAMES = {"Accuracy", "Precision", "Recall", "AUC", "Kappa", "NPofB10", "NPofB20"};

    public double[] values() {
        return new double[]{accuracy, precision, recall, auc, kappa, npofb10, npofb20};
    }

    public double value(String name) {
        return switch (name) {
            case "Accuracy" -> accuracy;
            case "Precision" -> precision;
            case "Recall" -> recall;
            case "AUC" -> auc;
            case "Kappa" -> kappa;
            case "NPofB10" -> npofb10;
            case "NPofB20" -> npofb20;
            default -> throw new IllegalArgumentException("Metrica sconosciuta: " + name);
        };
    }
}