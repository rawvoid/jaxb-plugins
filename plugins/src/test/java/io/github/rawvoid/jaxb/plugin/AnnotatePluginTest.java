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
            var generatedAnnotation = clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlAccessorType.class);
            assertThat(generatedAnnotation).isNotNull();
            assertThat(generatedAnnotation.value()).isEqualTo(jakarta.xml.bind.annotation.XmlAccessType.NONE);
        });
    }

    @Test
    void testMultipleAnnotations() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")",
            "-anno=@jakarta.xml.bind.annotation.XmlSeeAlso(java.lang.Object.class)",
            "-regex=.*Person"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlRootElement.class)).isNotNull();
            assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSeeAlso.class)).isNotNull();
        });
    }

    @Test
    void testMultiplePatterns() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")",
            "-regex=.*Person",
            "-regex=.*Order"
        );
        testExecute(args, clazz -> {
            if (clazz.getSimpleName().equals("Person") || clazz.getSimpleName().equals("Order")) {
                assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlRootElement.class)).isNotNull();
            }
        });
    }

    @Test
    void testMultipleConfigs() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")",
            "-regex=.*Person",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlSeeAlso(java.lang.Object.class)",
            "-regex=.*Person"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlRootElement.class)).isNotNull();
            assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSeeAlso.class)).isNotNull();
        });
    }

    @Test
    void testNonRepeatableReplacement() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"first\")",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"second\")",
            "-regex=.*Person"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var annotations = clazz.getDeclaredAnnotationsByType(jakarta.xml.bind.annotation.XmlRootElement.class);
            assertThat(annotations).hasSize(1);
            assertThat(annotations[0].name()).isEqualTo("second");
        });
    }

    @Test
    void testNoRegexMatchesAll() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlRootElement.class)).isNotNull();
        });
    }

    @Test
    void testMultipleFieldPatterns() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.XmlSchemaType(name=\"test\")",
            "-regex=.*name",
            "-regex=.*age"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            try {
                assertThat(clazz.getDeclaredField("name").getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSchemaType.class)).isNotNull();
                assertThat(clazz.getDeclaredField("age").getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSchemaType.class)).isNotNull();
                assertThat(clazz.getDeclaredField("active").getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSchemaType.class)).isNull();
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testRemoveClassAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-remove-from-class",
            "-anno=jakarta.xml.bind.annotation.XmlAccessorType",
            "-regex=.*Person"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var generatedAnnotation = clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlAccessorType.class);
            assertThat(generatedAnnotation).isNull();
        });
    }

    @Test
    void testRemoveFieldAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.XmlSchemaType(name=\"test\")",
            "-regex=.*name",
            "-remove-from-field",
            "-anno=jakarta.xml.bind.annotation.XmlSchemaType",
            "-regex=.*name"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var nameField = clazz.getDeclaredField("name");
            assertThat(nameField.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlSchemaType.class)).isNull();
        });
    }

    @Test
    void testRemoveWithRegex() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-remove-from-class",
            "-anno=jakarta.xml.bind.annotation.XmlAccessorType",
            "-regex=.*Order"
        );
        testExecute(args, clazz -> {
            if (clazz.getSimpleName().equals("Person")) {
                assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlAccessorType.class)).isNotNull();
            } else if (clazz.getSimpleName().equals("Order")) {
                assertThat(clazz.getDeclaredAnnotation(jakarta.xml.bind.annotation.XmlAccessorType.class)).isNull();
            }
        });
    }

    @Test
    void testRemoveMethodAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-method",
            "-anno=@java.lang.Deprecated",
            "-regex=.*getName",
            "-remove-from-method",
            "-anno=java.lang.Deprecated",
            "-regex=.*getName"
        );
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) return;
            var getNameMethod = clazz.getDeclaredMethod("getName");
            assertThat(getNameMethod.getDeclaredAnnotation(Deprecated.class)).isNull();
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
