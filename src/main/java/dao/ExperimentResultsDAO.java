package dao;

import entity.ClassDataset;
import entity.ClassifierType;
import entity.Comparison;
import entity.FoldOutcome;
import entity.HoldoutOutcome;
import entity.Metrics;
import entity.ModelConfiguration;
import entity.Predictions;
import entity.Recipe;
import entity.RecipeResult;
import entity.Selection;
import exception.PersistenceException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Scrive i risultati degli esperimenti di classificazione in CSV (stesso formato dei file del passo 3). */
public final class ExperimentResultsDAO {

    public static final String HOLDOUT_VALIDATION_FILE = "ml_holdout_validation.csv";
    public static final String HOLDOUT_TEST_FILE = "ml_holdout_test.csv";
    public static final String HOLDOUT_PREDICTIONS_FILE = "ml_holdout_predictions.csv";
    public static final String HOLDOUT_TESTS_FILE = "ml_holdout_tests.csv";
    public static final String CV_VALIDATION_FILE = "ml_cv_validation.csv";
    public static final String CV_FOLDS_FILE = "ml_cv_folds.csv";
    public static final String CV_SUMMARY_FILE = "ml_cv_summary.csv";
    public static final String CV_SELECTION_FILE = "ml_cv_selection.csv";
    public static final String CV_TESTS_FILE = "ml_cv_tests.csv";

    private static final List<String> RECIPE_COLUMNS = List.of("Classifier", "FeatureSelection", "Balancing", "K");
    private static final List<String> RESULT_COLUMNS = List.of("BalancedTrainRows", "BalancedTrainBuggy",
            "NumFeatures", "Features", "TP", "FP", "TN", "FN", "Accuracy", "Precision", "Recall", "AUC", "Kappa",
            "NPofB10", "NPofB20", "OptimalNPofB10", "OptimalNPofB20", "SmallestFirstNPofB10",
            "SmallestFirstNPofB20");

    private ExperimentResultsDAO() {
    }

    // ---------------------------------------------------------------- holdout

    public static void writeHoldout(HoldoutOutcome h, ClassDataset dataset, Path dir) throws PersistenceException {
        String trainSel = h.validationSplit().trainReleases();
        String valid = h.validationSplit().evaluationReleases();
        CsvTable validation = new CsvTable(concat(List.of("TrainReleases", "ValidationReleases"), RECIPE_COLUMNS,
                List.of("ChosenK", "BestForClassifier", "Champion"), RESULT_COLUMNS));
        for (RecipeResult r : h.selection().validationResults()) {
            validation.add(concat(List.of(trainSel, valid), recipe(r.recipe()),
                    selectionFlags(r.recipe(), h.selection()), result(r)));
        }
        validation.write(dir.resolve(HOLDOUT_VALIDATION_FILE));

        String train = h.testSplit().trainReleases();
        String test = h.testSplit().evaluationReleases();
        CsvTable testTable = new CsvTable(concat(List.of("TrainReleases", "TestReleases"), RECIPE_COLUMNS,
                List.of("BestForClassifier", "Champion"), RESULT_COLUMNS));
        CsvTable predictions = new CsvTable(concat(RECIPE_COLUMNS,
                List.of("Class", ClassDataset.RELEASE_ID, ClassDataset.SIZE, "Actual", "Predicted",
                        "ProbabilityYes", "RiskDensity")));
        for (RecipeResult r : h.testResults()) {
            ModelConfiguration conf = r.recipe().configuration();
            testTable.add(concat(List.of(train, test), recipe(r.recipe()),
                    List.of(yesNo(h.selection().isBest(conf)), yesNo(h.selection().isChampion(conf))), result(r)));
            Predictions p = r.predictions();
            for (int i = 0; i < p.size(); i++) {
                predictions.add(concat(recipe(r.recipe()), List.of(dataset.className(p.rowId(i)),
                        Integer.toString(dataset.releaseId(p.rowId(i))), num(p.classSize(i)), yesNo(p.actual(i)),
                        yesNo(p.predicted(i)), Double.toString(p.probabilityYes(i)),
                        Double.toString(p.riskDensity(i)))));
            }
        }
        testTable.write(dir.resolve(HOLDOUT_TEST_FILE));
        predictions.write(dir.resolve(HOLDOUT_PREDICTIONS_FILE));
    }

