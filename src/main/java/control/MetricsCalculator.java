package control;

import entity.Metrics;
import entity.Predictions;

/**
 * Metriche di una predizione con classe positiva "yes", calcolate con le stesse definizioni di Weka
 * (Evaluation): Kappa di Cohen sulla matrice di confusione, AUC come area sotto la curva ROC con i pareggi
 * di probabilita' contati a meta' (statistica di Mann-Whitney). I pesi servono al bootstrap: il peso di una
 * riga e' il numero di volte in cui compare nel ricampionamento.
 */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static Metrics compute(Predictions p) {
        return compute(p, null);
    }

    public static Metrics compute(Predictions p, double[] weights) {
        double tp = 0;
        double fp = 0;
        double tn = 0;
        double fn = 0;
        for (int i = 0; i < p.size(); i++) {
            double w = weights == null ? 1 : weights[i];
            if (w == 0) {
                continue;
            }
            if (p.actual(i)) {
                if (p.predicted(i)) {
                    tp += w;
                } else {
                    fn += w;
                }
            } else if (p.predicted(i)) {
                fp += w;
            } else {
                tn += w;
            }
        }
        double total = tp + fp + tn + fn;
        double accuracy = (tp + tn) / total;
        double precision = tp / (tp + fp);
        double recall = tp / (tp + fn);
        return new Metrics(tp, fp, tn, fn, accuracy, precision, recall, auc(p, weights),
                kappa(tp, fp, tn, fn));
    }

    /** Kappa di Cohen, come Evaluation.kappa() di Weka (1 se l'accordo atteso per caso e' totale). */
    static double kappa(double tp, double fp, double tn, double fn) {
        double total = tp + fp + tn + fn;
        double observed = (tp + tn) / total;
        double chance = ((tp + fn) * (tp + fp) + (tn + fp) * (tn + fn)) / (total * total);
        return chance < 1 ? (observed - chance) / (1 - chance) : 1;
    }

    /** AUC con i pareggi contati a meta'; NaN se manca una delle due classi. */
    static double auc(Predictions p, double[] weights) {
        int[] order = p.ascendingOrder();
        double positives = 0;
        double negativesBelow = 0;
        double sum = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            double groupPos = 0;
            double groupNeg = 0;
            double score = p.probabilityYes(order[i]);
            while (j < order.length && Double.compare(p.probabilityYes(order[j]), score) == 0) {
                double w = weights == null ? 1 : weights[order[j]];
                if (p.actual(order[j])) {
                    groupPos += w;
                } else {
                    groupNeg += w;
                }
                j++;
            }
            sum += groupPos * (negativesBelow + 0.5 * groupNeg);
            positives += groupPos;
            negativesBelow += groupNeg;
            i = j;
        }
        double negatives = negativesBelow;
        return positives == 0 || negatives == 0 ? Double.NaN : sum / (positives * negatives);
    }
}