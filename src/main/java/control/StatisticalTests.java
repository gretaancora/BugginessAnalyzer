package control;

import entity.Metrics;
import entity.Predictions;
import weka.core.Statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Test statistici per confrontare due modelli sugli stessi dati.
 * <ul>
 *   <li>CV: t-test corretto per campioni ricampionati (Nadeau e Bengio, 2003). I training set dei fold si
 *   sovrappongono, quindi le differenze non sono indipendenti e la varianza va gonfiata: si usa
 *   (1/n + n_test/n_train) * s^2 al posto di s^2/n. E' il test del Paired T-Tester (corrected) di Weka.</li>
 *   <li>Holdout: bootstrap appaiato. Si ricampionano con reinserimento le righe del test, le stesse per i due
 *   modelli, e si calcola ogni volta la differenza della metrica.</li>
 *   <li>Correzione di Holm per i confronti multipli.</li>
 * </ul>
 */
public final class StatisticalTests {

    private static final double ALPHA = 0.05;

    /** Esito di un test: medie (o valori), differenza A - B, statistica, p-value e intervallo al 95%. */
    public record Outcome(int n, double meanA, double meanB, double difference, double statistic, double pValue,
                          double ciLow, double ciHigh) {
    }

    private StatisticalTests() {
    }

    /**
     * T-test corretto sulle coppie (a[i], b[i]); le coppie con un NaN (es. Precision senza predizioni buggy)
     * vengono escluse.
     *
     * @param testTrainRatio n_test / n_train di un fold (circa 1/9 nella 10-fold)
     */
    public static Outcome correctedResampledTTest(double[] a, double[] b, double testTrainRatio) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Serie di lunghezza diversa");
        }
        int[] valid = IntStream.range(0, a.length).filter(i -> !Double.isNaN(a[i]) && !Double.isNaN(b[i])).toArray();
        int n = valid.length;
        double meanA = Arrays.stream(valid).mapToDouble(i -> a[i]).average().orElse(Double.NaN);
        double meanB = Arrays.stream(valid).mapToDouble(i -> b[i]).average().orElse(Double.NaN);
        if (n < 2) {
            return new Outcome(n, meanA, meanB, meanA - meanB, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double mean = meanA - meanB;
        double var = 0;
        for (int i : valid) {
            double d = a[i] - b[i] - mean;
            var += d * d;
        }
        var /= n - 1;
        double se = Math.sqrt((1.0 / n + testTrainRatio) * var);
        int df = n - 1;
        if (se == 0) {
            double p = mean == 0 ? 1 : 0;
            return new Outcome(n, meanA, meanB, mean, mean == 0 ? 0 : Math.signum(mean) * Double.POSITIVE_INFINITY,
                    p, mean, mean);
        }
        double t = mean / se;
        double q = tQuantile975(df);
        return new Outcome(n, meanA, meanB, mean, t, tTwoSidedP(t, df), mean - q * se, mean + q * se);
    }

    /** P-value bilaterale della t di Student con df gradi di liberta'. */
    static double tTwoSidedP(double t, double df) {
        if (Double.isInfinite(t)) {
            return 0;
        }
        return Statistics.incompleteBeta(df / 2, 0.5, df / (df + t * t));
    }

    /** Quantile 0,975 della t di Student (per l'intervallo al 95%), per bisezione. */
    static double tQuantile975(double df) {
        double lo = 0;
        double hi = 1000;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (tTwoSidedP(mid, df) > ALPHA) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * Bootstrap appaiato su tutte le metriche: per ogni ricampionamento le stesse righe pesano allo stesso modo
     * per i due modelli. Il p-value bilaterale e' 2 * min(quota di differenze <= 0, quota >= 0) (al massimo 1);
     * l'intervallo al 95% e' quello dei percentili. I ricampionamenti in cui una metrica non e' definita (NaN)
     * vengono esclusi per quella metrica.
     */
    public static Map<String, Outcome> pairedBootstrap(Predictions a, Predictions b, int samples, long seed) {
        if (!a.sameRows(b)) {
            throw new IllegalArgumentException("Il bootstrap appaiato richiede le stesse righe nello stesso ordine");
        }
        int n = a.size();
        Metrics fullA = MetricsCalculator.compute(a);
        Metrics fullB = MetricsCalculator.compute(b);
        List<List<Double>> diffs = new ArrayList<>();
        for (int m = 0; m < Metrics.NAMES.length; m++) {
            diffs.add(new ArrayList<>(samples));
        }
        Random random = new Random(seed);
        double[] weights = new double[n];
        for (int s = 0; s < samples; s++) {
            Arrays.fill(weights, 0);
            for (int i = 0; i < n; i++) {
                weights[random.nextInt(n)]++;
            }
            double[] va = MetricsCalculator.compute(a, weights).values();
            double[] vb = MetricsCalculator.compute(b, weights).values();
            for (int m = 0; m < va.length; m++) {
                double d = va[m] - vb[m];
                if (!Double.isNaN(d)) {
                    diffs.get(m).add(d);
                }
            }
        }
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        double[] valuesA = fullA.values();
        double[] valuesB = fullB.values();
        for (int m = 0; m < Metrics.NAMES.length; m++) {
            List<Double> d = diffs.get(m);
            d.sort(Comparator.naturalOrder());
            double p = Double.NaN;
            double low = Double.NaN;
            double high = Double.NaN;
            if (!d.isEmpty()) {
                long nonPositive = d.stream().filter(x -> x <= 0).count();
                long nonNegative = d.stream().filter(x -> x >= 0).count();
                p = Math.min(1, 2.0 * Math.min(nonPositive, nonNegative) / d.size());
                low = percentile(d, 0.025);
                high = percentile(d, 0.975);
            }
            outcomes.put(Metrics.NAMES[m], new Outcome(n, valuesA[m], valuesB[m], valuesA[m] - valuesB[m],
                    Double.NaN, p, low, high));
        }
        return outcomes;
    }

    private static double percentile(List<Double> sorted, double q) {
        int index = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /**
     * Correzione di Holm-Bonferroni: con i p-value ordinati p(1) <= ... <= p(m), il valore corretto di p(i) e'
     * max_{j<=i} min(1, (m - j + 1) * p(j)). I NaN restano NaN e non contano in m.
     */
    public static double[] holm(double[] p) {
        int[] order = IntStream.range(0, p.length).filter(i -> !Double.isNaN(p[i])).boxed()
                .sorted(Comparator.comparingDouble(i -> p[i])).mapToInt(Integer::intValue).toArray();
        double[] adjusted = new double[p.length];
        Arrays.fill(adjusted, Double.NaN);
        int m = order.length;
        double running = 0;
        for (int j = 0; j < m; j++) {
            running = Math.max(running, Math.min(1, (m - j) * p[order[j]]));
            adjusted[order[j]] = running;
        }
        return adjusted;
    }
}