package dao;

import exception.PersistenceException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tabella CSV semplice (separatore virgola, nessun valore contiene virgole o virgolette):
 * e' il formato di tutti i file intermedi del passo 3.
 */
public final class CsvTable {

    /** Valore mancante: e' anche il simbolo che Weka riconosce come dato assente. */
    public static final String MISSING = "?";

    private final List<String> header;
    private final List<List<String>> rows = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    public CsvTable(List<String> header) {
        this.header = List.copyOf(header);
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i), i);
        }
    }

    public List<String> header() {
        return header;
    }

    public List<List<String>> rows() {
        return rows;
    }

    public void add(List<String> row) {
        if (row.size() != header.size()) {
            throw new IllegalArgumentException("Riga con " + row.size() + " valori, attesi " + header.size());
        }
        for (String v : row) {
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                throw new IllegalArgumentException("Valore non ammesso nel CSV: " + v);
            }
        }
        rows.add(List.copyOf(row));
    }

    /** Posizione di una colonna, o -1 se non esiste. */
    public int column(String name) {
        return index.getOrDefault(name, -1);
    }

    public void write(Path file) throws PersistenceException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(String.join(",", header));
                w.newLine();
                for (List<String> row : rows) {
                    w.write(String.join(",", row));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Impossibile scrivere " + file + ": " + e.getMessage(), e);
        }
    }

    public static CsvTable read(Path file) throws PersistenceException {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new PersistenceException("File vuoto: " + file);
            }
            CsvTable t = new CsvTable(List.of(lines.getFirst().split(",", -1)));
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    t.add(List.of(line.split(",", -1)));
                }
            }
            return t;
        } catch (IOException | IllegalArgumentException e) {
            throw new PersistenceException("Impossibile leggere " + file + ": " + e.getMessage(), e);
        }
    }
}
