package control;

import config.AppConfig;
import config.ExperimentSettings;
import dao.ExperimentResultsDAO;
import dao.WekaDatasetDAO;
import entity.ClassDataset;
import entity.ClassifierType;
import entity.Comparison;
import entity.FoldOutcome;
import entity.HoldoutOutcome;
import entity.Metrics;
import entity.ModelConfiguration;
import entity.Recipe;
import entity.RecipeResult;
import entity.ReleaseSplit;
import entity.Selection;
import exception.ClassificationException;
import exception.ConfigException;
import exception.PersistenceException;
import weka.core.Instance;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Passo 5 (Milestone 2): confronto di RandomForest, NaiveBayes e IBk sul dataset finale, con e senza feature
 * selection (CFS) e balancing (undersampling, SMOTE), validati con ordered holdout 80-20 per release e con
 * 10x10 cross-validation. In entrambe le validazioni le scelte (k di IBk, configurazione migliore di ogni
 * classificatore, campione) si fanno su un validation set ricavato dal training; i dati di test servono solo a
 * misurare e a confrontare, con test statistici. L'holdout viene eseguito e scritto per primo (e' veloce).
 */
public final class ClassificationController {

    public static final String ARFF_SUFFIX = "_dataset.arff";

    private static final Logger LOG = Logger.getLogger(ClassificationController.class.getName());

    private final AppConfig config;

    public ClassificationController(AppConfig config) {
        this.config = config;
    }

    public void run() throws ConfigException, PersistenceException, ClassificationException {
        ExperimentSettings settings = config.experimentSettings().validate();
        Path out = config.outputDir();
        Path csv = out.resolve(new LabelingController(config).datasetFileName());
        ClassDataset dataset = WekaDatasetDAO.read(csv);
        WekaDatasetDAO.writeArff(dataset, out.resolve(config.projectName() + ARFF_SUFFIX));
        LOG.info(describe(dataset, settings));

        // 1. ordered holdout
        long start = System.nanoTime();
        HoldoutOutcome holdout = new HoldoutExperiment(dataset, settings).run();
        List<Comparison> holdoutTests = ExperimentComparisons.holdout(holdout, settings.bootstrapSamples(),
                settings.seed());
        ExperimentResultsDAO.writeHoldout(holdout, dataset, out);
        ExperimentResultsDAO.writeComparisons(holdoutTests, out.resolve(ExperimentResultsDAO.HOLDOUT_TESTS_FILE));
        LOG.info(holdoutReport(holdout, holdoutTests, seconds(start)));

        // 2. 10x10 cross-validation annidata
        start = System.nanoTime();
        List<FoldOutcome> folds = new CrossValidationExperiment(dataset, settings).run();
        List<Comparison> cvTests = ExperimentComparisons.crossValidation(folds);
        ExperimentResultsDAO.writeCrossValidation(folds, out);
        ExperimentResultsDAO.writeComparisons(cvTests, out.resolve(ExperimentResultsDAO.CV_TESTS_FILE));
        LOG.info(cvReport(folds, cvTests, settings, seconds(start)));
        LOG.info(() -> "Risultati scritti in " + out.toAbsolutePath() + " (file ml_*.csv)");
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    // ---------------------------------------------------------------- riepiloghi

    static String describe(ClassDataset d, ExperimentSettings s) {
        int yes = d.instances().classAttribute().indexOfValue(ClassDataset.YES);
        int buggy = 0;
        int withMissing = 0;
        int buggyWithMissing = 0;
        for (Instance inst : d.instances()) {
            boolean b = (int) inst.classValue() == yes;
            boolean missing = inst.hasMissingValue();
            buggy += b ? 1 : 0;
            withMissing += missing ? 1 : 0;
            buggyWithMissing += b && missing ? 1 : 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Dataset: %d righe, %d buggy (%.1f%%), %d feature: %s%n", d.size(), buggy,
                100.0 * buggy / d.size(), d.featureNames().size(), d.featureNames()));
        sb.append(String.format(Locale.ROOT, "Righe con valori mancanti: %d (buggy: %d; per SMOTE le loro distanze "
                + "non sono definite)%n", withMissing, buggyWithMissing));
        sb.append(String.format(Locale.ROOT, "Configurazioni: %d; k di IBk: %s; CV %dx%d; thread: %d",
                ModelConfiguration.all().size(), s.kValues(), s.repetitions(), s.folds(), s.threads()));
        return sb.toString();
    }

