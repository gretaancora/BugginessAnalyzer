package entity;

import java.util.List;

/**
 * Risultato dell'ordered holdout: i due tagli per release (selezione e test), le scelte fatte sul validation
 * set e i risultati sul test di tutte le configurazioni, con le predizioni per riga.
 */
public record HoldoutOutcome(ReleaseSplit testSplit, ReleaseSplit validationSplit, Selection selection,
                             List<RecipeResult> testResults) {

    public HoldoutOutcome {
        testResults = List.copyOf(testResults);
    }

    public RecipeResult testResult(ModelConfiguration configuration) {
        return testResults.stream().filter(r -> r.recipe().configuration().equals(configuration)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configurazione senza risultato: " + configuration));
    }
}