/*
 * Copyright 2026 Rawvoid(https://github.com/rawvoid)
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
import jakarta.xml.bind.annotation.*;
import jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link AnnotatePlugin}.
 * Uses dedicated {@code annotate.xsd} only.
 */
class AnnotatePluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com\\.github\\.rawvoid\\.xjc_plugins\\.annotate";
    private static final String PERSON = PKG + "\\.Person";
    private static final String PERSON_OR_ORDER = PKG + "\\.(Person|Order)";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("annotate.xsd");
    }

    @Test
    void testUsage() {
        var plugin = new AnnotatePlugin();
        assertThat(plugin.getUsage()).isNotNull();
    }

    @Test
    void testAnnotatePlugin() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlSeeAlso(value = {java.lang.Object.class, java.lang.String.class})",
            "-target=.*Person",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter(jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter.class)",
            "-target=.*name"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var generatedAnnotation = clazz.getDeclaredAnnotation(XmlSeeAlso.class);
            assertThat(generatedAnnotation).isNotNull();
            assertThat(generatedAnnotation.value()).containsExactly(Object.class, String.class);

            var nameField = clazz.getDeclaredField("name");
            var nameAnnotation = nameField.getDeclaredAnnotation(XmlJavaTypeAdapter.class);
            assertThat(nameAnnotation).isNotNull();
            assertThat(nameAnnotation.value()).isEqualTo(CollapsedStringAdapter.class);

            var ageField = clazz.getDeclaredField("age");
            assertThat(ageField.getDeclaredAnnotation(XmlJavaTypeAdapter.class)).isNull();
        });
    }

    @Test
    void testDuplicateAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlAccessorType(jakarta.xml.bind.annotation.XmlAccessType.NONE)",
            "-target=.*Person"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var generatedAnnotation = clazz.getDeclaredAnnotation(XmlAccessorType.class);
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
            "-target=.*Person"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredAnnotation(XmlRootElement.class)).isNotNull();
            assertThat(clazz.getDeclaredAnnotation(XmlSeeAlso.class)).isNotNull();
        });
    }

    @Test
    void testMultipleTargets() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")",
            "-target=.*Person",
            "-target=.*Order"
        );
        var classes = testExecute(args, PERSON_OR_ORDER, (source, clazz) -> {
            assertThat(clazz.getDeclaredAnnotation(XmlRootElement.class)).isNotNull();
        });
        assertThat(classes.stream().map(Class::getSimpleName).filter(n -> n.equals("Person") || n.equals("Order")))
            .containsExactlyInAnyOrder("Person", "Order");
    }

    @Test
    void testMultipleConfigs() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"test\")",
            "-target=.*Person",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlSeeAlso(java.lang.Object.class)",
            "-target=.*Person"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredAnnotation(XmlRootElement.class)).isNotNull();
            assertThat(clazz.getDeclaredAnnotation(XmlSeeAlso.class)).isNotNull();
        });
    }

    @Test
    void testNonRepeatableReplacement() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"first\")",
            "-anno=@jakarta.xml.bind.annotation.XmlRootElement(name=\"second\")",
            "-target=.*Person"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var annotations = clazz.getDeclaredAnnotationsByType(XmlRootElement.class);
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
        testExecute(args, PERSON_OR_ORDER, (source, clazz) -> {
            assertThat(clazz.getDeclaredAnnotation(XmlRootElement.class)).isNotNull();
        });
    }

    @Test
    void testMultipleFieldTargets() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.XmlSchemaType(name=\"test\")",
            "-target=.*name",
            "-target=.*age"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredField("name").getDeclaredAnnotation(XmlSchemaType.class)).isNotNull();
            assertThat(clazz.getDeclaredField("age").getDeclaredAnnotation(XmlSchemaType.class)).isNotNull();
            assertThat(clazz.getDeclaredField("active").getDeclaredAnnotation(XmlSchemaType.class)).isNull();
        });
    }

    @Test
    void testRemoveClassAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-remove-from-class",
            "-anno=jakarta.xml.bind.annotation.XmlAccessorType",
            "-target=.*Person"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredAnnotation(XmlAccessorType.class)).isNull();
        });
    }

    @Test
    void testRemoveFieldAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-field",
            "-anno=@jakarta.xml.bind.annotation.XmlSchemaType(name=\"test\")",
            "-target=.*name",
            "-remove-from-field",
            "-anno=jakarta.xml.bind.annotation.XmlSchemaType",
            "-target=.*name"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var nameField = clazz.getDeclaredField("name");
            assertThat(nameField.getDeclaredAnnotation(XmlSchemaType.class)).isNull();
        });
    }

    @Test
    void testRemoveWithRegex() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-remove-from-class",
            "-anno=jakarta.xml.bind.annotation.XmlAccessorType",
            "-target=.*Order"
        );
        testExecute(args, PERSON_OR_ORDER, (source, clazz) -> {
            if (clazz.getSimpleName().equals("Person")) {
                assertThat(clazz.getDeclaredAnnotation(XmlAccessorType.class)).isNotNull();
            } else if (clazz.getSimpleName().equals("Order")) {
                assertThat(clazz.getDeclaredAnnotation(XmlAccessorType.class)).isNull();
            }
        });
    }

    @Test
    void testAddMethodAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-method",
            "-anno=@java.lang.Deprecated",
            "-target=.*getName"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredMethod("getName").getDeclaredAnnotation(Deprecated.class)).isNotNull();
            assertThat(clazz.getDeclaredMethod("getAge").getDeclaredAnnotation(Deprecated.class)).isNull();
        });
    }

    @Test
    void testRemoveMethodAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-method",
            "-anno=@java.lang.Deprecated",
            "-target=.*getName",
            "-remove-from-method",
            "-anno=java.lang.Deprecated",
            "-target=.*getName"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getDeclaredMethod("getName").getDeclaredAnnotation(Deprecated.class)).isNull();
        });
    }

    @Test
    void testAddPackageAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-package",
            "-anno=@java.lang.Deprecated",
            "-target=com\\.github\\.rawvoid\\.xjc_plugins\\.annotate"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var pkg = clazz.getPackage();
            assertThat(pkg.getDeclaredAnnotation(Deprecated.class)).isNotNull();
            assertThat(pkg.getDeclaredAnnotation(XmlSchema.class)).isNotNull();
        });
    }

    @Test
    void testRemovePackageAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-remove-from-package",
            "-anno=jakarta.xml.bind.annotation.XmlSchema",
            "-target=com\\.github\\.rawvoid\\.xjc_plugins\\.annotate"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(clazz.getPackage().getDeclaredAnnotation(XmlSchema.class)).isNull();
        });
    }

    @Test
    void testNestedAnnotation() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-package",
            "-anno=@jakarta.xml.bind.annotation.XmlSchema(xmlns={@jakarta.xml.bind.annotation.XmlNs(prefix=\"p\", namespaceURI=\"http://example.com\")})",
            "-target=com\\.github\\.rawvoid\\.xjc_plugins\\.annotate"
        );
        testExecute(args, PERSON, (source, clazz) -> {
            var schema = clazz.getPackage().getDeclaredAnnotation(XmlSchema.class);
            assertThat(schema).isNotNull();
            assertThat(schema.xmlns()).hasSize(1);
            assertThat(schema.xmlns()[0].prefix()).isEqualTo("p");
            assertThat(schema.xmlns()[0].namespaceURI()).isEqualTo("http://example.com");
        });
    }

}