    // ---------------------------------------------------------------- cross-validation

    public static void writeCrossValidation(List<FoldOutcome> folds, Path dir) throws PersistenceException {
        CsvTable validation = new CsvTable(concat(List.of("Repetition", "Fold"), RECIPE_COLUMNS,
                List.of("ChosenK", "BestForClassifier", "Champion"), RESULT_COLUMNS));
        CsvTable test = new CsvTable(concat(List.of("Repetition", "Fold", "TrainRows", "TestRows"), RECIPE_COLUMNS,
                List.of("BestForClassifier", "Champion"), RESULT_COLUMNS));
        for (FoldOutcome f : folds) {
            List<String> id = List.of(Integer.toString(f.repetition() + 1), Integer.toString(f.fold() + 1));
            for (RecipeResult r : f.selection().validationResults()) {
                validation.add(concat(id, recipe(r.recipe()), selectionFlags(r.recipe(), f.selection()), result(r)));
            }
            for (RecipeResult r : f.testResults()) {
                ModelConfiguration conf = r.recipe().configuration();
                test.add(concat(id, List.of(Integer.toString(f.trainRows()), Integer.toString(f.testRows())),
                        recipe(r.recipe()), List.of(yesNo(f.selection().isBest(conf)),
                                yesNo(f.selection().isChampion(conf))), result(r)));
            }
        }
        validation.write(dir.resolve(CV_VALIDATION_FILE));
        test.write(dir.resolve(CV_FOLDS_FILE));
        summary(folds).write(dir.resolve(CV_SUMMARY_FILE));
        selectionFrequency(folds).write(dir.resolve(CV_SELECTION_FILE));
    }

