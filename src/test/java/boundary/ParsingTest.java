package boundary;

import entity.FileChange;
import entity.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsingTest {

    @Test
    void numstatPlainAndRenames() throws Exception {
        FileChange plain = GitRepository.parseNumstat("3\t1\ta/b/C.java");
        assertEquals(new FileChange(null, "a/b/C.java", 3, 1), plain);

        FileChange braces = GitRepository.parseNumstat("0\t0\tmod/src/{old => new}/C.java");
        assertEquals("mod/src/old/C.java", braces.oldPath());
        assertEquals("mod/src/new/C.java", braces.path());

        FileChange emptySide = GitRepository.parseNumstat("1\t0\tmod/{ => sub}/C.java");
        assertEquals("mod/C.java", emptySide.oldPath());
        assertEquals("mod/sub/C.java", emptySide.path());

        FileChange whole = GitRepository.parseNumstat("2\t2\told/C.java => new/D.java");
        assertEquals("old/C.java", whole.oldPath());
        assertEquals("new/D.java", whole.path());

        assertTrue(GitRepository.parseNumstat("-\t-\timg.png").isBinary());
    }

    @Test
    void jiraIssues() throws Exception {
        String json = """
                [{"key":"OPENJPA-5","fields":{"created":"2006-06-29T22:01:04.000+0000",
                  "resolutiondate":"2006-07-10T10:00:00.000+0000",
                  "versions":[{"name":"0.9.0"}],"fixVersions":[{"name":"0.9.6"},{"name":"1.0.0"}]}},
                 {"key":"OPENJPA-6","fields":{"created":"2006-07-01T08:00:00.000-0700",
                  "resolutiondate":null,"versions":[],"fixVersions":[]}}]
                """;
        List<Ticket> t = JiraTicketsSource.parse(json);
        assertEquals(2, t.size());
        assertEquals(List.of("0.9.0"), t.get(0).affectedVersions());
        assertEquals(List.of("0.9.6", "1.0.0"), t.get(0).fixVersions());
        assertEquals(null, t.get(1).resolved());
        assertEquals(2006, t.get(1).created().getYear());
    }
}