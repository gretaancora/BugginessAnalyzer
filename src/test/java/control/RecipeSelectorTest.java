package control;

import entity.BalancingType;
import entity.ClassifierType;
import entity.FeatureSelectionType;
import entity.Metrics;
import entity.ModelConfiguration;
import entity.Recipe;
import entity.RecipeResult;
import entity.Selection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeSelectorTest {

    private static RecipeResult result(Recipe r, double kappa, double auc) {
        return new RecipeResult(r, new Metrics(0, 0, 0, 0, 0, 0, 0, auc, kappa, 0, 0, 0, 0, 0, 0), List.of(), 0, 0, null);
    }

    @Test
    void kappaFirstThenAucThenSimplestConfiguration() {
        ModelConfiguration base = new ModelConfiguration(ClassifierType.NAIVE_BAYES, FeatureSelectionType.NONE,
                BalancingType.NONE);
        ModelConfiguration smote = new ModelConfiguration(ClassifierType.NAIVE_BAYES, FeatureSelectionType.CFS,
                BalancingType.SMOTE);
        ModelConfiguration under = new ModelConfiguration(ClassifierType.NAIVE_BAYES, FeatureSelectionType.NONE,
                BalancingType.UNDERSAMPLING);
        RecipeResult a = result(Recipe.of(base), 0.30, 0.70);
        RecipeResult b = result(Recipe.of(smote), 0.30, 0.75);   // stessa Kappa, AUC piu' alta: vince su a
        RecipeResult c = result(Recipe.of(under), 0.30, 0.75);   // pari a b ma piu' semplice: vince
        assertEquals(c, List.of(a, b, c).stream().min(RecipeSelector.BEST_FIRST).orElseThrow());
        RecipeResult d = result(Recipe.of(base), 0.31, 0.60);    // Kappa piu' alta: vince comunque
        assertEquals(d, List.of(a, b, c, d).stream().min(RecipeSelector.BEST_FIRST).orElseThrow());
        RecipeResult nan = result(Recipe.of(base), Double.NaN, 0.99);
        assertEquals(a, List.of(nan, a).stream().min(RecipeSelector.BEST_FIRST).orElseThrow());
    }

    @Test
    void championIsTheBestAmongTheBestVersions() {
        List<RecipeResult> all = new ArrayList<>();
        Map<ModelConfiguration, RecipeResult> best = new HashMap<>();
        Map<ModelConfiguration, Integer> chosenK = new HashMap<>();
        double kappa = 0.10;
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            Recipe r = conf.classifier().hasK() ? new Recipe(conf, 3) : Recipe.of(conf);
            RecipeResult res = result(r, kappa, 0.5);
            kappa += 0.01;   // l'ultima configurazione (IBk/CFS/SMOTE) e' la migliore
            all.add(res);
            best.put(conf, res);
            if (conf.classifier().hasK()) {
                chosenK.put(conf, 3);
            }
        }
        Selection s = RecipeSelector.choose(all, best, chosenK);
        ModelConfiguration ibkBest = new ModelConfiguration(ClassifierType.IBK, FeatureSelectionType.CFS,
                BalancingType.SMOTE);
        assertEquals(new Recipe(ibkBest, 3), s.champion());
        assertEquals(new ModelConfiguration(ClassifierType.RANDOM_FOREST, FeatureSelectionType.CFS,
                BalancingType.SMOTE), s.bestByClassifier().get(ClassifierType.RANDOM_FOREST).configuration());
        assertEquals(new Recipe(ibkBest, 3), s.recipeFor(ibkBest));
    }
}