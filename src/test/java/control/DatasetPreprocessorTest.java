package control;

import dao.WekaDatasetDAO;
import entity.ClassDataset;
import org.junit.jupiter.api.Test;
import weka.core.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetPreprocessorTest {

    @Test
    void signedLogHandlesZeroNegativeAndMissing() {
        assertEquals(0.0, DatasetPreprocessor.signedLog10(0), 1e-12);
        assertEquals(1.0, DatasetPreprocessor.signedLog10(9), 1e-12);
        assertEquals(2.0, DatasetPreprocessor.signedLog10(99), 1e-12);
        assertEquals(-2.0, DatasetPreprocessor.signedLog10(-99), 1e-12);   // Churn negativo
        assertTrue(Double.isNaN(DatasetPreprocessor.signedLog10(Double.NaN)));
    }

    @Test
    void prepareDropsIdentifiersAndTransformsOnlyFeatures() throws Exception {
        ClassDataset d = WekaDatasetDAO.fromTable(SyntheticDataset.table(2, 30, 3), "test");
        Instances raw = d.instances();
        Instances p = DatasetPreprocessor.prepare(raw);
        assertEquals(raw.numAttributes() - 2, p.numAttributes());
        assertEquals("Size", p.attribute(0).name());
        assertEquals(p.numAttributes() - 1, p.classIndex());
        assertEquals(raw.numInstances(), p.numInstances());
        int[] ids = DatasetPreprocessor.rowIds(raw);
        for (int i = 0; i < raw.numInstances(); i++) {
            assertEquals(i, ids[i]);
            assertEquals(raw.instance(i).classValue(), p.instance(i).classValue(), 0);
            for (int a = 0; a < p.numAttributes() - 1; a++) {
                double v = raw.instance(i).value(a + 2);
                double t = p.instance(i).value(a);
                if (Double.isNaN(v)) {
                    assertTrue(p.instance(i).isMissing(a));
                } else {
                    assertEquals(DatasetPreprocessor.signedLog10(v), t, 1e-12);
                }
            }
        }
    }
}