package entity;

import java.util.Map;
import java.util.SortedMap;

/**
 * Divisione ordinata per release: il training contiene le release fino a {@code lastTrainRelease} compresa,
 * l'insieme di valutazione le successive. Nessuna release viene spezzata.
 */
public record ReleaseSplit(int firstRelease, int lastTrainRelease, int lastRelease, int trainRows, int trainBuggy,
                           int evaluationRows, int evaluationBuggy) {

    /**
     * Sceglie il taglio al confine tra due release la cui frazione di righe nel training e' piu' vicina a
     * {@code trainFraction}; a parita' tiene il training piu' grande. Training e valutazione hanno almeno una
     * release ciascuno.
     *
     * @param counts righe e righe buggy per release ({righe, buggy}), in ordine di ReleaseID
     */
    public static ReleaseSplit closestTo(SortedMap<Integer, int[]> counts, double trainFraction) {
        if (counts.size() < 2) {
            throw new IllegalArgumentException("Servono almeno due release, trovate " + counts.size());
        }
        int total = counts.values().stream().mapToInt(c -> c[0]).sum();
        int totalBuggy = counts.values().stream().mapToInt(c -> c[1]).sum();
        int rows = 0;
        int buggy = 0;
        int seen = 0;
        ReleaseSplit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            rows += e.getValue()[0];
            buggy += e.getValue()[1];
            seen++;
            if (seen == counts.size()) {
                break;
            }
            double distance = Math.abs((double) rows / total - trainFraction);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = new ReleaseSplit(counts.firstKey(), e.getKey(), counts.lastKey(), rows, buggy,
                        total - rows, totalBuggy - buggy);
            }
        }
        return best;
    }

    public double trainFraction() {
        return (double) trainRows / (trainRows + evaluationRows);
    }

    public String trainReleases() {
        return firstRelease + "-" + lastTrainRelease;
    }

    public String evaluationReleases() {
        return (lastTrainRelease + 1) + "-" + lastRelease;
    }
}