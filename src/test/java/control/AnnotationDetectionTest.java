package control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationDetectionTest {

    private static final String PATH = "mod/src/main/java/p/EagerFetchMode.java";

    @Test
    void recognisesAnnotationDeclarations() {
        assertTrue(OoMetricsController.isAnnotationDeclaration(
                "@Target({METHOD, FIELD})\n@Retention(RUNTIME)\npublic @interface EagerFetchMode {\n"
                        + "    FetchMode value() default FetchMode.NONE;\n}\n", PATH));
        assertTrue(OoMetricsController.isAnnotationDeclaration("public @ interface EagerFetchMode{}", PATH));
    }

    @Test
    void ignoresClassesAndComments() {
        assertTrue(!OoMetricsController.isAnnotationDeclaration(
                "/** Simile a un @interface EagerFetchMode */\npublic class EagerFetchMode {}", PATH));
        assertTrue(!OoMetricsController.isAnnotationDeclaration(
                "// @interface EagerFetchMode\npublic enum EagerFetchMode { A }", PATH));
        assertTrue(!OoMetricsController.isAnnotationDeclaration(
                "public class EagerFetchMode { @interface Inner {} }", PATH));
    }
}
