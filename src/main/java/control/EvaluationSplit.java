package control;

import entity.BalancingType;
import entity.ClassDataset;
import entity.FeatureSelectionType;
import exception.ClassificationException;
import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;
import weka.filters.unsupervised.attribute.Remove;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coppia (insieme di addestramento, insieme di valutazione) con il preprocessing che dipende solo dal
 * training. Feature selection e balancing si calcolano sul solo training; l'insieme di valutazione viene
 * soltanto ridotto alle feature scelte e non viene mai bilanciato. I risultati sono memorizzati: il sottoinsieme
 * di CFS non dipende ne' dal balancing ne' dal classificatore, e il training bilanciato non dipende dal
 * classificatore, quindi le ricette dei tre classificatori li condividono (e li vedono identici).
 */
public final class EvaluationSplit {

    /** Training e valutazione dopo feature selection e balancing, con i nomi delle feature tenute. */
    public record Prepared(Instances train, Instances evaluation, List<String> features) {
    }

    private final Instances train;
    private final Instances evaluation;
    private final int[] evaluationRowIds;
    private final double[] evaluationSizes;
    private final int seed;
    private final Map<FeatureSelectionType, Prepared> selected = new HashMap<>();
    private final Map<String, Prepared> balanced = new HashMap<>();

    /**
     * @param trainRaw      righe di addestramento, con RowId e ReleaseID
     * @param evaluationRaw righe di valutazione, con RowId e ReleaseID
     * @param seed          seme di SMOTE e dell'undersampling
     */
    public EvaluationSplit(Instances trainRaw, Instances evaluationRaw, int seed) {
        this.train = DatasetPreprocessor.prepare(trainRaw);
        this.evaluation = DatasetPreprocessor.prepare(evaluationRaw);
        this.evaluationRowIds = DatasetPreprocessor.rowIds(evaluationRaw);
        this.evaluationSizes = sizes(evaluationRaw);
        this.seed = seed;
    }

    public int[] evaluationRowIds() {
        return evaluationRowIds.clone();
    }

    /** Size (righe di codice, valore originale, senza logaritmo) delle righe di valutazione: serve a NPofBX. */
    public double[] evaluationSizes() {
        return evaluationSizes.clone();
    }

    private static double[] sizes(Instances raw) {
        weka.core.Attribute size = raw.attribute(ClassDataset.SIZE);
        if (size == null) {
            throw new IllegalArgumentException("Il dataset non ha la colonna " + ClassDataset.SIZE
                    + ", necessaria per NPofBX");
        }
        double[] values = new double[raw.numInstances()];
        for (int i = 0; i < values.length; i++) {
            values[i] = raw.instance(i).value(size);
        }
        return values;
    }

    public int trainSize() {
        return train.numInstances();
    }

    public int evaluationSize() {
        return evaluation.numInstances();
    }

    public synchronized Prepared prepared(FeatureSelectionType fs, BalancingType balancing)
            throws ClassificationException {
        String key = fs + "+" + balancing;
        Prepared p = balanced.get(key);
        if (p == null) {
            Prepared s = selected(fs);
            p = new Prepared(balance(s.train(), balancing), s.evaluation(), s.features());
            balanced.put(key, p);
        }
        return p;
    }

    private Prepared selected(FeatureSelectionType fs) throws ClassificationException {
        Prepared s = selected.get(fs);
        if (s == null) {
            s = switch (fs) {
                case NONE -> new Prepared(train, evaluation, featureNames(train));
                case CFS -> cfs();
            };
            selected.put(fs, s);
        }
        return s;
    }

    /** CFS con ricerca GreedyStepwise all'indietro, calcolato sul training; il test viene proiettato. */
    private Prepared cfs() throws ClassificationException {
        try {
            AttributeSelection selection = new AttributeSelection();
            GreedyStepwise search = new GreedyStepwise();
            search.setSearchBackwards(true);
            selection.setEvaluator(new CfsSubsetEval());
            selection.setSearch(search);
            selection.SelectAttributes(train);
            int[] keep = selection.selectedAttributes();   // comprende l'indice della classe
            Remove remove = new Remove();
            remove.setAttributeIndicesArray(keep);
            remove.setInvertSelection(true);
            remove.setInputFormat(train);
            Instances t = Filter.useFilter(train, remove);
            Instances e = Filter.useFilter(evaluation, remove);
            return new Prepared(t, e, featureNames(t));
        } catch (Exception ex) {
            throw new ClassificationException("Feature selection CFS non riuscita: " + ex.getMessage(), ex);
        }
    }

    private Instances balance(Instances data, BalancingType balancing) throws ClassificationException {
        try {
            return switch (balancing) {
                case NONE -> data;
                case UNDERSAMPLING -> undersample(data);
                case SMOTE -> smote(data);
            };
        } catch (ClassificationException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassificationException("Balancing " + balancing + " non riuscito: " + e.getMessage(), e);
        }
    }

    /** SpreadSubsample con rapporto massimo 1:1 tra le classi. */
    private Instances undersample(Instances data) throws Exception {
        SpreadSubsample filter = new SpreadSubsample();
        filter.setDistributionSpread(1.0);
        filter.setRandomSeed(seed);
        filter.setInputFormat(data);
        return Filter.useFilter(data, filter);
    }

    /**
     * SMOTE fino al rapporto 1:1: la percentuale di istanze sintetiche e' (non buggy - buggy) / buggy * 100,
     * calcolata su questo training (il default di Weka, 100%, raddoppierebbe soltanto le buggy).
     */
    private Instances smote(Instances data) throws Exception {
        int[] counts = data.attributeStats(data.classIndex()).nominalCounts;
        int minority = Math.min(counts[0], counts[1]);
        int majority = Math.max(counts[0], counts[1]);
        if (minority < 2 || minority == majority) {
            return data;
        }
        SMOTE filter = new SMOTE();
        filter.setPercentage(100.0 * (majority - minority) / minority);
        filter.setNearestNeighbors(5);
        filter.setRandomSeed(seed);
        filter.setInputFormat(data);
        return Filter.useFilter(data, filter);
    }

    private static List<String> featureNames(Instances data) {
        List<String> names = new ArrayList<>();
        for (int a = 0; a < data.numAttributes(); a++) {
            if (a != data.classIndex()) {
                names.add(data.attribute(a).name());
            }
        }
        return names;
    }
}