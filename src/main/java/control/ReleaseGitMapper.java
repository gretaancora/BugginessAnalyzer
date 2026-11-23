package control;

import boundary.GitRepository;
import entity.ExcludedRelease;
import entity.JiraVersion;
import entity.Release;
import exception.GitException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Associa ogni versione Jira al commit Git che ne contiene il codice.
 *
 * <p>Per ogni versione: se in configurazione c'e' un commit scelto a mano si usa quello, altrimenti si
 * cerca un tag {@code refs/tags/<nome><suffisso>} per ciascun suffisso configurato (es. "-incubating").
 * Come controllo, la versione dichiarata nel pom.xml del commit deve coincidere con quella Jira
 * (il suffisso "-incubating" viene ignorato). Le versioni senza riferimento o con pom incoerente
 * vengono scartate con il relativo motivo.
 */
public final class ReleaseGitMapper {

    private static final String INCUBATING = "-incubating";

    private final GitRepository git;
    private final List<String> tagSuffixes;
    private final Map<String, String> manualRefs;

    public ReleaseGitMapper(GitRepository git, List<String> tagSuffixes, Map<String, String> manualRefs) {
        this.git = git;
        this.tagSuffixes = List.copyOf(tagSuffixes);
        this.manualRefs = Map.copyOf(manualRefs);
    }

    public record Mapping(List<Release> releases, List<ExcludedRelease> excluded) {
    }

    private record GitTarget(String ref, String commit) {
    }

    /** @param ordered versioni gia' ordinate; l'indice delle release segue quest'ordine */
    public Mapping map(List<JiraVersion> ordered) throws GitException {
        List<Release> releases = new ArrayList<>();
        List<ExcludedRelease> excluded = new ArrayList<>();

        for (JiraVersion v : ordered) {
            Optional<GitTarget> target = findTarget(v.name());
            if (target.isEmpty()) {
                String reason = manualRefs.containsKey(v.name())
                        ? "commit indicato in configurazione non trovato: " + manualRefs.get(v.name())
                        : "nessun tag Git corrispondente";
                excluded.add(ExcludedRelease.of(v, reason));
                continue;
            }
            Optional<String> pomVersion = pomVersion(target.get().commit());
            if (pomVersion.isPresent() && !normalize(pomVersion.get()).equals(normalize(v.name()))) {
                excluded.add(ExcludedRelease.of(v, "versione nel pom.xml (" + pomVersion.get()
                        + ") diversa da quella Jira in " + target.get().ref()));
                continue;
            }
            releases.add(new Release(releases.size() + 1, v.name(), v.id(), v.releaseDate(),
                    target.get().ref(), target.get().commit()));
        }
        return new Mapping(List.copyOf(releases), List.copyOf(excluded));
    }

    private Optional<GitTarget> findTarget(String versionName) throws GitException {
        String manual = manualRefs.get(versionName);
        if (manual != null) {
            return git.resolveCommit(manual).map(commit -> new GitTarget(commit, commit));
        }
        for (String suffix : tagSuffixes) {
            String tag = versionName + suffix;
            Optional<String> commit = git.resolveTag(tag);
            if (commit.isPresent()) {
                return Optional.of(new GitTarget("refs/tags/" + tag, commit.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<String> pomVersion(String commit) throws GitException {
        Optional<String> pom = git.readFile(commit, "pom.xml");
        return pom.isPresent() ? projectVersion(pom.get()) : Optional.empty();
    }

    /** Versione del progetto dichiarata nel pom (se assente, quella del parent). */
    static Optional<String> projectVersion(String pomXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(pomXml)));
            Element project = doc.getDocumentElement();
            Optional<String> own = childText(project, "version");
            if (own.isPresent()) {
                return own;
            }
            return childElement(project, "parent").flatMap(parent -> childText(parent, "version"));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Element> childElement(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName() != null ? e.getLocalName() : e.getTagName())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> childText(Element parent, String name) {
        return childElement(parent, name).map(e -> e.getTextContent().trim());
    }

    private static String normalize(String version) {
        return version.endsWith(INCUBATING) ? version.substring(0, version.length() - INCUBATING.length()) : version;
    }
}