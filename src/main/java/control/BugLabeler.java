package control;

import entity.TicketLifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assegna le etichette: le classi modificate dai commit di fix di un ticket sono buggy in ogni release da IV
 * inclusa a FV esclusa, purche' esistano in quella release. Le classi sono confrontate per identita' (vedi
 * {@link ClassIdentity}), quindi anche se nel frattempo hanno cambiato package, modulo o nome.
 */
public final class BugLabeler {

    private BugLabeler() {
    }

    /**
     * @param buggyByRelease   per release, le classi buggy
     * @param labelsByTicket   per ticket, quante coppie classe-release ha etichettato (anche se gia' buggy per
     *                         un altro ticket)
     * @param ambiguousMatches identita' con piu' di un percorso nella stessa release (attese: nessuna)
     */
    public record Result(Map<Integer, Set<String>> buggyByRelease, Map<String, Integer> labelsByTicket,
                         int ambiguousMatches) {
    }

    /**
     * @param lifecycles          ciclo di vita dei ticket
     * @param touchedByTicket     per ticket, le identita' delle classi modificate dai suoi commit di fix
     * @param identitiesByRelease per release del dataset (indice): classe -> identita'
     */
    public static Result label(List<TicketLifecycle> lifecycles, Map<String, Set<String>> touchedByTicket,
                               Map<Integer, Map<String, String>> identitiesByRelease) {
        Map<Integer, Map<String, List<String>>> byIdentity = new HashMap<>();
        int ambiguous = 0;
        for (Map.Entry<Integer, Map<String, String>> e : identitiesByRelease.entrySet()) {
            Map<String, List<String>> m = new HashMap<>();
            e.getValue().forEach((path, id) -> m.computeIfAbsent(id, k -> new ArrayList<>()).add(path));
            for (List<String> paths : m.values()) {
                if (paths.size() > 1) {
                    ambiguous++;
                }
            }
            byIdentity.put(e.getKey(), m);
        }

        Map<Integer, Set<String>> buggy = new HashMap<>();
        Map<String, Integer> labelsByTicket = new HashMap<>();
        for (TicketLifecycle t : lifecycles) {
            Set<String> ids = touchedByTicket.getOrDefault(t.key(), Set.of());
            if (ids.isEmpty() || t.iv() >= t.fv()) {
                continue;
            }
            int labels = 0;
            for (Map.Entry<Integer, Map<String, List<String>>> e : byIdentity.entrySet()) {
                if (!t.affects(e.getKey())) {
                    continue;
                }
                for (String id : ids) {
                    for (String path : e.getValue().getOrDefault(id, List.of())) {
                        buggy.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(path);
                        labels++;
                    }
                }
            }
            labelsByTicket.put(t.key(), labels);
        }
        return new Result(buggy, labelsByTicket, ambiguous);
    }
}
