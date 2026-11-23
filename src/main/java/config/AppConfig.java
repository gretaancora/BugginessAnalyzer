package config;

import exception.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static AppConfig load(Path file) throws ConfigException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new ConfigException("Impossibile leggere " + file + ": " + e.getMessage(), e);
        }
        return new AppConfig(p);
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