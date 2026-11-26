package entity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Predizioni di un modello su un insieme di valutazione: per ogni riga l'identificativo nel dataset,
 * l'etichetta vera, quella predetta, la probabilita' predetta di "yes" e la dimensione della classe (Size,
 * righe di codice non commentate, valore originale). Servono per le metriche e per il bootstrap appaiato
 * dell'holdout.
 */
public final class Predictions {

    private final int[] rowIds;
    private final boolean[] actual;
    private final boolean[] predicted;
    private final double[] probabilityYes;
    private final double[] size;
    private int[] ascendingOrder;
    private int[] effortOrder;
    private int[] optimalEffortOrder;
    private int[] smallestFirstOrder;

    public Predictions(int[] rowIds, boolean[] actual, boolean[] predicted, double[] probabilityYes, double[] size) {
        int n = rowIds.length;
        if (actual.length != n || predicted.length != n || probabilityYes.length != n || size.length != n) {
            throw new IllegalArgumentException("Array di lunghezze diverse");
        }
        for (double s : size) {
            if (Double.isNaN(s) || s < 0) {
                throw new IllegalArgumentException("Size mancante o negativa: " + s);
            }
        }
        this.rowIds = rowIds.clone();
        this.actual = actual.clone();
        this.predicted = predicted.clone();
        this.probabilityYes = probabilityYes.clone();
        this.size = size.clone();
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

    /** Righe di codice della classe (Size). */
    public double classSize(int i) {
        return size[i];
    }

    /** Densita' di rischio: probabilita' predetta / Size (Size almeno 1, per le classi senza righe). */
    public double riskDensity(int i) {
        return probabilityYes[i] / Math.max(size[i], 1);
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

    /**
     * Ordine di ispezione per NPofBX: densita' di rischio decrescente; a parita' probabilita' piu' alta, poi
     * RowId crescente (ordine del dataset). La regola non usa le etichette ed e' la stessa per tutti i modelli.
     */
    public synchronized int[] effortOrder() {
        if (effortOrder == null) {
            effortOrder = IntStream.range(0, size()).boxed()
                    .sorted(Comparator.comparingDouble((Integer i) -> riskDensity(i)).reversed()
                            .thenComparing(Comparator.comparingDouble((Integer i) -> probabilityYes[i]).reversed())
                            .thenComparingInt(i -> rowIds[i]))
                    .mapToInt(Integer::intValue).toArray();
        }
        return effortOrder;
    }

    /** Ordine ottimo (riferimento): prima le classi buggy dalla piu' piccola, poi le altre; a parita' RowId. */
    public synchronized int[] optimalEffortOrder() {
        if (optimalEffortOrder == null) {
            optimalEffortOrder = IntStream.range(0, size()).boxed()
                    .sorted(Comparator.comparing((Integer i) -> !actual[i])
                            .thenComparingDouble(i -> size[i])
                            .thenComparingInt(i -> rowIds[i]))
                    .mapToInt(Integer::intValue).toArray();
        }
        return optimalEffortOrder;
    }

    /** Riferimento senza modello: classi dalla piu' piccola alla piu' grande; a parita' RowId. */
    public synchronized int[] smallestFirstOrder() {
        if (smallestFirstOrder == null) {
            smallestFirstOrder = IntStream.range(0, size()).boxed()
                    .sorted(Comparator.comparingDouble((Integer i) -> size[i]).thenComparingInt(i -> rowIds[i]))
                    .mapToInt(Integer::intValue).toArray();
        }
        return smallestFirstOrder;
    }

    /** Vero se le due predizioni riguardano le stesse righe nello stesso ordine (condizione dei test appaiati). */
    public boolean sameRows(Predictions other) {
        return Arrays.equals(rowIds, other.rowIds);
    }
}