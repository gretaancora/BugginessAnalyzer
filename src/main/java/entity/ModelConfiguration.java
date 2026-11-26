package entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Configurazione sperimentale: classificatore, feature selection e balancing. E' la "ricetta" senza
 * l'iperparametro k, che per IBk viene scelto sul validation set e puo' cambiare da un fold all'altro.
 */
public record ModelConfiguration(ClassifierType classifier, FeatureSelectionType featureSelection,
                                 BalancingType balancing) {

    /** Ordine dalla configurazione piu' semplice alla piu' complessa (usato per spareggiare). */
    public static final Comparator<ModelConfiguration> SIMPLEST_FIRST = Comparator
            .comparing(ModelConfiguration::featureSelection)
            .thenComparing(ModelConfiguration::balancing)
            .thenComparing(ModelConfiguration::classifier);

    /** Le configurazioni di un classificatore, nell'ordine dalla piu' semplice (la baseline) alla piu' complessa. */
    public static List<ModelConfiguration> of(ClassifierType classifier) {
        List<ModelConfiguration> list = new ArrayList<>();
        for (FeatureSelectionType fs : FeatureSelectionType.values()) {
            for (BalancingType b : BalancingType.values()) {
                list.add(new ModelConfiguration(classifier, fs, b));
            }
        }
        return list;
    }

    /** Tutte le configurazioni della griglia. */
    public static List<ModelConfiguration> all() {
        List<ModelConfiguration> list = new ArrayList<>();
        for (ClassifierType c : ClassifierType.values()) {
            list.addAll(of(c));
        }
        return list;
    }

    public boolean isBaseline() {
        return featureSelection == FeatureSelectionType.NONE && balancing == BalancingType.NONE;
    }

    /** Coppia di tecniche (es. "CFS+SMOTE"), uguale per i tre classificatori. */
    public String techniques() {
        return featureSelection + "+" + balancing;
    }

    /** Nome leggibile, es. "RandomForest/CFS/SMOTE". */
    public String label() {
        return classifier.label() + "/" + featureSelection + "/" + balancing;
    }
}