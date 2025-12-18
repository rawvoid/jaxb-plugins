/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    void testUsage() throws Exception {
        var plugin = new AnnotatePlugin();
        var usage = plugin.getUsage();
        assertThat(usage).isNotNull();
    }

    @Test
    void testAnnotatePlugin() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlSeeAlso(value = {java.lang.Object.class, java.lang.String.class})",
            "-regex=.*Person",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter(jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter.class)",
            "-regex=.*name"
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
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlAccessorType(jakarta.xml.bind.annotation.XmlAccessType.NONE)",
            "-regex=.*Person"
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