    /** Media, deviazione standard e mediana sui fold di test di ogni configurazione, delle versioni migliori e del campione. */
    static CsvTable summary(List<FoldOutcome> folds) {
        List<String> header = new ArrayList<>(List.of("Model", "Classifier", "FeatureSelection", "Balancing", "N"));
        for (String m : Metrics.NAMES) {
            header.addAll(List.of(m + "_Mean", m + "_Std", m + "_Median"));
        }
        CsvTable t = new CsvTable(header);
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            t.add(summaryRow(conf.label(), conf.classifier().label(), conf.featureSelection().name(),
                    conf.balancing().name(), folds, f -> f.testResult(conf)));
        }
        for (ClassifierType c : ClassifierType.values()) {
            t.add(summaryRow("Best(" + c.label() + ")", c.label(), "SELECTED", "SELECTED", folds,
                    f -> f.bestResult(c)));
        }
        t.add(summaryRow("Champion", "SELECTED", "SELECTED", "SELECTED", folds,
                f -> f.testResult(f.selection().champion().configuration())));
        return t;
    }

    private static List<String> summaryRow(String model, String classifier, String fs, String balancing,
                                           List<FoldOutcome> folds, Function<FoldOutcome, RecipeResult> pick) {
        List<String> row = new ArrayList<>(List.of(model, classifier, fs, balancing, Integer.toString(folds.size())));
        for (String m : Metrics.NAMES) {
            double[] v = folds.stream().mapToDouble(f -> pick.apply(f).metrics().value(m))
                    .filter(x -> !Double.isNaN(x)).sorted().toArray();
            row.addAll(List.of(num(mean(v)), num(std(v)), num(median(v))));
        }
        return row;
    }

    /** Quante volte (su tutti i fold) e' stato scelto ogni k, ogni configurazione migliore e ogni campione. */
    static CsvTable selectionFrequency(List<FoldOutcome> folds) {
        Map<String, Integer> counts = new TreeMap<>();
        for (FoldOutcome f : folds) {
            Selection s = f.selection();
            s.chosenK().forEach((conf, k) -> counts.merge(key("K", conf, k), 1, Integer::sum));
            s.bestByClassifier().values().forEach(r -> counts.merge(key("BEST", r.configuration(), r.k()), 1,
                    Integer::sum));
            counts.merge(key("CHAMPION", s.champion().configuration(), s.champion().k()), 1, Integer::sum);
        }
        CsvTable t = new CsvTable(List.of("Kind", "Classifier", "FeatureSelection", "Balancing", "K", "Count",
                "Percentage"));
        counts.forEach((k, c) -> {
            List<String> row = new ArrayList<>(Arrays.asList(k.split("\\|")));
            row.add(Integer.toString(c));
            row.add(num(100.0 * c / folds.size()));
            t.add(row);
        });
        return t;
    }

    private static String key(String kind, ModelConfiguration conf, int k) {
        return String.join("|", kind, conf.classifier().label(), conf.featureSelection().name(),
                conf.balancing().name(), conf.classifier().hasK() ? Integer.toString(k) : "-");
    }

    // ---------------------------------------------------------------- test statistici

    public static void writeComparisons(List<Comparison> comparisons, Path file) throws PersistenceException {
        CsvTable t = new CsvTable(List.of("Validation", "Family", "Group", "Metric", "ModelA", "ModelB", "N",
                "MeanA", "MeanB", "Difference", "Statistic", "PValue", "PHolm", "CILow", "CIHigh", "Significant"));
        for (Comparison c : comparisons) {
            t.add(List.of(c.validation(), c.family(), c.group(), c.metric(), c.modelA(), c.modelB(),
                    Integer.toString(c.n()), num(c.meanA()), num(c.meanB()), num(c.difference()), num(c.statistic()),
                    num(c.pValue()), num(c.pHolm()), num(c.ciLow()), num(c.ciHigh()),
                    Double.isNaN(c.pHolm()) ? CsvTable.MISSING : yesNo(c.pHolm() < 0.05)));
        }
        t.write(file);
    }

    // ---------------------------------------------------------------- utilita'

    private static List<String> recipe(Recipe r) {
        ModelConfiguration c = r.configuration();
        return List.of(c.classifier().label(), c.featureSelection().name(), c.balancing().name(),
                c.classifier().hasK() ? Integer.toString(r.k()) : "-");
    }

    /** ChosenK (solo IBk), BestForClassifier e Champion di una ricetta provata sul validation set. */
    private static List<String> selectionFlags(Recipe r, Selection s) {
        ModelConfiguration c = r.configuration();
        String chosenK = c.classifier().hasK() ? yesNo(s.chosenK().get(c) == r.k()) : "-";
        return List.of(chosenK, yesNo(s.bestByClassifier().get(c.classifier()).equals(r)),
                yesNo(s.champion().equals(r)));
    }

    private static List<String> result(RecipeResult r) {
        Metrics m = r.metrics();
        return List.of(Integer.toString(r.trainRows()), Integer.toString(r.trainBuggy()),
                Integer.toString(r.features().size()), String.join(";", r.features()), num(m.tp()), num(m.fp()),
                num(m.tn()), num(m.fn()), num(m.accuracy()), num(m.precision()), num(m.recall()), num(m.auc()),
                num(m.kappa()), num(m.npofb10()), num(m.npofb20()), num(m.optimalNPofB10()),
                num(m.optimalNPofB20()), num(m.smallestFirstNPofB10()), num(m.smallestFirstNPofB20()));
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> p : parts) {
            all.addAll(p);
        }
        return all;
    }

    private static String yesNo(boolean b) {
        return b ? ClassDataset.YES : ClassDataset.NO;
    }

    /** Numero con 6 decimali (interi senza decimali); NaN diventa "?", come i valori mancanti. */
    static String num(double v) {
        if (Double.isNaN(v)) {
            return CsvTable.MISSING;
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Inf" : "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    static double mean(double[] v) {
        return v.length == 0 ? Double.NaN : Arrays.stream(v).average().orElseThrow();
    }

    static double std(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        return Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).sum() / (v.length - 1));
    }

    /** Mediana di un array gia' ordinato. */
    static double median(double[] sorted) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}