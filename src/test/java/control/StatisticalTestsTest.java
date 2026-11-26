package control;

import entity.Predictions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticalTestsTest {

    @Test
    void studentTMatchesTables() {
        assertEquals(0.07339, StatisticalTests.tTwoSidedP(2.0, 10), 1e-4);
        assertEquals(2.228, StatisticalTests.tQuantile975(10), 1e-3);
        assertEquals(1.984, StatisticalTests.tQuantile975(99), 1e-3);
        assertEquals(1.0, StatisticalTests.tTwoSidedP(0, 99), 1e-12);
    }

    @Test
    void correctedTTestInflatesTheVariance() {
        double[] a = new double[100];
        double[] b = new double[100];
        Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            b[i] = 0.3 + 0.05 * r.nextGaussian();
            a[i] = b[i] + 0.01 + 0.02 * r.nextGaussian();
        }
        StatisticalTests.Outcome corrected = StatisticalTests.correctedResampledTTest(a, b, 1.0 / 9);
        StatisticalTests.Outcome classic = StatisticalTests.correctedResampledTTest(a, b, 0);
        assertEquals(100, corrected.n());
        // con la correzione la varianza e' moltiplicata per (1 + 100/9): t piu' piccola di un fattore ~3,48
        assertEquals(classic.statistic() / Math.sqrt(1 + 100.0 / 9), corrected.statistic(), 1e-9);
        assertTrue(corrected.pValue() > classic.pValue());
        assertTrue(corrected.ciLow() < corrected.difference() && corrected.difference() < corrected.ciHigh());
    }

    @Test
    void tTestSkipsUndefinedPairs() {
        double[] a = {0.5, Double.NaN, 0.7, 0.6};
        double[] b = {0.4, 0.5, Double.NaN, 0.5};
        StatisticalTests.Outcome o = StatisticalTests.correctedResampledTTest(a, b, 0.1);
        assertEquals(2, o.n());
        assertEquals(0.1, o.difference(), 1e-12);
    }

    @Test
    void holmAdjustsInOrder() {
        double[] adjusted = StatisticalTests.holm(new double[]{0.01, 0.04, 0.03, Double.NaN});
        // ordinati: 0.01*3 = 0.03; 0.03*2 = 0.06; 0.04*1 = 0.04 -> max progressivo 0.06
        assertEquals(0.03, adjusted[0], 1e-12);
        assertEquals(0.06, adjusted[1], 1e-12);
        assertEquals(0.06, adjusted[2], 1e-12);
        assertTrue(Double.isNaN(adjusted[3]));
    }

    @Test
    void bootstrapOfIdenticalModelsFindsNoDifference() {
        int n = 200;
        int[] ids = new int[n];
        boolean[] actual = new boolean[n];
        boolean[] predicted = new boolean[n];
        double[] p = new double[n];
        double[] size = new double[n];
        Random r = new Random(5);
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            actual[i] = r.nextDouble() < 0.3;
            p[i] = r.nextDouble();
            predicted[i] = p[i] > 0.5;
            size[i] = 1 + r.nextInt(500);
        }
        Predictions a = new Predictions(ids, actual, predicted, p, size);
        Map<String, StatisticalTests.Outcome> o = StatisticalTests.pairedBootstrap(a, a, 300, 1);
        assertEquals(0, o.get("Kappa").difference(), 0);
        assertEquals(1, o.get("Kappa").pValue(), 0);

        // un modello perfetto contro uno casuale: differenza netta
        Predictions perfect = new Predictions(ids, actual, actual, toDouble(actual), size);
        Map<String, StatisticalTests.Outcome> d = StatisticalTests.pairedBootstrap(perfect, a, 300, 1);
        assertTrue(d.get("Kappa").difference() > 0.5);
        assertTrue(d.get("Kappa").pValue() < 0.01);
        assertTrue(d.get("Kappa").ciLow() > 0);
        assertEquals(0, o.get("NPofB20").difference(), 0);
        assertEquals(1, o.get("NPofB20").pValue(), 0);
    }

    private static double[] toDouble(boolean[] b) {
        double[] d = new double[b.length];
        for (int i = 0; i < b.length; i++) {
            d[i] = b[i] ? 1 : 0;
        }
        return d;
    }
}