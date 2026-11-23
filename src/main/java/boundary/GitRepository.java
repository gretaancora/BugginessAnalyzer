package boundary;

import exception.GitException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Accesso a un repository Git locale tramite la CLI di git. */
public final class GitRepository {

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