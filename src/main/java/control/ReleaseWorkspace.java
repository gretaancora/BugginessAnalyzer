package control;

import boundary.GitRepository;
import config.AppConfig;
import entity.Release;
import exception.ConfigException;
import exception.GitException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Cartella di lavoro con il codice di ogni release ({@code work.dir/<versione>}), estratto una sola
 * volta dal repository e riusato da CK e SonarCloud. Fornisce anche il filtro delle classi analizzate.
 */
public final class ReleaseWorkspace {

    private static final String MARKER = ".exported";

    private final GitRepository git;
    private final Path workDir;
    private final Predicate<String> isClass;

    public ReleaseWorkspace(GitRepository git, AppConfig config) throws ConfigException {
        this.git = git;
        this.workDir = config.workDir();
        this.isClass = classFilter(config);
    }

    /** File .java di produzione secondo {@code classes.include.regex} e {@code classes.exclude.regex}. */
    public static Predicate<String> classFilter(AppConfig config) throws ConfigException {
        Pattern include = config.classesInclude();
        Pattern exclude = config.classesExclude();
        return p -> include.matcher(p).matches() && !exclude.matcher(p).matches();
    }

    public Predicate<String> isClass() {
        return isClass;
    }

    /** Cartella con il codice della release, estratto se non c'e' gia'. */
    public Path sources(Release release) throws GitException {
        Path dir = workDir.resolve(release.getVersion()).toAbsolutePath();
        if (!Files.exists(dir.resolve(MARKER))) {
            git.exportTo(release.getCommit(), dir);
            try {
                Files.writeString(dir.resolve(MARKER), release.getCommit());
            } catch (IOException e) {
                throw new GitException("Impossibile scrivere in " + dir + ": " + e.getMessage(), e);
            }
        }
        return dir;
    }

    /** Classi analizzate nella release (percorsi relativi alla radice del repository). */
    public List<String> classes(Release release) throws GitException {
        return git.listFiles(release.getCommit()).stream().filter(isClass).sorted().toList();
    }
}
