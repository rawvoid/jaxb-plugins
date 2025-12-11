package io.github.rawvoid.jaxb.plugin;

import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlSeeAlso;
import org.junit.jupiter.api.Test;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class AnnotatePluginTest extends AbstractXJCMojoTestCase {

    @Test
    void testAnnotatePlugin() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-Xannotate-class=@jakarta.xml.bind.annotation.XmlSeeAlso(value = {java.lang.Object.class, java.lang.String.class})=regex:.*Person",
            "-Xannotate-field=@jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter(jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter.class)=regex:.*name"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var generatedAnnotation = clazz.getDeclaredAnnotation(XmlSeeAlso.class);
            assertThat(generatedAnnotation).isNotNull();
            assertThat(generatedAnnotation.value().length).isEqualTo(2);
            assertThat(generatedAnnotation.value()[0]).isEqualTo(Object.class);
            assertThat(generatedAnnotation.value()[1]).isEqualTo(String.class);

            var nameField = clazz.getDeclaredField("name");
            var nameAnnotation = nameField.getDeclaredAnnotation(jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter.class);
            assertThat(nameAnnotation).isNotNull();
            assertThat(nameAnnotation.value()).isEqualTo(jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter.class);

            var ageField = clazz.getDeclaredField("age");
            assertThat(ageField.getDeclaredAnnotation(jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter.class)).isNull();
        });
    }

    @Test
    void testDuplicateAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-Xannotate-class=@jakarta.xml.bind.annotation.XmlAccessorType(jakarta.xml.bind.annotation.XmlAccessType.NONE)"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var generatedAnnotation = clazz.getDeclaredAnnotation(XmlAccessorType.class);
            assertThat(generatedAnnotation).isNotNull();
            assertThat(generatedAnnotation.value()).isEqualTo(XmlAccessType.NONE);
        });
    }

    @Test
    void testXAnnotationParser() throws Exception {
        var source = """
            @javax.annotation.processing.Generated(value = "Xjc", date = "2025-01-01T00:00:00Z")
            """;
        var xAnnotation = XAnnotationParser.INSTANCE.parse(source);
        var annotationClass = xAnnotation.getAnnotationClass();
        assertThat(annotationClass).isEqualTo(javax.annotation.processing.Generated.class);
        for (var xAnnotationField : xAnnotation.getFieldsList()) {
            var fieldName = xAnnotationField.getName();
            if ("value".equals(fieldName)) {
                assertThat(((String[]) xAnnotationField.getValue())[0]).isEqualTo("Xjc");
            } else if ("date".equals(fieldName)) {
                assertThat(xAnnotationField.getValue()).isEqualTo("2025-01-01T00:00:00Z");
            }
        }
    }

}