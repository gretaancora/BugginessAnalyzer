package entity;

import org.junit.jupiter.api.Test;

import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseSplitTest {

    /** Righe e buggy delle 11 release del dataset di OpenJPA. */
    private static SortedMap<Integer, int[]> openJpa() {
        int[][] c = {{948, 107}, {949, 194}, {948, 182}, {997, 147}, {998, 142}, {1003, 150}, {1044, 166},
                {1050, 179}, {1050, 208}, {1050, 197}, {1293, 195}};
        SortedMap<Integer, int[]> m = new TreeMap<>();
        for (int i = 0; i < c.length; i++) {
            m.put(i + 1, c[i]);
        }
        return m;
    }

    @Test
    void openJpaHoldoutCutsAfterRelease9() {
        ReleaseSplit s = ReleaseSplit.closestTo(openJpa(), 0.8);
        assertEquals(9, s.lastTrainRelease());
        assertEquals(8987, s.trainRows());
        assertEquals(1475, s.trainBuggy());
        assertEquals(2343, s.evaluationRows());
        assertEquals(392, s.evaluationBuggy());
        assertEquals("10-11", s.evaluationReleases());
    }

    @Test
    void openJpaValidationCutsAfterRelease7() {
        SortedMap<Integer, int[]> train = new TreeMap<>(openJpa().headMap(10));
        ReleaseSplit s = ReleaseSplit.closestTo(train, 0.8);
        assertEquals(7, s.lastTrainRelease());
        assertEquals(6887, s.trainRows());
        assertEquals(1088, s.trainBuggy());
        assertEquals(2100, s.evaluationRows());
        assertEquals(387, s.evaluationBuggy());
    }

    @Test
    void alwaysLeavesAtLeastOneReleasePerSide() {
        SortedMap<Integer, int[]> m = new TreeMap<>();
        m.put(1, new int[]{10, 1});
        m.put(2, new int[]{1000, 100});
        assertEquals(1, ReleaseSplit.closestTo(m, 0.8).lastTrainRelease());
    }
}