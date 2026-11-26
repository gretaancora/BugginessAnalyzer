package control;

import config.ExperimentSettings;
import entity.ClassDataset;
import entity.HoldoutOutcome;
import entity.ModelConfiguration;
import entity.RecipeResult;
import entity.ReleaseSplit;
import entity.Selection;
import exception.ClassificationException;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Ordered holdout 80-20 per release, in due fasi.
 * <ol>
 *   <li>Selezione: dentro il training si ripete il taglio per release (circa 80-20); si addestra sulle release
 *   piu' vecchie e si sceglie sulle successive (validation set).</li>
 *   <li>Test: tutte le configurazioni, con il k scelto, si riaddestrano sull'intero training (feature selection
 *   e balancing ricalcolati) e si valutano una sola volta sulle release di test.</li>
 * </ol>
 */
public final class HoldoutExperiment {

    private final ClassDataset dataset;
    private final ExperimentSettings settings;

    public HoldoutExperiment(ClassDataset dataset, ExperimentSettings settings) {
        this.dataset = dataset;
        this.settings = settings;
    }

    /** I due tagli: [0] training/test, [1] selezione/validation dentro il training. */
    public ReleaseSplit[] splits() {
        SortedMap<Integer, int[]> counts = dataset.countsPerRelease();
        ReleaseSplit test = ReleaseSplit.closestTo(counts, settings.holdoutTrainFraction());
        SortedMap<Integer, int[]> trainCounts = new TreeMap<>(counts.headMap(test.lastTrainRelease() + 1));
        ReleaseSplit validation = ReleaseSplit.closestTo(trainCounts, 1 - settings.validationFraction());
        return new ReleaseSplit[]{test, validation};
    }

    public HoldoutOutcome run() throws ClassificationException {
        ReleaseSplit[] splits = splits();
        ReleaseSplit test = splits[0];
        ReleaseSplit validation = splits[1];

        Instances selectionTrain = dataset.releases(r -> r <= validation.lastTrainRelease());
        Instances validationSet = dataset.releases(r -> r > validation.lastTrainRelease()
                && r <= test.lastTrainRelease());
        Selection selection = RecipeSelector.select(
                new RecipeEvaluator(new EvaluationSplit(selectionTrain, validationSet, settings.seed()),
                        settings.seed()),
                settings.kValues());

        Instances train = dataset.releases(r -> r <= test.lastTrainRelease());
        Instances testSet = dataset.releases(r -> r > test.lastTrainRelease());
        RecipeEvaluator evaluator = new RecipeEvaluator(new EvaluationSplit(train, testSet, settings.seed()),
                settings.seed());
        List<RecipeResult> results = new ArrayList<>();
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            results.add(evaluator.evaluate(selection.recipeFor(conf), true));
        }
        return new HoldoutOutcome(test, validation, selection, results);
    }
}