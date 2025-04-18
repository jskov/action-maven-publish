package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dk.mada.action.util.JsonExtractor;
import dk.mada.action.util.JsonExtractor.JsonListString;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test of the crude Json field extractor.
 */
class JsonExtractorTest {
    private static final String EXAMPLE_JSON =
            """
            {
            "deploymentId": "28570f16-da32-4c14-bd2e-c1acc0782365",
            "deploymentName": "central-bundle.zip",
            "deploymentState": "PUBLISHED",
            "boolean":true,"another"   : false  ,
            "quoted"
            : "This' might \\" break",
            "purls": [
              "pkg:maven/com.sonatype.central.example/example_java_project@0.0.7"
            ]
            "errors":{"pkg:maven/dk.mada/action-maven-publish-test@0.0.0?type=pom":["Invalid 'md5' checksum for file: action-maven-publish-test-0.0.0.pom","Invalid 'sha1' checksum for file: action-maven-publish-test-0.0.0.pom","Invalid signature for file: action-maven-publish-test-0.0.0.pom"]}
          }""";
    private JsonExtractor jex = new JsonExtractor(EXAMPLE_JSON);

    /**
     * Tests that string and boolean fields can be extracted.
     */
    @Test
    void canExtractSimpleFields() {
        assertThat(jex.getString("deploymentState").value()).isEqualTo("PUBLISHED");
        assertThat(jex.getBoolean("boolean").value()).isTrue();
        assertThat(jex.getBoolean("another").value()).isFalse();
        assertThat(jex.getString("quoted").value()).isEqualTo("This' might \\\" break");
    }

    /**
     * Tests extraction of lists (with strings).
     */
    @Test
    void canExtractListStrings() {
        assertThat(jex.getListString("purls").values())
                .containsExactly("pkg:maven/com.sonatype.central.example/example_java_project@0.0.7");
    }

    /**
     * Tests extraction of a map keyed by strings, containing list of strings.
     */
    @Test
    void canExtratMap() {
        assertThat(jex.getMap("errors").values())
                .containsEntry(
                        "pkg:maven/dk.mada/action-maven-publish-test@0.0.0?type=pom",
                        new JsonListString(List.of(
                                "Invalid 'md5' checksum for file: action-maven-publish-test-0.0.0.pom",
                                "Invalid 'sha1' checksum for file: action-maven-publish-test-0.0.0.pom",
                                "Invalid signature for file: action-maven-publish-test-0.0.0.pom")));
    }

    /**
     * Tests line/column number hints in parsing errors.
     */
    @Test
    void canPrintSensibleErrorHints() {
        assertThatThrownBy(() -> new JsonExtractor(
                                """
                {
                "field":
                     P
                """)
                        .getString("field"))
                .hasMessageContaining("line:3, column:5");
    }
}
