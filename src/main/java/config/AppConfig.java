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

    public String sonarScannerCommand() {
        return optional("sonar.scanner.command", "sonar-scanner");
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

    /**
     * Token SonarCloud: dalla variabile d'ambiente {@code SONAR_TOKEN} oppure dalla proprieta'
     * {@code sonar.token} del file indicato da {@code sonar.token.file} (da non versionare).
     */
    public String sonarToken() throws ConfigException {
        String env = System.getenv("SONAR_TOKEN");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path file = Path.of(optional("sonar.token.file", "secrets.properties"));
        Properties secrets = readProperties(file);
        if (secrets != null) {
            String token = secrets.getProperty("sonar.token", "");
            if (!token.isBlank()) {
                return token.trim();
            }
        }
        throw new ConfigException("Token SonarCloud mancante: imposta la variabile d'ambiente SONAR_TOKEN "
                + "oppure scrivi sonar.token=<token> in " + file + " (nella radice del progetto "
                + "o in src/main/resources)");
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
}
