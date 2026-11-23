package dao;

import entity.ExcludedRelease;
import entity.Release;
import exception.PersistenceException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Lettura e scrittura dei CSV delle release. */
public final class ReleaseDAO {

    public static final String ALL_RELEASES_FILE = "releases_all.csv";
    public static final String DATASET_RELEASES_FILE = "releases_dataset.csv";
    public static final String EXCLUDED_RELEASES_FILE = "releases_excluded.csv";

    private static final String RELEASES_HEADER = "Index,Version,JiraID,ReleaseDate,GitRef,Commit";
    private static final String EXCLUDED_HEADER = "Version,JiraID,ReleaseDate,Reason";

    private ReleaseDAO() {
    }

    public static void writeReleases(Path file, List<Release> releases) throws PersistenceException {
        List<String> lines = new ArrayList<>();
        lines.add(RELEASES_HEADER);
        for (Release r : releases) {
            lines.add(String.join(",",
                    String.valueOf(r.getIndex()),
                    csv(r.getVersion()),
                    csv(r.getJiraId()),
                    r.getReleaseDate().toString(),
                    csv(r.getGitRef()),
                    csv(r.getCommit())));
        }
        write(file, lines);
    }

    public static void writeExcluded(Path file, List<ExcludedRelease> excluded) throws PersistenceException {
        List<String> lines = new ArrayList<>();
        lines.add(EXCLUDED_HEADER);
        for (ExcludedRelease e : excluded) {
            lines.add(String.join(",",
                    csv(e.name()),
                    csv(e.jiraId()),
                    e.releaseDate() == null ? "" : e.releaseDate().toString(),
                    csv(e.reason())));
        }
        write(file, lines);
    }

    /** Rilegge un CSV scritto da {@link #writeReleases}; servira' ai passi successivi. */
    public static List<Release> readReleases(Path file) throws PersistenceException {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals(RELEASES_HEADER)) {
                throw new PersistenceException("Intestazione inattesa in " + file);
            }
            List<Release> releases = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                if (f.length != 6) {
                    throw new PersistenceException("Riga non valida in " + file + ": " + line);
                }
                releases.add(new Release(Integer.parseInt(f[0]), f[1], f[2], LocalDate.parse(f[3]), f[4], f[5]));
            }
            return releases;
        } catch (IOException | RuntimeException e) {
            throw new PersistenceException("Impossibile leggere " + file + ": " + e.getMessage(), e);
        }
    }

    private static void write(Path file, List<String> lines) throws PersistenceException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    w.write(line);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Impossibile scrivere " + file + ": " + e.getMessage(), e);
        }
    }

    /** Racchiude il valore tra virgolette se contiene caratteri speciali per il CSV. */
    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}