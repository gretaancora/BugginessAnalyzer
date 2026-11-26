package control;

import config.ExperimentSettings;
import entity.ClassDataset;
import entity.FoldOutcome;
import entity.ModelConfiguration;
import entity.RecipeResult;
import entity.Selection;
import exception.ClassificationException;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 10x10 cross-validation stratificata e annidata. Per ogni ripetizione r le righe vengono rimescolate con il
 * seme (seed + r) e divise in 10 fold stratificati, come fa Evaluation.crossValidateModel di Weka; ogni fold
 * fa da test una volta. Dentro ogni fold:
 * <ol>
 *   <li>il training esterno viene diviso in modo stratificato in training interno (80%) e validation (20%), con
 *   un seme fisso per fold: lo split e' lo stesso per tutte le ricette; sul validation si sceglie;</li>
 *   <li>tutte le configurazioni, con il k scelto, si addestrano sul training esterno e si valutano sul fold.</li>
 * </ol>
 * Tutte le configurazioni vedono gli stessi fold, quindi i confronti sono appaiati. I fold sono indipendenti e
 * vengono eseguiti in parallelo; il risultato non dipende dall'ordine di esecuzione.
 */
public final class CrossValidationExperiment {

    private static final Logger LOG = Logger.getLogger(CrossValidationExperiment.class.getName());

    private final ClassDataset dataset;
    private final ExperimentSettings settings;

    public CrossValidationExperiment(ClassDataset dataset, ExperimentSettings settings) {
        this.dataset = dataset;
        this.settings = settings;
    }

    /** Un fold: ripetizione, indice e righe gia' rimescolate e stratificate della ripetizione. */
    record FoldData(int repetition, int fold, Instances stratified) {

        Instances train(int folds, int seed) {
            return stratified.trainCV(folds, fold, new Random(seed));
        }

        Instances test(int folds) {
            return stratified.testCV(folds, fold);
        }
    }

    /**
     * I fold di tutte le ripetizioni. Per ogni ripetizione si tiene una sola copia rimescolata e stratificata
     * (in sola lettura, condivisa tra i thread); training e test del fold vengono estratti dentro il task.
     */
    List<FoldData> folds() {
        List<FoldData> folds = new ArrayList<>();
        int k = settings.folds();
        for (int r = 0; r < settings.repetitions(); r++) {
            Instances data = new Instances(dataset.instances());
            data.randomize(new Random(settings.seed() + r));
            data.stratify(k);
            for (int f = 0; f < k; f++) {
                folds.add(new FoldData(r, f, data));
            }
        }
        return folds;
    }

    private int foldSeed(int repetition, int fold) {
        return settings.seed() * 1_000_003 + repetition * 1_000 + fold;
    }

    public List<FoldOutcome> run() throws ClassificationException {
        List<FoldData> folds = folds();
        long start = System.nanoTime();
        AtomicInteger done = new AtomicInteger();
        List<Future<FoldOutcome>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(settings.threads());
        try {
            for (FoldData fd : folds) {
                Callable<FoldOutcome> task = () -> {
                    FoldOutcome outcome = runFold(fd);
                    int n = done.incrementAndGet();
                    double elapsed = (System.nanoTime() - start) / 1e9;
                    double eta = elapsed / n * (folds.size() - n);
                    LOG.info(() -> String.format(Locale.ROOT, "CV: fold %d/%d completati (r=%d, f=%d), "
                                    + "trascorsi %.0f s, stima al termine %.0f s", n, folds.size(),
                            fd.repetition() + 1, fd.fold() + 1, elapsed, eta));
                    return outcome;
                };
                futures.add(pool.submit(task));
            }
            List<FoldOutcome> outcomes = new ArrayList<>();
            for (Future<FoldOutcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationException("Cross-validation interrotta", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new ClassificationException("Cross-validation non riuscita: " + cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }
    }

    FoldOutcome runFold(FoldData fd) throws ClassificationException {
        int seed = foldSeed(fd.repetition(), fd.fold());
        Instances train = fd.train(settings.folds(), seed);
        Instances test = fd.test(settings.folds());
        // 1. selezione sulla validation interna (split stratificato 80/20 del training esterno)
        Instances shuffled = new Instances(train);
        shuffled.randomize(new Random(seed));
        shuffled.stratify(settings.innerFolds());
        Instances innerTrain = shuffled.trainCV(settings.innerFolds(), 0);
        Instances validation = shuffled.testCV(settings.innerFolds(), 0);
        Selection selection = RecipeSelector.select(
                new RecipeEvaluator(new EvaluationSplit(innerTrain, validation, settings.seed()), settings.seed()),
                settings.kValues());

        // 2. tutte le configurazioni sul fold di test
        RecipeEvaluator evaluator = new RecipeEvaluator(new EvaluationSplit(train, test, settings.seed()),
                settings.seed());
        List<RecipeResult> results = new ArrayList<>();
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            results.add(evaluator.evaluate(selection.recipeFor(conf), false));
        }
        return new FoldOutcome(fd.repetition(), fd.fold(), train.numInstances(), test.numInstances(),
                selection, results);
    }
}