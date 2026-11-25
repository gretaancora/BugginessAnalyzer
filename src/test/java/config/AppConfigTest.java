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

    @Test
    void tokenQuotesAreRemovedAndMaskHidesTheValue() {
        assertEquals("abc123", AppConfig.unquote("\"abc123\""));
        assertEquals("abc123", AppConfig.unquote(" 'abc123' "));
        assertEquals("abc123", AppConfig.unquote("abc123"));
        assertEquals("abc1... (10 caratteri)", new AppConfig.Secret("abc1234567", "test").masked());
    }

    @Test
    void scannerCommandFromScannerHome() throws Exception {
        Path home = Files.createTempDirectory("scanner");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path unix = Files.createFile(bin.resolve("sonar-scanner"));
        Path win = Files.createFile(bin.resolve("sonar-scanner.bat"));
        try {
            assertEquals(unix.toString(), AppConfig.resolveScannerCommand(home.toString(), "sonar-scanner", "Linux"));
            assertEquals(win.toString(), AppConfig.resolveScannerCommand(home.toString(), "sonar-scanner", "Windows 11"));
            // senza variabile d'ambiente vale il valore della configurazione
            assertEquals("sonar-scanner", AppConfig.resolveScannerCommand(null, "sonar-scanner", "Linux"));
            assertEquals("sonar-scanner", AppConfig.resolveScannerCommand(" ", "sonar-scanner", "Linux"));
        } finally {
            Files.delete(unix);
            Files.delete(win);
            Files.delete(bin);
            Files.delete(home);
        }
    }

    @Test
    void scannerHomeWithoutExecutableIsAnError() throws Exception {
        Path home = Files.createTempDirectory("scanner");
        try {
            AppConfig.resolveScannerCommand(home.toString(), "sonar-scanner", "Linux");
            throw new AssertionError("attesa ConfigException");
        } catch (exception.ConfigException e) {
            assertTrue(e.getMessage().contains("SONAR_SCANNER_HOME"));
        } finally {
            Files.delete(home);
        }
    }
}
