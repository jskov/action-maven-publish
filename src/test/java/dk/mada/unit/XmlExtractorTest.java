package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dk.mada.action.util.XmlExtractor;
import org.junit.jupiter.api.Test;

/**
 * Test of the crude XML field extractor.
 */
class XmlExtractorTest {
    /**
     * Tests that string fields can be extracted.
     */
    @Test
    void canExtractFromExample() {
        String exampleXml =
                """
				<?xml version="1.0" encoding="UTF-8"?>
				<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
				    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>dk.mada</groupId>
				  <artifactId>action-maven-publish-test</artifactId>
				  <version>0.0.0</version>
				  <packaging>pom</packaging>
				  <name>Used for testing OSSRH publishing</name>
				  <description>A dummy file.</description>
				  <url>https://github.com/jskov/action-maven-publish</url>
				  <licenses>
				    <license>
				      <name>The Apache License, Version 2.0</name>
				      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
				    </license>
				  </licenses>
				  <developers>
				    <developer>
				      <id>jskov</id>
				      <name>Jesper Skov</name>
				      <email>jskov@mada.dk</email>
				    </developer>
				  </developers>
				  <scm>
				    <connection>scm:git:git://github.com/jskov/action-maven-publish.git</connection>
				    <developerConnection>scm:git:ssh://github.com:jskov/action-maven-publish.git</developerConnection>
				    <url>https://github.com/jskov/action-maven-publish/</url>
				  </scm>
				  <dependencies>
		       		<dependency>
       		       	  <groupId>org.apache.commons</groupId>
       		       	  <artifactId>commons-lang3</artifactId>
        		      <version>${commons.lang3.version}</version>
		    		</dependency>
        		  </dependencies>
				</project>""";

        XmlExtractor xex = new XmlExtractor(exampleXml);
        assertThat(xex.get("groupId")).isEqualTo("dk.mada");
        assertThat(xex.get("artifactId")).isEqualTo("action-maven-publish-test");
        assertThat(xex.get("version")).isEqualTo("0.0.0");
    }
}
