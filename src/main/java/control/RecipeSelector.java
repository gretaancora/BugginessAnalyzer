package control;

import entity.ClassifierType;
import entity.ModelConfiguration;
import entity.Recipe;
import entity.RecipeResult;
import entity.Selection;
import exception.ClassificationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase di selezione: prova tutte le ricette su un validation set e sceglie, in quest'ordine, il k di ogni
 * configurazione di IBk, la configurazione migliore di ogni classificatore e il campione assoluto.
 * Criterio: Kappa piu' alta; a parita' AUC piu' alta, poi la configurazione piu' semplice, poi il k piu' piccolo.
 */
public final class RecipeSelector {

    /** Dal risultato migliore al peggiore; NaN conta come il valore peggiore. */
    public static final Comparator<RecipeResult> BEST_FIRST = Comparator
            .comparingDouble((RecipeResult r) -> orWorst(r.metrics().kappa())).reversed()
            .thenComparing(Comparator.comparingDouble((RecipeResult r) -> orWorst(r.metrics().auc())).reversed())
            .thenComparing(r -> r.recipe().configuration(), ModelConfiguration.SIMPLEST_FIRST)
            .thenComparingInt(r -> r.recipe().k());

    private RecipeSelector() {
    }

    private static double orWorst(double v) {
        return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
    }

    public static Selection select(RecipeEvaluator evaluator, List<Integer> kValues) throws ClassificationException {
        List<RecipeResult> all = new ArrayList<>();
        Map<ModelConfiguration, RecipeResult> bestPerConfiguration = new HashMap<>();
        Map<ModelConfiguration, Integer> chosenK = new HashMap<>();
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            List<RecipeResult> tried = new ArrayList<>();
            if (conf.classifier().hasK()) {
                for (int k : kValues) {
                    tried.add(evaluator.evaluate(new Recipe(conf, k), false));
                }
            } else {
                tried.add(evaluator.evaluate(Recipe.of(conf), false));
            }
            all.addAll(tried);
            RecipeResult best = tried.stream().min(BEST_FIRST).orElseThrow();
            bestPerConfiguration.put(conf, best);
            if (conf.classifier().hasK()) {
                chosenK.put(conf, best.recipe().k());
            }
        }
        return choose(all, bestPerConfiguration, chosenK);
    }

    /** Dalle migliori ricette di ogni configurazione alle migliori di ogni classificatore e al campione. */
    static Selection choose(List<RecipeResult> all, Map<ModelConfiguration, RecipeResult> bestPerConfiguration,
                            Map<ModelConfiguration, Integer> chosenK) {
        Map<ClassifierType, Recipe> bestByClassifier = new EnumMap<>(ClassifierType.class);
        List<RecipeResult> finalists = new ArrayList<>();
        for (ClassifierType c : ClassifierType.values()) {
            RecipeResult best = ModelConfiguration.of(c).stream().map(bestPerConfiguration::get)
                    .min(BEST_FIRST).orElseThrow();
            bestByClassifier.put(c, best.recipe());
            finalists.add(best);
        }
        Recipe champion = finalists.stream().min(BEST_FIRST).orElseThrow().recipe();
        return new Selection(chosenK, bestByClassifier, champion, all);
    }
}