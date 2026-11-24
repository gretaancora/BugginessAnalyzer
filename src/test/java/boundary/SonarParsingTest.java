package boundary;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarParsingTest {

    @Test
    void componentTreeMeasuresByPath() {
        JSONObject body = new JSONObject("""
                {"paging":{"pageIndex":1,"pageSize":500,"total":2},
                 "baseComponent":{"key":"org_p"},
                 "components":[
                   {"key":"org_p:m/src/main/java/A.java","path":"m/src/main/java/A.java","qualifier":"FIL",
                    "measures":[{"metric":"ncloc","value":"120"},{"metric":"code_smells","value":"4"}]},
                   {"key":"org_p:m/src/main/java/B.java","path":"m/src/main/java/B.java","qualifier":"FIL",
                    "measures":[]}]}
                """);
        Map<String, Map<String, String>> m = SonarCloudClient.parseComponents(body);
        assertEquals("120", m.get("m/src/main/java/A.java").get("ncloc"));
        assertEquals("4", m.get("m/src/main/java/A.java").get("code_smells"));
        assertTrue(m.get("m/src/main/java/B.java").isEmpty());
    }

    @Test
    void reportTaskId() throws Exception {
        Path f = Files.createTempFile("report-task", ".txt");
        Files.writeString(f, "organization=gretaancora\nprojectKey=k\nceTaskId=AY123\nceTaskUrl=https://x\n");
        assertEquals("AY123", SonarScanner.taskId(f));
        Files.delete(f);
    }
}
