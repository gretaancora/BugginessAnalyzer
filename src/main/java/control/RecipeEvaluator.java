package control;

import entity.ClassDataset;
import entity.Metrics;
import entity.Predictions;
import entity.Recipe;
import entity.RecipeResult;
import exception.ClassificationException;
import weka.classifiers.Classifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Utils;

/**
 * Addestra una ricetta sul training di un {@link EvaluationSplit} e la valuta sull'insieme di valutazione.
 * I classificatori usano i parametri di default di Weka; per IBk solo k viene dalla ricetta.
 */
public final class RecipeEvaluator {

    private final EvaluationSplit split;
    private final int seed;

    public RecipeEvaluator(EvaluationSplit split, int seed) {
        this.split = split;
        this.seed = seed;
    }

    public EvaluationSplit split() {
        return split;
    }

    /**
     * @param keepPredictions se vero il risultato contiene le predizioni per riga (servono al bootstrap
     *                        dell'holdout); altrimenti solo le metriche
     */
    public RecipeResult evaluate(Recipe recipe, boolean keepPredictions) throws ClassificationException {
        EvaluationSplit.Prepared data = split.prepared(recipe.configuration().featureSelection(),
                recipe.configuration().balancing());
        Instances train = data.train();
        Instances eval = data.evaluation();
        int yes = train.classAttribute().indexOfValue(ClassDataset.YES);
        try {
            Classifier classifier = create(recipe);
            classifier.buildClassifier(train);
            int n = eval.numInstances();
            boolean[] actual = new boolean[n];
            boolean[] predicted = new boolean[n];
            double[] probability = new double[n];
            for (int i = 0; i < n; i++) {
                double[] dist = classifier.distributionForInstance(eval.instance(i));
                actual[i] = (int) eval.instance(i).classValue() == yes;
                predicted[i] = Utils.maxIndex(dist) == yes;   // come Weka: a parita' vince la prima classe (no)
                probability[i] = dist[yes];
            }
            Predictions predictions = new Predictions(split.evaluationRowIds(), actual, predicted, probability);
            Metrics metrics = MetricsCalculator.compute(predictions);
            int trainBuggy = train.attributeStats(train.classIndex()).nominalCounts[yes];
            return new RecipeResult(recipe, metrics, data.features(), train.numInstances(), trainBuggy,
                    keepPredictions ? predictions : null);
        } catch (Exception e) {
            throw new ClassificationException("Ricetta " + recipe.label() + " non riuscita: " + e.getMessage(), e);
        }
    }

    private Classifier create(Recipe recipe) {
        return switch (recipe.classifier()) {
            case RANDOM_FOREST -> {
                RandomForest rf = new RandomForest();
                rf.setSeed(seed);
                yield rf;
            }
            case NAIVE_BAYES -> new NaiveBayes();
            case IBK -> new IBk(recipe.k());
        };
    }
}