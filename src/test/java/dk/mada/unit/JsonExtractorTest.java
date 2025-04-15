package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dk.mada.action.util.JsonExtractor;
import org.junit.jupiter.api.Test;

/**
 * Test of the crude Json field extractor.
 */
class JsonExtractorTest {
    /**
     * Tests that string and boolean fields can be extracted.
     */
    @Test
    void canExtractFromExample() {
        String exampleJson =
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
                }""";

        JsonExtractor jex = new JsonExtractor(exampleJson);
        assertThat(jex.get("deploymentState")).isEqualTo("PUBLISHED");
        assertThat(jex.get("boolean")).isEqualTo("true");
        assertThat(jex.get("another")).isEqualTo("false");
        assertThat(jex.get("quoted")).isEqualTo("This' might \\\" break");
    }
}
