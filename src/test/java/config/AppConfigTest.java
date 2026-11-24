package config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void fileOnDiskComesFirst() throws Exception {
        Path f = Files.createTempFile("cfg", ".properties");
        try {
            Files.writeString(f, "sonar.token=abc\n");
            Properties p = AppConfig.readProperties(f);
            assertEquals("abc", p.getProperty("sonar.token"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void missingEverywhereGivesNull() throws Exception {
        assertTrue(AppConfig.readProperties(Path.of("non-esiste-" + System.nanoTime() + ".properties")) == null);
    }
}
