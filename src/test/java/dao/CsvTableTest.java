package dao;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvTableTest {

    @Test
    void writeAndReadBack() throws Exception {
        CsvTable t = new CsvTable(List.of("Class", "ReleaseID", "LCOM*"));
        t.add(List.of("a/B.java", "1", "0.250"));
        t.add(List.of("a/C.java", "1", CsvTable.MISSING));
        Path f = Files.createTempFile("table", ".csv");
        t.write(f);
        CsvTable r = CsvTable.read(f);
        assertEquals(t.header(), r.header());
        assertEquals(t.rows(), r.rows());
        assertEquals(2, r.column("LCOM*"));
        Files.delete(f);
    }
}
