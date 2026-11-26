package control;

import dao.WekaDatasetDAO;
import entity.ClassDataset;
import entity.Metrics;
import entity.Predictions;
import org.junit.jupiter.api.Test;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCalculatorTest {

    private static Predictions predictions(boolean[] actual, boolean[] predicted, double[] p) {
        double[] size = new double[actual.length];
        java.util.Arrays.fill(size, 1);
        return predictions(actual, predicted, p, size);
    }

    private static Predictions predictions(boolean[] actual, boolean[] predicted, double[] p, double[] size) {
        int[] ids = new int[actual.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        return new Predictions(ids, actual, predicted, p, size);
    }

    /**
     * L'esempio discusso: 5 classi, 1.000 righe. Ordine per densita' A, B, C, D, E. Con il 20% (200 righe) si
     * ispezionano A, B, C (180 righe; D porterebbe a 780 e non si conta): 2 buggy su 3. Con il 10% (100 righe)
     * solo A e B (C porterebbe a 180): 1 su 3.
     */
    @Test
    void npofbFollowsRiskDensityAndExcludesTheClassAcrossTheThreshold() {
        boolean[] actual = {true, false, true, true, false};          // A, B, C, D, E
        double[] p = {0.60, 0.30, 0.90, 0.80, 0.10};
        double[] size = {50, 30, 100, 600, 220};
        Metrics m = MetricsCalculator.compute(predictions(actual, new boolean[5], p, size));
        assertEquals(2.0 / 3, m.npofb20(), 1e-12);
        assertEquals(1.0 / 3, m.npofb10(), 1e-12);
        // ottimo: buggy dalla piu' piccola (A 50, C 100, D 600): 20% -> A, C; 10% -> A (C porterebbe a 150)
        assertEquals(2.0 / 3, m.optimalNPofB20(), 1e-12);
        assertEquals(1.0 / 3, m.optimalNPofB10(), 1e-12);
        // solo dimensione: B 30, A 50, C 100 (180), E porterebbe a 400 -> A, C su 3; al 10% B, A (80) -> A su 3
        assertEquals(2.0 / 3, m.smallestFirstNPofB20(), 1e-12);
        assertEquals(1.0 / 3, m.smallestFirstNPofB10(), 1e-12);
    }

    @Test
    void npofbIncludesAClassThatEndsExactlyOnTheThreshold() {
        // 1.000 righe: il 20% e' 200; la prima classe ne ha esattamente 200 ed e' buggy
        boolean[] actual = {true, true, false};
        double[] p = {0.9, 0.5, 0.1};
        double[] size = {200, 300, 500};
        Metrics m = MetricsCalculator.compute(predictions(actual, new boolean[3], p, size));
        assertEquals(0.5, m.npofb20(), 1e-12);
        assertEquals(0.0, m.npofb10(), 1e-12);
    }

    @Test
    void npofbTiesUseProbabilityThenDatasetOrder() {
        // stessa densita' (0,01): prima la probabilita' piu' alta (riga 1, 100 righe), poi la riga 0
        boolean[] actual = {false, true, false};
        double[] p = {0.5, 1.0, 0.0};
        double[] size = {50, 100, 850};
        Metrics m = MetricsCalculator.compute(predictions(actual, new boolean[3], p, size));
        assertEquals(1.0, m.npofb10(), 1e-12);   // 10% = 100 righe: solo la riga 1, che e' buggy
    }

    @Test
    void handComputedExample() {
        // TP=2 (0,1), FN=1 (2), FP=1 (3), TN=2 (4,5)
        boolean[] actual = {true, true, true, false, false, false};
        boolean[] predicted = {true, true, false, true, false, false};
        double[] p = {0.9, 0.8, 0.3, 0.7, 0.3, 0.1};
        Metrics m = MetricsCalculator.compute(predictions(actual, predicted, p));
        assertEquals(2, m.tp(), 0);
        assertEquals(1, m.fp(), 0);
        assertEquals(2, m.tn(), 0);
        assertEquals(1, m.fn(), 0);
        assertEquals(4.0 / 6, m.accuracy(), 1e-12);
        assertEquals(2.0 / 3, m.precision(), 1e-12);
        assertEquals(2.0 / 3, m.recall(), 1e-12);
        // Kappa: po = 4/6, pe = (3*3 + 3*3)/36 = 0.5 -> (0.6667-0.5)/0.5 = 1/3
        assertEquals(1.0 / 3, m.kappa(), 1e-12);
        // AUC: coppie (pos, neg) = 9; vinte: 0.9 e 0.8 su tutte (6); 0.3 batte 0.1, pareggia 0.3 (1.5) -> 7.5/9
        assertEquals(7.5 / 9, m.auc(), 1e-12);
    }

    @Test
    void precisionIsUndefinedWithoutPositivePredictions() {
        Metrics m = MetricsCalculator.compute(predictions(new boolean[]{true, false}, new boolean[]{false, false},
                new double[]{0.4, 0.2}));
        assertTrue(Double.isNaN(m.precision()));
        assertEquals(0, m.kappa(), 1e-12);
    }

    @Test
    void weightsAreEquivalentToRepeatedRows() {
        boolean[] actual = {true, false, true, false};
        boolean[] predicted = {true, false, false, true};
        double[] p = {0.8, 0.2, 0.4, 0.6};
        double[] size = {10, 20, 30, 40};
        Metrics weighted = MetricsCalculator.compute(predictions(actual, predicted, p, size),
                new double[]{2, 0, 1, 3});
        Metrics repeated = MetricsCalculator.compute(predictions(
                new boolean[]{true, true, true, false, false, false},
                new boolean[]{true, true, false, true, true, true},
                new double[]{0.8, 0.8, 0.4, 0.6, 0.6, 0.6},
                new double[]{10, 10, 30, 40, 40, 40}));
        assertEquals(repeated, weighted);
    }

    /** Le metriche coincidono con quelle di weka.classifiers.Evaluation sugli stessi dati. */
    @Test
    void agreesWithWekaEvaluation() throws Exception {
        ClassDataset d = WekaDatasetDAO.fromTable(SyntheticDataset.table(4, 150, 11), "test");
        Instances train = DatasetPreprocessor.prepare(d.releases(r -> r <= 3));
        Instances test = DatasetPreprocessor.prepare(d.releases(r -> r == 4));
        int yes = train.classAttribute().indexOfValue(ClassDataset.YES);
        for (Classifier c : new Classifier[]{new NaiveBayes(), new IBk(5), new RandomForest()}) {
            c.buildClassifier(train);
            Evaluation eval = new Evaluation(train);
            eval.evaluateModel(c, test);
            int n = test.numInstances();
            boolean[] actual = new boolean[n];
            boolean[] predicted = new boolean[n];
            double[] prob = new double[n];
            for (int i = 0; i < n; i++) {
                double[] dist = c.distributionForInstance(test.instance(i));
                actual[i] = (int) test.instance(i).classValue() == yes;
                predicted[i] = Utils.maxIndex(dist) == yes;
                prob[i] = dist[yes];
            }
            Metrics m = MetricsCalculator.compute(predictions(actual, predicted, prob));
            String name = c.getClass().getSimpleName();
            assertEquals(eval.numTruePositives(yes), m.tp(), 0, name);
            assertEquals(eval.numFalsePositives(yes), m.fp(), 0, name);
            assertEquals(eval.pctCorrect() / 100, m.accuracy(), 1e-9, name);
            assertEquals(eval.precision(yes), m.precision(), 1e-9, name);
            assertEquals(eval.recall(yes), m.recall(), 1e-9, name);
            assertEquals(eval.kappa(), m.kappa(), 1e-9, name);
            assertEquals(eval.areaUnderROC(yes), m.auc(), 1e-9, name);
        }
    }
}