    static String holdoutReport(HoldoutOutcome h, List<Comparison> tests, double seconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "=== Ordered holdout (%.0f s) ===%n", seconds));
        sb.append("Selezione: ").append(split(h.validationSplit(), "validation")).append('\n');
        sb.append("Test:      ").append(split(h.testSplit(), "test")).append('\n');
        sb.append(selectionReport(h.selection()));
        sb.append("Risultati sul test (* = versione migliore scelta sul validation, ** = campione):\n");
        sb.append(table(h.testResults(), r -> mark(h.selection(), r.recipe().configuration())));
        sb.append(bestTests(tests));
        return sb.toString();
    }

    private static String split(ReleaseSplit s, String what) {
        return String.format(Locale.ROOT, "training release %s (%d righe, %d buggy), %s release %s (%d righe, "
                        + "%d buggy), training = %.1f%%", s.trainReleases(), s.trainRows(), s.trainBuggy(), what,
                s.evaluationReleases(), s.evaluationRows(), s.evaluationBuggy(), 100 * s.trainFraction());
    }

    private static String selectionReport(Selection s) {
        StringBuilder sb = new StringBuilder("Scelte sul validation set:\n");
        for (ModelConfiguration conf : ModelConfiguration.of(ClassifierType.IBK)) {
            sb.append(String.format(Locale.ROOT, "  k di %-24s = %d%n", conf.label(), s.chosenK().get(conf)));
        }
        for (ClassifierType c : ClassifierType.values()) {
            Recipe best = s.bestByClassifier().get(c);
            RecipeResult r = s.validationResults().stream().filter(v -> v.recipe().equals(best)).findFirst()
                    .orElseThrow();
            sb.append(String.format(Locale.ROOT, "  migliore %-13s: %-30s Kappa validation %.3f%n", c.label(),
                    best.label(), r.metrics().kappa()));
        }
        sb.append("  campione: ").append(s.champion().label()).append('\n');
        return sb.toString();
    }

    private static String mark(Selection s, ModelConfiguration conf) {
        if (s.isChampion(conf)) {
            return "**";
        }
        return s.isBest(conf) ? "*" : "";
    }

    private static String table(List<RecipeResult> results, Function<RecipeResult, String> marker) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-34s %7s %7s %7s %7s %7s%n", "Ricetta",
                "Kappa", "AUC", "Prec", "Recall", "Acc"));
        for (RecipeResult r : results) {
            Metrics m = r.metrics();
            sb.append(String.format(Locale.ROOT, "  %-34s %7.3f %7.3f %7.3f %7.3f %7.3f%n",
                    r.recipe().label() + marker.apply(r), m.kappa(), m.auc(), m.precision(), m.recall(),
                    m.accuracy()));
        }
        return sb.toString();
    }

    /** Confronti tra le versioni migliori su Kappa e AUC. */
    private static String bestTests(List<Comparison> tests) {
        StringBuilder sb = new StringBuilder("Versioni migliori a confronto (p corretto con Holm):\n");
        for (Comparison c : tests) {
            if (c.family().equals("BEST") && (c.metric().equals("Kappa") || c.metric().equals("AUC"))) {
                String p = c.pHolm() < 0.001 ? "< 0.001" : String.format(Locale.ROOT, "%.3f", c.pHolm());
                sb.append(String.format(Locale.ROOT, "  %-5s %-20s vs %-20s diff %+.3f  p %s%s%n", c.metric(),
                        c.modelA(), c.modelB(), c.difference(), p, c.pHolm() < 0.05 ? "  *" : ""));
            }
        }
        return sb.toString();
    }

    static String cvReport(List<FoldOutcome> folds, List<Comparison> tests, ExperimentSettings s, double seconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "=== 10x10 cross-validation annidata (%d fold, %.0f s) ===%n",
                folds.size(), seconds));
        sb.append(String.format(Locale.ROOT, "  %-34s %16s  %16s%n", "Modello", "Kappa", "AUC"));
        for (ModelConfiguration conf : ModelConfiguration.all()) {
            sb.append(row(conf.label(), folds, f -> f.testResult(conf)));
        }
        for (ClassifierType c : ClassifierType.values()) {
            sb.append(row("Best(" + c.label() + ")", folds, f -> f.bestResult(c)));
        }
        sb.append("Configurazione scelta per ogni classificatore (fold su ").append(folds.size()).append("):\n");
        for (ClassifierType c : ClassifierType.values()) {
            Map<String, Integer> freq = new TreeMap<>();
            folds.forEach(f -> freq.merge(f.selection().bestByClassifier().get(c).configuration().label(), 1,
                    Integer::sum));
            sb.append("  ").append(c.label()).append(": ").append(freq).append('\n');
        }
        int maxK = s.kValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
        long atBorder = folds.stream().flatMap(f -> f.selection().chosenK().values().stream())
                .filter(k -> k == maxK).count();
        long choices = (long) folds.size() * ModelConfiguration.of(ClassifierType.IBK).size();
        sb.append(String.format(Locale.ROOT, "k = %d (limite superiore) scelto %d volte su %d%s%n", maxK, atBorder,
                choices, atBorder > choices / 4 ? ": conviene allargare l'intervallo di k" : ""));
        sb.append(bestTests(tests));
        return sb.toString();
    }

    private static String row(String label, List<FoldOutcome> folds, Function<FoldOutcome, RecipeResult> pick) {
        double[] kappa = folds.stream().mapToDouble(f -> pick.apply(f).metrics().kappa()).toArray();
        double[] auc = folds.stream().mapToDouble(f -> pick.apply(f).metrics().auc()).toArray();
        return String.format(Locale.ROOT, "  %-34s %6.3f +/- %.3f  %6.3f +/- %.3f%n", label, mean(kappa), std(kappa),
                mean(auc), std(auc));
    }

    private static double mean(double[] v) {
        return Arrays.stream(v).filter(x -> !Double.isNaN(x)).average().orElse(Double.NaN);
    }

    private static double std(double[] v) {
        double[] x = Arrays.stream(v).filter(d -> !Double.isNaN(d)).toArray();
        if (x.length < 2) {
            return Double.NaN;
        }
        double m = mean(x);
        return Math.sqrt(Arrays.stream(x).map(d -> (d - m) * (d - m)).sum() / (x.length - 1));
    }
}