package config;

import boundary.SonarScanner;
import exception.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parametri letti da config.properties. */
public final class AppConfig {

    public static final String DEFAULT_FILE = "config.properties";

    private final Properties props;

    private AppConfig(Properties props) {
        this.props = props;
    }

    /**
     * Carica la configurazione da {@code file}; se il file non esiste, cerca una risorsa con lo stesso nome
     * nel classpath (cartella {@code src/main/resources}).
     */
    public static AppConfig load(Path file) throws ConfigException {
        Properties p = readProperties(file);
        if (p == null) {
            throw new ConfigException("Configurazione non trovata: ne' " + file.toAbsolutePath()
                    + " ne' la risorsa " + file.getFileName() + " in src/main/resources");
        }
        return new AppConfig(p);
    }

    /**
     * Legge un file di proprieta': prima dal percorso indicato (relativo alla cartella di esecuzione),
     * poi, se non esiste, dalla risorsa con lo stesso nome nel classpath. {@code null} se non c'e' nessuno dei due.
     */
    static Properties readProperties(Path file) throws ConfigException {
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
                return p;
            }
            String resource = file.getFileName().toString();
            try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                p.load(in);
                return p;
            }
        } catch (IOException e) {
            throw new ConfigException("Impossibile leggere " + file + ": " + e.getMessage(), e);
        }
    }

    private String required(String key) throws ConfigException {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ConfigException("Proprieta' mancante: " + key);
        }
        return value.trim();
    }

    public String projectName() throws ConfigException {
        return required("project.name");
    }

    public String jiraProjectKey() throws ConfigException {
        return required("jira.project.key");
    }

    public Path jiraVersionsSnapshot() throws ConfigException {
        return Path.of(required("jira.versions.snapshot"));
    }

    public String gitRemoteUrl() throws ConfigException {
        return required("git.remote.url");
    }

    public Path gitLocalPath() throws ConfigException {
        return Path.of(required("git.local.path"));
    }

    public Path outputDir() throws ConfigException {
        return Path.of(required("output.dir"));
    }

    public int keptPercentage() throws ConfigException {
        String raw = required("releases.kept.percentage");
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || value > 100) {
                throw new ConfigException("releases.kept.percentage deve essere tra 1 e 100: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigException("releases.kept.percentage non e' un intero: " + raw, e);
        }
    }

    public Pattern preReleasePattern() throws ConfigException {
        String raw = required("releases.prerelease.regex");
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new ConfigException("releases.prerelease.regex non valida: " + raw, e);
        }
    }

    public Path jiraTicketsSnapshot() throws ConfigException {
        return Path.of(required("jira.tickets.snapshot"));
    }

    public String jiraTicketsJql() throws ConfigException {
        return required("jira.tickets.jql");
    }

    public Pattern classesInclude() throws ConfigException {
        return pattern("classes.include.regex", required("classes.include.regex"));
    }

    /** Pattern dei file da escludere; se la proprieta' manca non si esclude nulla. */
    public Pattern classesExclude() throws ConfigException {
        String raw = props.getProperty("classes.exclude.regex", "");
        return raw.isBlank() ? Pattern.compile("(?!)") : pattern("classes.exclude.regex", raw.trim());
    }

    public int massCommitThreshold() throws ConfigException {
        String raw = required("metrics.mass.commit.threshold");
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new ConfigException("metrics.mass.commit.threshold deve essere positivo: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigException("metrics.mass.commit.threshold non e' un intero: " + raw, e);
        }
    }

    /** Cartella dove estrarre il codice delle release (per CK e SonarCloud). */
    public Path workDir() {
        return Path.of(optional("work.dir", "work"));
    }

    public String sonarHostUrl() {
        return optional("sonar.host.url", "https://sonarcloud.io");
    }

    public String sonarOrganization() throws ConfigException {
        return required("sonar.organization");
    }

    public String sonarProjectPrefix() {
        return optional("sonar.project.prefix", "openjpa");
    }

    public SonarScanner.Mode sonarScannerMode() throws ConfigException {
        String raw = optional("sonar.scanner.mode", "cli");
        try {
            return SonarScanner.Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("sonar.scanner.mode deve essere cli o docker: " + raw, e);
        }
    }

    /** Variabile d'ambiente con la cartella di installazione di sonar-scanner. */
    public static final String SCANNER_HOME_ENV = "SONAR_SCANNER_HOME";

    /**
     * Comando di sonar-scanner: se e' impostata la variabile d'ambiente {@value #SCANNER_HOME_ENV}, il
     * programma {@code bin/sonar-scanner} della cartella indicata; altrimenti {@code sonar.scanner.command}
     * (predefinito "sonar-scanner", cercato nel PATH). Cosi' il percorso della propria installazione non
     * finisce nel file di configurazione versionato.
     */
    public String sonarScannerCommand() throws ConfigException {
        return resolveScannerCommand(System.getenv(SCANNER_HOME_ENV), optional("sonar.scanner.command", "sonar-scanner"),
                System.getProperty("os.name", ""));
    }

    static String resolveScannerCommand(String scannerHome, String configured, String osName) throws ConfigException {
        if (scannerHome == null || scannerHome.isBlank()) {
            return configured;
        }
        String executable = osName.toLowerCase().startsWith("windows") ? "sonar-scanner.bat" : "sonar-scanner";
        Path command = Path.of(scannerHome.trim()).resolve("bin").resolve(executable);
        if (!Files.isRegularFile(command)) {
            throw new ConfigException("La variabile d'ambiente " + SCANNER_HOME_ENV + " vale \"" + scannerHome
                    + "\", ma " + command + " non esiste: deve indicare la cartella di installazione di sonar-scanner "
                    + "(quella che contiene bin/)");
        }
        return command.toString();
    }

    public String sonarDockerImage() {
        return optional("sonar.scanner.docker.image", "sonarsource/sonar-scanner-cli");
    }

    public boolean sonarReanalyze() {
        return Boolean.parseBoolean(optional("sonar.reanalyze", "false"));
    }

    public String sonarSourceEncoding() {
        return optional("sonar.source.encoding", "UTF-8");
    }

    public int sonarTaskTimeoutMinutes() throws ConfigException {
        String raw = optional("sonar.task.timeout.minutes", "30");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConfigException("sonar.task.timeout.minutes non e' un intero: " + raw, e);
        }
    }

    /** Token e indicazione di dove e' stato letto (per i messaggi di diagnostica). */
    public record Secret(String value, String origin) {

        /** Primi 4 caratteri e lunghezza: abbastanza per riconoscere il token senza mostrarlo. */
        public String masked() {
            return (value.length() <= 4 ? "****" : value.substring(0, 4) + "...") + " (" + value.length() + " caratteri)";
        }
    }

    /**
     * Token SonarCloud: dalla variabile d'ambiente {@code SONAR_TOKEN} oppure dalla proprieta'
     * {@code sonar.token} del file indicato da {@code sonar.token.file} (da non versionare), cercato nella
     * cartella di esecuzione e poi in src/main/resources. Eventuali virgolette attorno al valore vengono tolte.
     */

    public Secret sonarSecret() throws ConfigException {
        String env = System.getenv("SONAR_TOKEN");
        if (env != null && !env.isBlank()) {
            return new Secret(unquote(env), "variabile d'ambiente SONAR_TOKEN");
        }
        Path file = Path.of(optional("sonar.token.file", "secrets.properties"));
        Properties secrets = readProperties(file);
        if (secrets != null) {
            String token = secrets.getProperty("sonar.token", "");
            if (!token.isBlank()) {
                return new Secret(unquote(token), origin(file));
            }
        }
        throw new ConfigException("Token SonarCloud mancante: imposta la variabile d'ambiente SONAR_TOKEN "
                + "oppure scrivi sonar.token=<token> in " + file + " (nella radice del progetto "
                + "o in src/main/resources)");
    }

    /** Dove {@link #readProperties} trova il file: percorso su disco oppure risorsa del classpath. */
    static String origin(Path file) {
        if (Files.exists(file)) {
            return file.toAbsolutePath().toString();
        }
        java.net.URL url = AppConfig.class.getClassLoader().getResource(file.getFileName().toString());
        return url == null ? file.toString() : url.toString();
    }

    static String unquote(String raw) {
        String v = raw.trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private String optional(String key, String defaultValue) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Pattern pattern(String key, String raw) throws ConfigException {
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new ConfigException(key + " non valida: " + raw, e);
        }
    }

    /** Suffissi provati per i tag; la stringa vuota indica il nome identico alla versione Jira. */
    public List<String> tagSuffixes() {
        String raw = props.getProperty("releases.tag.suffixes", "");
        return Arrays.stream(raw.split(",", -1)).map(String::trim).distinct().toList();
    }

    /** Riferimenti Git scelti a mano per le release senza tag (versione -> commit). */
    public Map<String, String> manualRefs() throws ConfigException {
        Map<String, String> refs = new LinkedHashMap<>();
        String raw = props.getProperty("releases.manual.refs", "");
        for (String entry : raw.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] kv = entry.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new ConfigException("Voce non valida in releases.manual.refs: " + entry);
            }
            refs.put(kv[0].trim(), kv[1].trim());
        }
        return refs;
    }

    /** Parametri degli esperimenti di classificazione (passo ml); ogni chiave ml.* e' facoltativa. */
    public ExperimentSettings experimentSettings() throws ConfigException {
        List<Integer> k = new java.util.ArrayList<>();
        for (String v : optional("ml.ibk.k", "1,3,5,7,9,11,13,15").split(",")) {
            if (!v.isBlank()) {
                k.add(parseInt("ml.ibk.k", v.trim()));
            }
        }
        int threads = parseInt("ml.threads", optional("ml.threads", "0"));
        if (threads <= 0) {
            threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        java.util.Set<String> validations = new java.util.HashSet<>();
        for (String v : optional("ml.validations", "holdout,cv").split(",")) {
            String name = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.isEmpty() && !name.equals("holdout") && !name.equals("cv")) {
                throw new ConfigException("ml.validations: valore sconosciuto " + v + " (validi: holdout, cv)");
            }
            validations.add(name);
        }
        return new ExperimentSettings(
                parseInt("ml.cv.repetitions", optional("ml.cv.repetitions", "10")),
                parseInt("ml.cv.folds", optional("ml.cv.folds", "10")),
                parseDouble("ml.holdout.train.fraction", optional("ml.holdout.train.fraction", "0.8")),
                parseDouble("ml.validation.fraction", optional("ml.validation.fraction", "0.2")),
                k,
                parseInt("ml.bootstrap.samples", optional("ml.bootstrap.samples", "2000")),
                parseInt("ml.seed", optional("ml.seed", "1")),
                threads,
                validations.contains("holdout"),
                validations.contains("cv"));
    }

    private static int parseInt(String key, String raw) throws ConfigException {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " non e' un intero: " + raw, e);
        }
    }

    private static double parseDouble(String key, String raw) throws ConfigException {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " non e' un numero: " + raw, e);
        }
    }
}