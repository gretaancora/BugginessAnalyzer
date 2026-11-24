package boundary;

import exception.AnalysisException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Esegue SonarScanner su una cartella, con la CLI installata ({@code sonar-scanner}) oppure con
 * l'immagine Docker ufficiale. Il token passa solo come variabile d'ambiente {@code SONAR_TOKEN},
 * mai sulla riga di comando. Restituisce l'id dell'elaborazione lato server, letto da
 * {@code .scannerwork/report-task.txt}.
 */
public final class SonarScanner {

    public enum Mode { CLI, DOCKER }

    private static final String LOG_FILE = ".sonar-scanner.log";
    private static final String DOCKER_MOUNT = "/usr/src";

    private final Mode mode;
    private final String command;
    private final String dockerImage;

    public SonarScanner(Mode mode, String command, String dockerImage) {
        this.mode = mode;
        this.command = command;
        this.dockerImage = dockerImage;
    }

    /**
     * @param baseDir    cartella da analizzare (radice della release)
     * @param properties proprieta' sonar.* con percorsi relativi a {@code baseDir}
     */
    public String run(Path baseDir, Map<String, String> properties, String token) throws AnalysisException {
        Path dir = baseDir.toAbsolutePath().normalize();
        Path report = dir.resolve(".scannerwork").resolve("report-task.txt");
        Path log = dir.resolve(LOG_FILE);
        try {
            Files.deleteIfExists(report);
        } catch (IOException e) {
            throw new AnalysisException("Impossibile ripulire " + report + ": " + e.getMessage(), e);
        }

        List<String> cmd = new ArrayList<>();
        if (mode == Mode.DOCKER) {
            cmd.addAll(List.of("docker", "run", "--rm", "-e", "SONAR_TOKEN", "-v", dir + ":" + DOCKER_MOUNT,
                    dockerImage));
        } else {
            cmd.add(executable(command));
        }
        properties.forEach((k, v) -> cmd.add("-D" + k + "=" + v));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("SONAR_TOKEN", token);
        try {
            int code = pb.start().waitFor();
            if (code != 0) {
                throw new AnalysisException("SonarScanner terminato con codice " + code + " su " + dir
                        + ". Ultime righe del log (" + log + "):\n" + tail(log, 25));
            }
        } catch (IOException e) {
            throw new AnalysisException("Impossibile avviare " + cmd.getFirst()
                    + " (e' installato e nel PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("SonarScanner interrotto", e);
        }
        return taskId(report);
    }

    /** Su Windows la CLI si chiama {@code sonar-scanner.bat}. */
    private static String executable(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        boolean hasExtension = command.contains(".") && command.lastIndexOf('.') > command.lastIndexOf('/');
        return windows && !hasExtension ? command + ".bat" : command;
    }

    static String taskId(Path report) throws AnalysisException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(report)) {
            p.load(in);
        } catch (IOException e) {
            throw new AnalysisException("Report di SonarScanner non trovato: " + report, e);
        }
        String id = p.getProperty("ceTaskId");
        if (id == null || id.isBlank()) {
            throw new AnalysisException("ceTaskId mancante in " + report);
        }
        return id.trim();
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(log non leggibile: " + e.getMessage() + ")";
        }
    }
}
