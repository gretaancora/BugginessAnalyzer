package control;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Individua i commit di fix: quelli il cui messaggio cita la chiave di un ticket di bug corretto. */
public final class FixCommitLinker {

    private final Pattern keyPattern;
    private final String projectKey;

    public FixCommitLinker(String projectKey) {
        this.projectKey = projectKey.toUpperCase();
        // la chiave non deve essere parte di una parola o di un numero piu' lungo (OPENJPA-12 != OPENJPA-123)
        this.keyPattern = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(projectKey) + "-(\\d+)(?!\\d)",
                Pattern.CASE_INSENSITIVE);
    }

    /** Chiavi dei ticket citate in un messaggio, in forma normalizzata (es. "OPENJPA-123"). */
    public Set<String> keysIn(String message) {
        Set<String> keys = new HashSet<>();
        Matcher m = keyPattern.matcher(message);
        while (m.find()) {
            keys.add(projectKey + "-" + Integer.parseInt(m.group(1)));
        }
        return keys;
    }

    /** Hash dei commit che citano almeno uno dei ticket indicati. */
    public Set<String> fixCommits(Map<String, String> messages, Set<String> bugKeys) {
        Set<String> fixes = new HashSet<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            for (String key : keysIn(e.getValue())) {
                if (bugKeys.contains(key)) {
                    fixes.add(e.getKey());
                    break;
                }
            }
        }
        return fixes;
    }
}