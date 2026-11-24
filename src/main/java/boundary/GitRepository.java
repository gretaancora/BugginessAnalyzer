package boundary;

import entity.Commit;
import entity.FileChange;
import exception.GitException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Accesso a un repository Git locale tramite la CLI di git. */
public final class GitRepository {

    /** Separatori usati nei formati di git log (caratteri che non compaiono in nomi e messaggi). */
    private static final String RECORD = "\u001e";
    private static final String FIELD = "\u001f";

    private final Path path;

    private GitRepository(Path path) {
        this.path = path;
    }

    /** Apre il clone locale; se la cartella non esiste clona il repository remoto. */
    public static GitRepository openOrClone(Path localPath, String remoteUrl) throws GitException {
        if (!Files.exists(localPath)) {
            Path parent = localPath.toAbsolutePath().getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                throw new GitException("Impossibile creare " + parent + ": " + e.getMessage(), e);
            }
            runInheritingIo(List.of("git", "clone", remoteUrl, localPath.toString()));
        }
        GitRepository repo = new GitRepository(localPath);
        repo.run("rev-parse", "--git-dir");
        return repo;
    }

    /** Hash del commit puntato da un tag, se il tag esiste. Usa sempre refs/tags/ per evitare branch omonimi. */
    public Optional<String> resolveTag(String tagName) throws GitException {
        return resolveCommit("refs/tags/" + tagName);
    }

    /** Hash completo del commit indicato da un riferimento qualsiasi (hash abbreviato, tag, ...). */
    public Optional<String> resolveCommit(String rev) throws GitException {
        Result r = runAllowingFailure("rev-parse", "--verify", "--quiet", rev + "^{commit}");
        return r.exitCode == 0 ? Optional.of(r.stdout.trim()) : Optional.empty();
    }

    /** Contenuto di un file a una certa revisione, se il file esiste. */
    public Optional<String> readFile(String commit, String filePath) throws GitException {
        Result r = runAllowingFailure("show", commit + ":" + filePath);
        return r.exitCode == 0 ? Optional.of(r.stdout) : Optional.empty();
    }

    /** File presenti in un commit (percorsi relativi alla radice). */
    public List<String> listFiles(String commit) throws GitException {
        return run("ls-tree", "-r", "--name-only", commit).lines().filter(l -> !l.isBlank()).toList();
    }

    /** Hash dei commit di un intervallo (es. "a..b" oppure un singolo riferimento per tutta la storia). */
    public Set<String> revList(String range) throws GitException {
        return run("rev-list", range).lines().filter(l -> !l.isBlank()).collect(Collectors.toSet());
    }

    /**
     * Storia che porta a {@code rev}, dal commit piu' vecchio al piu' recente (genitori prima dei figli),
     * senza merge. Le righe sono contate ignorando gli spazi e le rinomine sono riconosciute.
     */
    public List<Commit> history(String rev) throws GitException {
        String out = run("log", "--no-merges", "--topo-order", "--reverse", "-w", "-M", "--numstat",
                "--format=" + RECORD + "%H" + FIELD + "%an" + FIELD + "%at", rev);
        List<Commit> commits = new ArrayList<>();
        for (String record : out.split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] lines = record.split("\n");
            String[] header = lines[0].split(FIELD, -1);
            if (header.length != 3) {
                throw new GitException("Intestazione di commit non valida: " + lines[0]);
            }
            List<FileChange> changes = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    changes.add(parseNumstat(lines[i]));
                }
            }
            commits.add(new Commit(header[0], header[1],
                    Instant.ofEpochSecond(Long.parseLong(header[2])), changes));
        }
        return commits;
    }

    /** Messaggi completi dei commit della storia di {@code rev} (hash -> messaggio). */
    public Map<String, String> messages(String rev) throws GitException {
        String out = run("log", "--no-merges", "--format=%H" + FIELD + "%B" + RECORD, rev);
        Map<String, String> messages = new HashMap<>();
        for (String record : out.split(RECORD)) {
            String trimmed = record.strip();
            int sep = trimmed.indexOf(FIELD);
            if (sep > 0) {
                messages.put(trimmed.substring(0, sep), trimmed.substring(sep + 1));
            }
        }
        return messages;
    }

    /**
     * Interpreta una riga di {@code --numstat}: "aggiunte\trimosse\tpercorso". Le rinomine compaiono come
     * "vecchio => nuovo" oppure "prefisso/{vecchio => nuovo}/suffisso".
     */
    static FileChange parseNumstat(String line) throws GitException {
        String[] parts = line.split("\t", 3);
        if (parts.length != 3) {
            throw new GitException("Riga numstat non valida: " + line);
        }
        int added = "-".equals(parts[0]) ? -1 : Integer.parseInt(parts[0]);
        int deleted = "-".equals(parts[1]) ? -1 : Integer.parseInt(parts[1]);
        String path = parts[2];
        if (!path.contains(" => ")) {
            return new FileChange(null, path, added, deleted);
        }
        int open = path.indexOf('{');
        int close = path.indexOf('}', open + 1);
        String oldPath;
        String newPath;
        if (open >= 0 && close > open) {
            String prefix = path.substring(0, open);
            String suffix = path.substring(close + 1);
            String[] sides = path.substring(open + 1, close).split(" => ", -1);
            oldPath = normalize(prefix + sides[0] + suffix);
            newPath = normalize(prefix + sides[1] + suffix);
        } else {
            String[] sides = path.split(" => ", 2);
            oldPath = sides[0];
            newPath = sides[1];
        }
        return new FileChange(oldPath, newPath, added, deleted);
    }

    private static String normalize(String path) {
        String p = path.replace("//", "/");
        return p.startsWith("/") ? p.substring(1) : p;
    }

    private String run(String... args) throws GitException {
        Result r = runAllowingFailure(args);
        if (r.exitCode != 0) {
            throw new GitException("git " + String.join(" ", args) + " fallito (exit " + r.exitCode + "): "
                    + r.stderr.trim());
        }
        return r.stdout;
    }

    private Result runAllowingFailure(String... args) throws GitException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", path.toString()));
        cmd.addAll(List.of(args));
        try {
            Path errFile = Files.createTempFile("git-stderr", ".log");
            try {
                Process p = new ProcessBuilder(cmd).redirectError(errFile.toFile()).start();
                String out;
                try (InputStream in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                int code = p.waitFor();
                return new Result(code, out, Files.readString(errFile, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(errFile);
            }
        } catch (IOException e) {
            throw new GitException("Impossibile eseguire " + String.join(" ", cmd) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Comando git interrotto: " + String.join(" ", cmd), e);
        }
    }

    private static void runInheritingIo(List<String> cmd) throws GitException {
        try {
            int code = new ProcessBuilder(cmd).inheritIO().start().waitFor();
            if (code != 0) {
                throw new GitException(String.join(" ", cmd) + " fallito (exit " + code + ")");
            }
        } catch (IOException e) {
            throw new GitException("Impossibile eseguire " + String.join(" ", cmd) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Comando interrotto: " + String.join(" ", cmd), e);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}