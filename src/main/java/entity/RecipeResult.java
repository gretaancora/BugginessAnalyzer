package entity;

import java.util.List;

/**
 * Risultato di una ricetta addestrata su un training set e valutata su un altro insieme: metriche,
 * feature tenute dalla feature selection e dimensione del training dopo il balancing.
 */
public record RecipeResult(Recipe recipe, Metrics metrics, List<String> features, int trainRows,
                           int trainBuggy, Predictions predictions) {

    public RecipeResult {
        features = List.copyOf(features);
    }

    /** Stesso risultato senza le predizioni per riga (per non tenerle in memoria quando non servono). */
    public RecipeResult withoutPredictions() {
        return new RecipeResult(recipe, metrics, features, trainRows, trainBuggy, null);
    }
}