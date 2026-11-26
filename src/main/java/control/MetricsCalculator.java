package control;

import entity.Metrics;
import entity.Predictions;

/**
 * Metriche di una predizione con classe positiva "yes", calcolate con le stesse definizioni di Weka
 * (Evaluation): Kappa di Cohen sulla matrice di confusione, AUC come area sotto la curva ROC con i pareggi
 * di probabilita' contati a meta' (statistica di Mann-Whitney). In piu' le metriche effort-aware NPofB10 e
 * NPofB20 (vedi {@link #npofb}). I pesi servono al bootstrap: il peso di una riga e' il numero di volte in cui
 * compare nel ricampionamento.
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
        int[] levels = Metrics.NPOFB_LEVELS;
        return new Metrics(tp, fp, tn, fn, accuracy, precision, recall, auc(p, weights),
                kappa(tp, fp, tn, fn),
                npofb(p, weights, p.effortOrder(), levels[0]), npofb(p, weights, p.effortOrder(), levels[1]),
                npofb(p, weights, p.optimalEffortOrder(), levels[0]),
                npofb(p, weights, p.optimalEffortOrder(), levels[1]),
                npofb(p, weights, p.smallestFirstOrder(), levels[0]),
                npofb(p, weights, p.smallestFirstOrder(), levels[1]));
    }

    /**
     * NPofBX: si ispezionano le classi nell'ordine dato (per i modelli: probabilita' predetta / Size decrescente)
     * finche' le righe ispezionate restano entro l'X% delle righe totali dell'insieme; la classe che farebbe
     * superare la soglia non viene ispezionata e l'ispezione si ferma. Il valore e' la quota di classi buggy
     * trovate sul totale delle classi buggy (un'etichetta per classe: non si contano i singoli difetti).
     * Con i pesi del bootstrap una riga presa w volte conta come w classi consecutive. NaN se non ci sono classi
     * buggy.
     *
     * @param percent X, percentuale di righe ispezionate
     */
    static double npofb(Predictions p, double[] weights, int[] order, int percent) {
        double totalLines = 0;
        double totalBuggy = 0;
        for (int i = 0; i < p.size(); i++) {
            double w = weights == null ? 1 : weights[i];
            totalLines += w * p.classSize(i);
            totalBuggy += p.actual(i) ? w : 0;
        }
        if (totalBuggy == 0) {
            return Double.NaN;
        }
        double budget = totalLines * percent;   // confronto con righe * 100: niente arrotondamenti
        double inspected = 0;
        double found = 0;
        for (int i : order) {
            long copies = weights == null ? 1 : Math.round(weights[i]);
            for (long c = 0; c < copies; c++) {
                if ((inspected + p.classSize(i)) * 100 > budget) {
                    return found / totalBuggy;
                }
                inspected += p.classSize(i);
                found += p.actual(i) ? 1 : 0;
            }
        }
        return found / totalBuggy;
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