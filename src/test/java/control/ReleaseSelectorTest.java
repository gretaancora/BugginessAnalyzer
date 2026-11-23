package control;

import boundary.JiraVersionsSource;
import entity.ExcludedRelease;
import entity.JiraVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSelectorTest {

    private static final Pattern PRE_RELEASE = Pattern.compile(".*-(M\\d+|alpha\\d*|beta\\d*|RC\\d*)$");

    private static JiraVersion v(String name, String date) {
        return new JiraVersion(name, name, date != null, date == null ? null : LocalDate.parse(date));
    }

    private static List<String> names(List<JiraVersion> versions) {
        return versions.stream().map(JiraVersion::name).toList();
    }

    @Test
    void openJpaSnapshotGives33ReleasesAnd11InDataset() throws Exception {
        List<JiraVersion> all = JiraVersionsSource.load(Path.of("data/openjpa_versions.json"), "OPENJPA");
        ReleaseSelector.Selection s = new ReleaseSelector(PRE_RELEASE).select(all);

        assertEquals(52, all.size());
        assertEquals(33, s.kept().size());
        assertEquals(11, ReleaseSelector.datasetSize(s.kept().size(), 34));
        assertEquals(List.of("0.9.0", "0.9.6", "0.9.7", "1.0.0", "1.0.1", "1.0.2", "1.1.0", "1.2.0", "1.2.1",
                "1.2.2", "2.0.0"), names(s.kept().subList(0, 11)));
        assertEquals("4.2.0", s.kept().getLast().name());

        Set<String> excluded = s.excluded().stream().map(ExcludedRelease::name).collect(Collectors.toSet());
        assertTrue(excluded.containsAll(Set.of("2.0.0-M1", "2.0.0-M2", "2.0.0-M3",
                "2.0.0-beta", "2.0.0-beta2", "2.0.0-beta3")));
        assertTrue(excluded.containsAll(Set.of("1.0.3", "1.0.4", "1.2.3", "2.4.3")));
        assertTrue(excluded.containsAll(Set.of("1.3.0", "2.4.4", "3.2.3", "4.3.0")));
        assertEquals(19, s.excluded().size());
    }

    @Test
    void maintenanceReleaseOfOlderLineIsOutOfSequence() {
        List<JiraVersion> input = List.of(
                v("1.0.0", "2020-01-01"),
                v("1.0.1", "2020-02-01"),   // linea 1.0 ancora la piu' recente: resta
                v("1.1.0", "2020-03-01"),
                v("1.0.2", "2020-04-01"),   // linea 1.0 dopo la 1.1.0: fuori sequenza
                v("1.1.1", "2020-05-01"));
        ReleaseSelector.Selection s = new ReleaseSelector(PRE_RELEASE).select(input);

        assertEquals(List.of("1.0.0", "1.0.1", "1.1.0", "1.1.1"), names(s.kept()));
        assertEquals("1.0.2", s.excluded().getFirst().name());
    }

    @Test
    void sameDayReleaseOfOlderLineIsOutOfSequence() {
        List<JiraVersion> input = List.of(v("3.0.0", "2018-06-12"), v("2.4.3", "2018-06-12"), v("2.4.2", "2017-01-03"));
        ReleaseSelector.Selection s = new ReleaseSelector(PRE_RELEASE).select(input);

        assertEquals(List.of("2.4.2", "3.0.0"), names(s.kept()));
    }

    @Test
    void unreleasedAndPreReleasesAreExcluded() {
        List<JiraVersion> input = List.of(v("1.0.0", "2020-01-01"), v("2.0.0-M1", "2020-02-01"),
                v("2.0.0-beta2", "2020-03-01"), v("2.0.0", null));
        ReleaseSelector.Selection s = new ReleaseSelector(PRE_RELEASE).select(input);

        assertEquals(List.of("1.0.0"), names(s.kept()));
        assertEquals(3, s.excluded().size());
    }

    @Test
    void datasetSizeIsFloorOfPercentage() {
        assertEquals(11, ReleaseSelector.datasetSize(33, 34));
        assertEquals(14, ReleaseSelector.datasetSize(43, 34));
        assertEquals(0, ReleaseSelector.datasetSize(2, 34));
    }

    @Test
    void pomVersionIgnoresParentVersion() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>org.apache</groupId><artifactId>apache</artifactId><version>23</version></parent>
                  <artifactId>openjpa-parent</artifactId>
                  <version>3.1.1</version>
                </project>
                """;
        assertEquals("3.1.1", ReleaseGitMapper.projectVersion(pom).orElseThrow());

        String inherited = "<project><parent><version>1.2.0</version></parent><artifactId>x</artifactId></project>";
        assertEquals("1.2.0", ReleaseGitMapper.projectVersion(inherited).orElseThrow());
    }
}