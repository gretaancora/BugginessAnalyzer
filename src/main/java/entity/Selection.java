package entity;

import java.util.List;
import java.util.Map;

/**
 * Esito della fase di selezione su un validation set: il k scelto per ogni configurazione di IBk, la
 * ricetta migliore di ogni classificatore e il campione assoluto, insieme a tutti i risultati di
 * validazione da cui derivano (una riga per ogni ricetta provata, quindi per IBk una per ogni k).
 */
public record Selection(Map<ModelConfiguration, Integer> chosenK, Map<ClassifierType, Recipe> bestByClassifier,
                        Recipe champion, List<RecipeResult> validationResults) {

    public Selection {
        chosenK = Map.copyOf(chosenK);
        bestByClassifier = Map.copyOf(bestByClassifier);
        validationResults = List.copyOf(validationResults);
    }

    /** Ricetta da usare nella fase di test per una configurazione (con il k scelto se e' IBk). */
    public Recipe recipeFor(ModelConfiguration configuration) {
        return configuration.classifier().hasK()
                ? new Recipe(configuration, chosenK.get(configuration))
                : Recipe.of(configuration);
    }

    public boolean isBest(ModelConfiguration configuration) {
        return bestByClassifier.get(configuration.classifier()).configuration().equals(configuration);
    }

    public boolean isChampion(ModelConfiguration configuration) {
        return champion.configuration().equals(configuration);
    }
}