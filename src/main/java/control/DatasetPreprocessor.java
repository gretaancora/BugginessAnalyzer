package control;

import entity.ClassDataset;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

import java.util.ArrayList;

/**
 * Preprocessing comune a training e test: toglie {@code RowId} e {@code ReleaseID} (servono solo a dividere i
 * dati) e trasforma ogni feature con il logaritmo in base 10 con segno, sign(x) * log10(1 + |x|).
 * <ul>
 *   <li>il +1 serve perche' molte metriche valgono 0 (log10(0) non esiste);</li>
 *   <li>il segno serve per Churn, che puo' essere negativo;</li>
 *   <li>i valori mancanti restano mancanti.</li>
 * </ul>
 * La trasformazione non stima parametri dai dati, quindi applicarla separatamente a training e test equivale
 * ad applicarla prima della divisione: non crea data leakage.
 */
public final class DatasetPreprocessor {

    private DatasetPreprocessor() {
    }

    public static double signedLog10(double x) {
        if (Double.isNaN(x)) {
            return x;
        }
        return Math.signum(x) * Math.log10(1 + Math.abs(x));
    }

    /** Nuove istanze senza RowId e ReleaseID, con le feature trasformate; la classe resta l'ultimo attributo. */
    public static Instances prepare(Instances split) {
        int classIndex = split.classIndex();
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int a = 2; a < split.numAttributes(); a++) {
            attributes.add(split.attribute(a).copy(split.attribute(a).name()));
        }
        Instances out = new Instances(split.relationName(), attributes, split.numInstances());
        out.setClassIndex(attributes.size() - 1);
        for (Instance inst : split) {
            double[] values = new double[attributes.size()];
            for (int a = 2; a < split.numAttributes(); a++) {
                double v = inst.value(a);
                values[a - 2] = a == classIndex ? v : signedLog10(v);
            }
            out.add(new DenseInstance(inst.weight(), values));
        }
        return out;
    }

    /** RowId di ogni riga, nello stesso ordine (per ritrovare le classi Java dopo il preprocessing). */
    public static int[] rowIds(Instances split) {
        if (!ClassDataset.ROW_ID.equals(split.attribute(0).name())) {
            throw new IllegalArgumentException("Il primo attributo non e' " + ClassDataset.ROW_ID);
        }
        int[] ids = new int[split.numInstances()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (int) split.instance(i).value(0);
        }
        return ids;
    }
}