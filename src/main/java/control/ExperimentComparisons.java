package control;

import entity.ClassifierType;
import entity.Comparison;
import entity.FoldOutcome;
import entity.HoldoutOutcome;
import entity.Metrics;
import entity.ModelConfiguration;
import entity.Predictions;
import entity.RecipeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * I tre gruppi di confronti fatti sui dati di test, per ogni metrica:
 * <ul>
 *   <li>TECHNIQUES: per ogni classificatore, ognuna delle sue configurazioni con tecniche contro la sua baseline;</li>
 *   <li>CLASSIFIERS: per ogni coppia di tecniche, i tre classificatori tra loro;</li>
 *   <li>BEST: le versioni migliori dei tre classificatori (scelte sul validation set) tra loro.</li>
 * </ul>
 * Il p-value e' corretto con Holm dentro ogni (gruppo di confronti, gruppo, metrica).
 */
public final class ExperimentComparisons {

    public static final String CV = "CV_10x10";
    public static final String HOLDOUT = "ORDERED_HOLDOUT";

    /**
     * Un confronto da fare: A e B sono una configurazione oppure un classificatore (= la sua versione migliore,
     * cioe' la configurazione scelta sul validation set).
     */
    record Pair(String family, String group, Object a, Object b) {
    }

    private ExperimentComparisons() {
    }

    static List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        for (ClassifierType c : ClassifierType.values()) {
            ModelConfiguration baseline = ModelConfiguration.of(c).getFirst();
            for (ModelConfiguration conf : ModelConfiguration.of(c)) {
                if (!conf.isBaseline()) {
                    pairs.add(new Pair("TECHNIQUES", c.label(), conf, baseline));
                }
            }
        }
        ClassifierType[] cs = ClassifierType.values();
        for (ModelConfiguration rf : ModelConfiguration.of(cs[0])) {
            for (int i = 0; i < cs.length; i++) {
                for (int j = i + 1; j < cs.length; j++) {
                    pairs.add(new Pair("CLASSIFIERS", rf.techniques(), same(rf, cs[i]), same(rf, cs[j])));
                }
            }
        }
        for (int i = 0; i < cs.length; i++) {
            for (int j = i + 1; j < cs.length; j++) {
                pairs.add(new Pair("BEST", "-", cs[i], cs[j]));
            }
        }
        return pairs;
    }

    private static ModelConfiguration same(ModelConfiguration techniques, ClassifierType classifier) {
        return new ModelConfiguration(classifier, techniques.featureSelection(), techniques.balancing());
    }

    public static String label(Object key) {
        return key instanceof ModelConfiguration conf ? conf.label() : "Best(" + ((ClassifierType) key).label() + ")";
    }

    /** Confronti sui fold esterni della 10x10 CV (t-test corretto). */
    public static List<Comparison> crossValidation(List<FoldOutcome> folds) {
        double ratio = folds.stream().mapToDouble(f -> (double) f.testRows() / f.trainRows()).average().orElseThrow();
        List<Comparison> out = new ArrayList<>();
        for (Pair pair : pairs()) {
            Function<FoldOutcome, RecipeResult> ra = resultOf(pair.a());
            Function<FoldOutcome, RecipeResult> rb = resultOf(pair.b());
            for (String metric : Metrics.NAMES) {
                double[] a = folds.stream().mapToDouble(f -> ra.apply(f).metrics().value(metric)).toArray();
                double[] b = folds.stream().mapToDouble(f -> rb.apply(f).metrics().value(metric)).toArray();
                StatisticalTests.Outcome o = StatisticalTests.correctedResampledTTest(a, b, ratio);
                out.add(comparison(CV, pair, metric, o));
            }
        }
        return withHolm(out);
    }

    private static Function<FoldOutcome, RecipeResult> resultOf(Object key) {
        if (key instanceof ModelConfiguration conf) {
            return f -> f.testResult(conf);
        }
        ClassifierType c = (ClassifierType) key;
        return f -> f.bestResult(c);
    }

    /** Confronti sul test dell'ordered holdout (bootstrap appaiato). */
    public static List<Comparison> holdout(HoldoutOutcome outcome, int samples, long seed) {
        List<Comparison> out = new ArrayList<>();
        int index = 0;
        for (Pair pair : pairs()) {
            Predictions a = holdoutResult(outcome, pair.a()).predictions();
            Predictions b = holdoutResult(outcome, pair.b()).predictions();
            // seme diverso per confronto, ma fisso: i risultati sono riproducibili
            Map<String, StatisticalTests.Outcome> o = StatisticalTests.pairedBootstrap(a, b, samples, seed + index++);
            for (String metric : Metrics.NAMES) {
                out.add(comparison(HOLDOUT, pair, metric, o.get(metric)));
            }
        }
        return withHolm(out);
    }

    private static RecipeResult holdoutResult(HoldoutOutcome outcome, Object key) {
        if (key instanceof ModelConfiguration conf) {
            return outcome.testResult(conf);
        }
        return outcome.testResult(outcome.selection().bestByClassifier().get((ClassifierType) key).configuration());
    }

    private static Comparison comparison(String validation, Pair pair, String metric, StatisticalTests.Outcome o) {
        return new Comparison(validation, pair.family(), pair.group(), metric, label(pair.a()), label(pair.b()),
                o.n(), o.meanA(), o.meanB(), o.difference(), o.statistic(), o.pValue(), Double.NaN, o.ciLow(),
                o.ciHigh());
    }

    /** Applica Holm dentro ogni (famiglia, gruppo, metrica). */
    static List<Comparison> withHolm(List<Comparison> list) {
        Map<String, List<Integer>> families = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Comparison c = list.get(i);
            families.computeIfAbsent(c.family() + "|" + c.group() + "|" + c.metric(), k -> new ArrayList<>()).add(i);
        }
        List<Comparison> out = new ArrayList<>(list);
        for (List<Integer> idx : families.values()) {
            double[] p = idx.stream().mapToDouble(i -> list.get(i).pValue()).toArray();
            double[] adjusted = StatisticalTests.holm(p);
            for (int j = 0; j < idx.size(); j++) {
                out.set(idx.get(j), list.get(idx.get(j)).withHolm(adjusted[j]));
            }
        }
        return out;
    }
}