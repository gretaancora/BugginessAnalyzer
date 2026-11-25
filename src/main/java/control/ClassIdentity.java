package control;

import entity.FileChange;
import exception.GitException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Riconosce la stessa classe sotto percorsi diversi: ogni rinomina registrata in un commit (spostamento di
 * package o di modulo, cambio di nome, copie temporanee dei tag SVN) unisce il vecchio e il nuovo percorso.
 *
 * <p>Un percorso puo' indicare classi diverse in momenti diversi. In OpenJPA (OPENJPA-240, 24/07/2007)
 * {@code XMLMetaData} e' stata rinominata {@code XMLFieldMetaData} e, undici minuti dopo, {@code XMLMapping}
 * e' stata rinominata {@code XMLMetaData}: dallo stesso percorso passano due classi. Quando un percorso diventa
 * destinazione di una rinomina dopo essere stato rinominato altrove, da quel commit inizia una nuova "epoca"
 * del percorso, distinta dalla precedente.
 *
 * <p>"Dopo" va inteso nella storia di Git, non nel calendario: i rami di manutenzione sono storie parallele.
 * {@code DistributedStoreManager}, rinominata sul trunk nel 2009, e' rimasta con il suo nome sul ramo 1.2.x,
 * dove le operazioni SVN sul tag 1.2.1 la "rinominano" da una cartella temporanea al suo posto: li' e' sempre
 * la stessa classe. Per questo una nuova epoca si apre solo se il commit che aveva liberato il percorso e' un
 * antenato del commit che lo riusa, e l'epoca valida per un commit (di fix o di release) e' l'ultima iniziata
 * nella sua storia. Le semplici modifiche non aprono epoche: sui rami un file spostato sul trunk continua a
 * essere modificato con il vecchio percorso, ed e' la stessa classe. Le identita' sono classi di equivalenza
 * (union-find) sulle epoche.
 */
public final class ClassIdentity {

    /** Relazione di discendenza tra commit: {@code true} se {@code ancestor} e' nella storia di {@code commit}. */
    @FunctionalInterface
    public interface Ancestry {
        boolean isAncestor(String ancestor, String commit) throws GitException;
    }

    private record Epoch(String startCommit, String node) {
    }

    private final Ancestry ancestry;
    private final Map<String, Boolean> ancestryCache = new HashMap<>();
    private final Map<String, String> parent = new HashMap<>();
    private final Map<String, List<Epoch>> epochs = new HashMap<>();
    /** Per percorso, i commit che lo hanno rinominato altrove. */
    private final Map<String, List<String>> vacatedBy = new HashMap<>();
    private int renames;
    private int reused;

    public ClassIdentity(Ancestry ancestry) {
        this.ancestry = ancestry;
    }

    /**
     * Registra le rinomine di un commit, che valgono tutte insieme. I commit vanno passati con i genitori
     * prima dei figli.
     */
    public void commit(String hash, List<FileChange> commitRenames) throws GitException {
        if (commitRenames.isEmpty()) {
            return;
        }
        Set<String> sources = new HashSet<>();
        for (FileChange r : commitRenames) {
            sources.add(r.oldPath());
        }
        List<String[]> pairs = new ArrayList<>();
        for (FileChange r : commitRenames) {
            pairs.add(new String[]{node(r.oldPath(), hash, false), null, r.path()});
        }
        for (String[] p : pairs) {
            String target = p[2];
            if (sources.contains(target) || reusedAt(target, hash)) {
                p[1] = newEpoch(target, hash);
                reused++;
            } else {
                p[1] = node(target, hash, true);
            }
        }
        for (String[] p : pairs) {
            union(p[0], p[1]);
            renames++;
        }
        for (FileChange r : commitRenames) {
            vacatedBy.computeIfAbsent(r.oldPath(), k -> new ArrayList<>()).add(hash);
        }
    }

    /** Identita' del percorso nella storia del commit indicato (commit di fix o di release). */
    public String find(String path, String commit) throws GitException {
        return root(node(path, commit, true));
    }

    public int renames() {
        return renames;
    }

    /** Rinomine verso un percorso gia' usato da un'altra classe nella stessa storia (nuova epoca). */
    public int reused() {
        return reused;
    }

    /** Il percorso e' stato liberato nella storia di {@code commit} dopo l'inizio della sua epoca attuale? */
    private boolean reusedAt(String path, String commit) throws GitException {
        List<String> vacating = vacatedBy.get(path);
        if (vacating == null) {
            return false;
        }
        String activeStart = activeEpochStart(path, commit);
        for (String v : vacating) {
            if (isAncestor(v, commit) && (activeStart == null || (!activeStart.equals(v) && isAncestor(activeStart, v)))) {
                return true;
            }
        }
        return false;
    }

    private String node(String path, String commit, boolean includeCommit) throws GitException {
        String node = path;
        for (Epoch e : epochs.getOrDefault(path, List.of())) {
            if (inHistory(e.startCommit(), commit, includeCommit)) {
                node = e.node();
            }
        }
        return node;
    }

    private String activeEpochStart(String path, String commit) throws GitException {
        String start = null;
        for (Epoch e : epochs.getOrDefault(path, List.of())) {
            if (inHistory(e.startCommit(), commit, false)) {
                start = e.startCommit();
            }
        }
        return start;
    }

    private boolean inHistory(String candidate, String commit, boolean includeCommit) throws GitException {
        if (candidate.equals(commit)) {
            return includeCommit;
        }
        return isAncestor(candidate, commit);
    }

    private boolean isAncestor(String ancestor, String commit) throws GitException {
        String key = ancestor + ">" + commit;
        Boolean cached = ancestryCache.get(key);
        if (cached == null) {
            cached = ancestry.isAncestor(ancestor, commit);
            ancestryCache.put(key, cached);
        }
        return cached;
    }

    private String newEpoch(String path, String commit) {
        List<Epoch> list = epochs.computeIfAbsent(path, k -> new ArrayList<>());
        String node = path + "#" + (list.size() + 1);
        list.add(new Epoch(commit, node));
        return node;
    }

    private void union(String a, String b) {
        String ra = root(a);
        String rb = root(b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    private String root(String node) {
        String r = node;
        while (parent.containsKey(r)) {
            r = parent.get(r);
        }
        String cur = node;
        while (!cur.equals(r)) {
            String next = parent.get(cur);
            parent.put(cur, r);
            cur = next;
        }
        return r;
    }
}
