package dao;

import entity.ClassDataset;
import exception.PersistenceException;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.converters.ArffSaver;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Legge il dataset finale (CSV del passo 4) come {@link ClassDataset} e lo salva in ARFF per Weka. */
public final class WekaDatasetDAO {

    /** Colonne che non sono feature. */
    private static final Set<String> NOT_FEATURES = Set.of("Project", "Class", ClassDataset.RELEASE_ID,
            ClassDataset.CLASS_ATTRIBUTE);

    private WekaDatasetDAO() {
    }

    public static ClassDataset read(Path csv) throws PersistenceException {
        return fromTable(CsvTable.read(csv), csv.getFileName().toString().replaceFirst("\\.csv$", ""));
    }

    /**
     * Converte la tabella: ogni colonna diversa da Project, Class, ReleaseID e Buggy e' una feature numerica;
     * "?" e' un valore mancante. Le righe vengono ordinate per ReleaseID (ordinamento stabile).
     */
    public static ClassDataset fromTable(CsvTable table, String relation) throws PersistenceException {
        int classCol = required(table, "Class");
        int releaseCol = required(table, ClassDataset.RELEASE_ID);
        int buggyCol = required(table, ClassDataset.CLASS_ATTRIBUTE);
        List<Integer> featureCols = new ArrayList<>();
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute(ClassDataset.ROW_ID));
        attributes.add(new Attribute(ClassDataset.RELEASE_ID));
        for (int c = 0; c < table.header().size(); c++) {
            if (!NOT_FEATURES.contains(table.header().get(c))) {
                featureCols.add(c);
                attributes.add(new Attribute(table.header().get(c)));
            }
        }
        attributes.add(new Attribute(ClassDataset.CLASS_ATTRIBUTE, List.of(ClassDataset.NO, ClassDataset.YES)));

        List<List<String>> rows = new ArrayList<>(table.rows());
        rows.sort(Comparator.comparingInt(r -> Integer.parseInt(r.get(releaseCol))));

        Instances data = new Instances(relation, attributes, rows.size());
        data.setClassIndex(attributes.size() - 1);
        List<String> classNames = new ArrayList<>();
        int line = 0;
        for (List<String> row : rows) {
            double[] values = new double[attributes.size()];
            values[0] = line;
            values[1] = parse(row.get(releaseCol), ClassDataset.RELEASE_ID, line);
            for (int f = 0; f < featureCols.size(); f++) {
                int col = featureCols.get(f);
                values[f + 2] = parse(row.get(col), table.header().get(col), line);
            }
            String buggy = row.get(buggyCol);
            if (!ClassDataset.YES.equals(buggy) && !ClassDataset.NO.equals(buggy)) {
                throw new PersistenceException("Valore di Buggy non valido alla riga " + line + ": " + buggy);
            }
            values[values.length - 1] = ClassDataset.YES.equals(buggy) ? 1 : 0;
            data.add(new DenseInstance(1.0, values));
            classNames.add(row.get(classCol));
            line++;
        }
        return new ClassDataset(data, classNames);
    }

    private static int required(CsvTable table, String column) throws PersistenceException {
        int c = table.column(column);
        if (c < 0) {
            throw new PersistenceException("Colonna mancante nel dataset: " + column);
        }
        return c;
    }

    private static double parse(String raw, String column, int line) throws PersistenceException {
        if (CsvTable.MISSING.equals(raw)) {
            return Utils.missingValue();
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new PersistenceException("Valore non numerico in " + column + " alla riga " + line + ": " + raw, e);
        }
    }

    /** Salva il dataset in ARFF senza RowId, per aprirlo con l'interfaccia grafica di Weka. */
    public static void writeArff(ClassDataset dataset, Path file) throws PersistenceException {
        try {
            Remove remove = new Remove();
            remove.setAttributeIndices("1");
            remove.setInputFormat(dataset.instances());
            Instances out = Filter.useFilter(dataset.instances(), remove);
            out.setRelationName(dataset.instances().relationName());
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArffSaver saver = new ArffSaver();
            saver.setInstances(out);
            saver.setFile(file.toFile());
            saver.writeBatch();
        } catch (IOException e) {
            throw new PersistenceException("Impossibile scrivere " + file + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PersistenceException("Conversione ARFF non riuscita: " + e.getMessage(), e);
        }
    }
}