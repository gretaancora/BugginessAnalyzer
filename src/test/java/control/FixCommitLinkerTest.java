package control;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixCommitLinkerTest {

    private final FixCommitLinker linker = new FixCommitLinker("OPENJPA");

    @Test
    void extractsKeysWithoutPartialMatches() {
        assertEquals(Set.of("OPENJPA-12", "OPENJPA-7"),
                linker.keysIn("OPENJPA-12: fix NPE. Also openjpa-7 and SUBOPENJPA-99, OPENJPA-12a"));
        assertEquals(Set.of(), linker.keysIn("OPENJPA-x and XOPENJPA-1"));
    }

    @Test
    void linksOnlyBugTickets() {
        Map<String, String> messages = Map.of(
                "h1", "OPENJPA-100 fix", "h2", "OPENJPA-1000 new feature", "h3", "no ticket");
        assertEquals(Set.of("h1"), linker.fixCommits(messages, Set.of("OPENJPA-100")));
    }
}