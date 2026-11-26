package entity;

import weka.core.Instances;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Dataset finale in forma Weka. Attributi, in ordine: {@value #ROW_ID} (posizione della riga, per ritrovare
 * la classe Java dopo i rimescolamenti), {@value #RELEASE_ID}, le feature numeriche e la classe
 * {@value #CLASS_ATTRIBUTE} con valori {no, yes}. Le righe sono in ordine di ReleaseID. {@code RowId} e
 * {@code ReleaseID} servono solo a dividere i dati e vengono tolti prima dell'addestramento.
 */
public final class ClassDataset {

    public static final String ROW_ID = "RowId";
    public static final String RELEASE_ID = "ReleaseID";
    public static final String CLASS_ATTRIBUTE = "Buggy";
    public static final String YES = "yes";
    public static final String NO = "no";

    private final Instances data;
    private final List<String> classNames;

    public ClassDataset(Instances data, List<String> classNames) {
        if (data.classIndex() != data.numAttributes() - 1 || !data.classAttribute().name().equals(CLASS_ATTRIBUTE)) {
            throw new IllegalArgumentException("La classe deve essere l'ultimo attributo, " + CLASS_ATTRIBUTE);
        }
        if (!ROW_ID.equals(data.attribute(0).name()) || !RELEASE_ID.equals(data.attribute(1).name())) {
            throw new IllegalArgumentException("I primi attributi devono essere " + ROW_ID + " e " + RELEASE_ID);
        }
        if (classNames.size() != data.numInstances()) {
            throw new IllegalArgumentException("Un nome di classe per riga: " + classNames.size()
                    + " nomi, " + data.numInstances() + " righe");
        }
        this.data = data;
        this.classNames = List.copyOf(classNames);
    }

    /** Tutte le righe. Non va modificato: chi deve cambiare i valori lavora su una copia. */
    public Instances instances() {
        return data;
    }

    public int size() {
        return data.numInstances();
    }

    public String className(int rowId) {
        return classNames.get(rowId);
    }

    public int releaseId(int row) {
        return (int) data.instance(row).value(1);
    }

    /** Nomi delle feature (senza RowId, ReleaseID e Buggy). */
    public List<String> featureNames() {
        List<String> names = new ArrayList<>();
        for (int i = 2; i < data.numAttributes() - 1; i++) {
            names.add(data.attribute(i).name());
        }
        return names;
    }

    /** Righe e righe buggy di ogni release, in ordine di ReleaseID: valore {righe, buggy}. */
    public SortedMap<Integer, int[]> countsPerRelease() {
        int yes = data.classAttribute().indexOfValue(YES);
        SortedMap<Integer, int[]> counts = new TreeMap<>();
        for (int i = 0; i < size(); i++) {
            int[] c = counts.computeIfAbsent(releaseId(i), r -> new int[2]);
            c[0]++;
            if ((int) data.instance(i).classValue() == yes) {
                c[1]++;
            }
        }
        return counts;
    }

    /** Righe delle release che soddisfano il filtro, nell'ordine del dataset. */
    public Instances releases(IntPredicate releaseFilter) {
        Instances subset = new Instances(data, 0);
        for (int i = 0; i < size(); i++) {
            if (releaseFilter.test(releaseId(i))) {
                subset.add(data.instance(i));
            }
        }
        return subset;
    }
}