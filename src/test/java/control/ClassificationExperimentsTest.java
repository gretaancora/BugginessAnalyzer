package control;

import config.ExperimentSettings;
import dao.CsvTable;
import dao.ExperimentResultsDAO;
import dao.WekaDatasetDAO;
import entity.ClassDataset;
import entity.ClassifierType;
import entity.Comparison;
import entity.FoldOutcome;
import entity.HoldoutOutcome;
import entity.Metrics;
import entity.ModelConfiguration;
import entity.RecipeResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prova completa dei due esperimenti su un dataset sintetico piccolo (CV ridotta a 2x3). */
class ClassificationExperimentsTest {

    private static final ExperimentSettings SETTINGS = new ExperimentSettings(2, 3, 0.8, 0.2, List.of(1, 3), 200,
            1, 2);

    private static ClassDataset dataset() throws Exception {
        return WekaDatasetDAO.fromTable(SyntheticDataset.table(5, 120, 7), "test");
    }

    @Test
    void holdoutSelectsOnValidationAndMeasuresAllConfigurationsOnTest() throws Exception {
        ClassDataset d = dataset();
        HoldoutOutcome h = new HoldoutExperiment(d, SETTINGS).run();
        assertEquals(4, h.testSplit().lastTrainRelease());        // 480/600 = 80%
        assertEquals(3, h.validationSplit().lastTrainRelease());  // 360/480 = 75%
        assertEquals(18, h.testResults().size());
        // validation: 6 configurazioni per RF e NB, 6 x 2 valori di k per IBk
        assertEquals(6 + 6 + 12, h.selection().validationResults().size());
        int size = d.instances().attribute(ClassDataset.SIZE).index();
        for (RecipeResult r : h.testResults()) {
            assertEquals(120, r.predictions().size());
            // NPofBX usa Size originale (non il logaritmo)
            for (int i = 0; i < r.predictions().size(); i++) {
                assertEquals(d.instances().instance(r.predictions().rowId(i)).value(size),
                        r.predictions().classSize(i), 0);
            }
            assertTrue(r.metrics().npofb20() >= r.metrics().npofb10());
            assertTrue(r.metrics().npofb20() <= r.metrics().optimalNPofB20() + 1e-12);
            assertEquals(h.selection().recipeFor(r.recipe().configuration()), r.recipe());
            if (r.recipe().configuration().balancing() != entity.BalancingType.NONE) {
                assertEquals(r.trainRows(), 2 * r.trainBuggy(), 2.0);   // training bilanciato 1:1
            }
        }
        // stesse feature per le tre configurazioni CFS con lo stesso balancing: CFS non dipende dal classificatore
        Set<List<String>> cfsFeatures = new HashSet<>();
        for (ClassifierType c : ClassifierType.values()) {
            cfsFeatures.add(h.testResult(new ModelConfiguration(c, entity.FeatureSelectionType.CFS,
                    entity.BalancingType.NONE)).features());
        }
        assertEquals(1, cfsFeatures.size());

        List<Comparison> tests = ExperimentComparisons.holdout(h, SETTINGS.bootstrapSamples(), 1);
        assertEquals((15 + 18 + 3) * Metrics.NAMES.length, tests.size());
        assertTrue(tests.stream().allMatch(c -> Double.isNaN(c.pHolm()) || c.pHolm() >= c.pValue()));

        Path dir = Files.createTempDirectory("ml-holdout");
        ExperimentResultsDAO.writeHoldout(h, d, dir);
        ExperimentResultsDAO.writeComparisons(tests, dir.resolve(ExperimentResultsDAO.HOLDOUT_TESTS_FILE));
        assertEquals(18 * 120, CsvTable.read(dir.resolve(ExperimentResultsDAO.HOLDOUT_PREDICTIONS_FILE)).rows().size());
        CsvTable validation = CsvTable.read(dir.resolve(ExperimentResultsDAO.HOLDOUT_VALIDATION_FILE));
        int champion = validation.column("Champion");
        assertEquals(1, validation.rows().stream().filter(r -> r.get(champion).equals("yes")).count());
    }

    @Test
    void nestedCrossValidationIsDeterministicAndPaired() throws Exception {
        ClassDataset d = dataset();
        List<FoldOutcome> folds = new CrossValidationExperiment(d, SETTINGS).run();
        assertEquals(6, folds.size());
        for (FoldOutcome f : folds) {
            assertEquals(600, f.trainRows() + f.testRows());
            assertEquals(18, f.testResults().size());
        }
        // stessa esecuzione con un solo thread: stessi risultati (non dipendono dal parallelismo)
        ExperimentSettings single = new ExperimentSettings(2, 3, 0.8, 0.2, List.of(1, 3), 200, 1, 1);
        List<FoldOutcome> again = new CrossValidationExperiment(d, single).run();
        for (int i = 0; i < folds.size(); i++) {
            for (ModelConfiguration conf : ModelConfiguration.all()) {
                assertEquals(folds.get(i).testResult(conf).metrics(), again.get(i).testResult(conf).metrics());
            }
            assertEquals(folds.get(i).selection().champion(), again.get(i).selection().champion());
        }
        List<Comparison> tests = ExperimentComparisons.crossValidation(folds);
        assertEquals((15 + 18 + 3) * Metrics.NAMES.length, tests.size());

        Path dir = Files.createTempDirectory("ml-cv");
        ExperimentResultsDAO.writeCrossValidation(folds, dir);
        assertEquals(6 * 18, CsvTable.read(dir.resolve(ExperimentResultsDAO.CV_FOLDS_FILE)).rows().size());
        assertEquals(18 + 3 + 1, CsvTable.read(dir.resolve(ExperimentResultsDAO.CV_SUMMARY_FILE)).rows().size());
    }
}