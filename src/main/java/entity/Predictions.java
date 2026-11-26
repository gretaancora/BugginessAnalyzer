package entity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Predizioni di un modello su un insieme di valutazione: per ogni riga l'identificativo nel dataset,
 * l'etichetta vera, quella predetta e la probabilita' predetta di "yes". Servono per le metriche e per il
 * bootstrap appaiato dell'holdout.
 */
public final class Predictions {

    private final int[] rowIds;
    private final boolean[] actual;
    private final boolean[] predicted;
    private final double[] probabilityYes;
    private int[] ascendingOrder;

    public Predictions(int[] rowIds, boolean[] actual, boolean[] predicted, double[] probabilityYes) {
        int n = rowIds.length;
        if (actual.length != n || predicted.length != n || probabilityYes.length != n) {
            throw new IllegalArgumentException("Array di lunghezze diverse");
        }
        this.rowIds = rowIds.clone();
        this.actual = actual.clone();
        this.predicted = predicted.clone();
        this.probabilityYes = probabilityYes.clone();
    }

    public int size() {
        return rowIds.length;
    }

    public int rowId(int i) {
        return rowIds[i];
    }

    public boolean actual(int i) {
        return actual[i];
    }

    public boolean predicted(int i) {
        return predicted[i];
    }

    public double probabilityYes(int i) {
        return probabilityYes[i];
    }

    /** Indici ordinati per probabilita' crescente (calcolati una volta: il bootstrap li riusa). */
    public synchronized int[] ascendingOrder() {
        if (ascendingOrder == null) {
            ascendingOrder = IntStream.range(0, size()).boxed()
                    .sorted(Comparator.comparingDouble(i -> probabilityYes[i]))
                    .mapToInt(Integer::intValue).toArray();
        }
        return ascendingOrder;
    }

    /** Vero se le due predizioni riguardano le stesse righe nello stesso ordine (condizione dei test appaiati). */
    public boolean sameRows(Predictions other) {
        return Arrays.equals(rowIds, other.rowIds);
    }
}