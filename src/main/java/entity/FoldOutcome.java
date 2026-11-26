package entity;

import java.util.List;

/**
 * Risultato di un fold della 10x10 CV: le scelte fatte sulla validation interna (con i risultati di
 * validazione) e i risultati sul fold di test di tutte le configurazioni.
 *
 * @param repetition ripetizione, da 0
 * @param fold       fold, da 0
 * @param trainRows  righe del training esterno
 * @param testRows   righe del fold di test
 */
public record FoldOutcome(int repetition, int fold, int trainRows, int testRows, Selection selection,
                          List<RecipeResult> testResults) {

    public FoldOutcome {
        testResults = List.copyOf(testResults);
    }

    public RecipeResult testResult(ModelConfiguration configuration) {
        return testResults.stream().filter(r -> r.recipe().configuration().equals(configuration)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configurazione senza risultato: " + configuration));
    }

    /** Risultato sul test della configurazione scelta per il classificatore in questo fold. */
    public RecipeResult bestResult(ClassifierType classifier) {
        return testResult(selection.bestByClassifier().get(classifier).configuration());
    }
}