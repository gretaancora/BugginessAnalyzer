package entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parte numerica di un nome di versione (es. "2.0.0-beta" -> [2, 0, 0]).
 * La "linea" di una versione e' la coppia major.minor: 1.0.1 e 1.0.3 appartengono alla linea 1.0.
 */
public record VersionNumber(List<Integer> parts) implements Comparable<VersionNumber> {

    public VersionNumber {
        parts = List.copyOf(parts);
    }

    public static Optional<VersionNumber> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String numeric = name.split("-", 2)[0];
        List<Integer> parts = new ArrayList<>();
        for (String token : numeric.split("\\.")) {
            if (!token.matches("\\d+")) {
                return Optional.empty();
            }
            parts.add(Integer.parseInt(token));
        }
        return Optional.of(new VersionNumber(parts));
    }

    private int part(int i) {
        return i < parts.size() ? parts.get(i) : 0;
    }

    /** Linea di sviluppo: major.minor. */
    public VersionNumber line() {
        return new VersionNumber(List.of(part(0), part(1)));
    }

    @Override
    public int compareTo(VersionNumber other) {
        int n = Math.max(parts.size(), other.parts.size());
        for (int i = 0; i < n; i++) {
            int cmp = Integer.compare(part(i), other.part(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.join(".", parts.stream().map(String::valueOf).toList());
    }
}