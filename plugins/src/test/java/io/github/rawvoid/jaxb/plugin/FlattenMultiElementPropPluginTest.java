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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link FlattenMultiElementPropPlugin}.
 * Uses dedicated {@code flatten-multi-element-prop.xsd} only.
 */
class FlattenMultiElementPropPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com\\.github\\.rawvoid\\.xjc_plugins\\.flatten_multi_element_prop";
    private static final String FLIGHT = PKG + "\\.Flight";
    private static final String DUAL = PKG + "\\.DualChoice";
    private static final String PLAIN = PKG + "\\.PlainList";
    private static final String TIMED = PKG + "\\.TimedDerived";

    private static final String OPTION = "-Xflatten-multi-element-prop";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("flatten-multi-element-prop.xsd");
    }

    @Test
    void baselineKeepsMergedProperty() throws Exception {
        testExecute(List.of(), FLIGHT, (source, clazz) -> {
            // Without the plugin, XJC generates a single merged field.
            assertThat(fieldNames(clazz)).hasSize(1);
            assertThat(source).containsAnyOf("@XmlElements", "@XmlElementRefs");
        });
    }

    @Test
    void flattensSingleChoiceIntoIndividualFields() throws Exception {
        testExecute(List.of(OPTION), FLIGHT, (source, clazz) -> {
            var names = fieldNames(clazz);
            assertThat(names).containsExactly("airportCode", "date", "time");
            // Verify that the split fields are collection Lists (since original choice repeats)
            assertThat(clazz.getDeclaredField("airportCode").getType()).isEqualTo(java.util.List.class);
            // No merged annotation should remain.
            assertThat(source).doesNotContain("@XmlElements");
            assertThat(source).doesNotContain("@XmlElementRefs");
        });
    }

    @Test
    void flattenedFieldsAreNotJAXBElement() throws Exception {
        testExecute(List.of(OPTION), FLIGHT, (source, clazz) -> {
            // Each field should be a direct type (String), not JAXBElement.
            for (var field : clazz.getDeclaredFields()) {
                var name = field.getName();
                if (name.contains("$")) continue;
                assertThat(field.getType())
                    .as("field '%s' should not be JAXBElement", name)
                    .isNotEqualTo(jakarta.xml.bind.JAXBElement.class);
            }
        });
    }

    @Test
    void flattensTwoChoicesOnOneType() throws Exception {
        testExecute(List.of(OPTION), DUAL, (source, clazz) -> {
            var names = fieldNames(clazz);
            assertThat(names).contains("one", "two", "red", "green");

            // Verify that 'one' has @XmlElement with correct nillable/required defaults (false)
            var oneField = clazz.getDeclaredField("one");
            var oneXml = oneField.getAnnotation(jakarta.xml.bind.annotation.XmlElement.class);
            assertThat(oneXml).isNotNull();
            assertThat(oneXml.required()).isFalse();
            assertThat(oneXml.nillable()).isFalse();
        });
    }

    @Test
    void leavesPlainListUnchanged() throws Exception {
        testExecute(List.of(OPTION), PLAIN, (source, clazz) -> {
            assertThat(fieldNames(clazz)).contains("tag");
            // Should not introduce new fields.
            assertThat(fieldNames(clazz)).hasSize(1);
        });
    }

    @Test
    void flattensInheritanceCollisionRestProperty() throws Exception {
        testExecute(List.of(OPTION), TIMED, (source, clazz) -> {
            var names = fieldNames(clazz).stream().map(String::toLowerCase).toList();
            // The merged "rest"/"content" property should be gone.
            assertThat(names).noneMatch(n -> n.equals("rest") || n.equals("content"));
            // Individual fields should exist (Time shows up as element name in
            // the collision, along with Note).
            assertThat(names).anyMatch(n -> n.contains("time") || n.contains("note"));

            // Verify that time in TimedDerived is String (not List) to override TimedBase.time
            assertThat(clazz.getDeclaredField("time").getType()).isEqualTo(String.class);

            // Verify that 'time' in TimedDerived inherited required = true from TimedBase.time
            var timeField = clazz.getDeclaredField("time");
            var timeXml = timeField.getAnnotation(jakarta.xml.bind.annotation.XmlElement.class);
            assertThat(timeXml).isNotNull();
            assertThat(timeXml.required()).isTrue();

            // Verify that optional field 'note' maintains required = false
            var noteField = clazz.getDeclaredField("note");
            var noteXml = noteField.getAnnotation(jakarta.xml.bind.annotation.XmlElement.class);
            assertThat(noteXml).isNotNull();
            assertThat(noteXml.required()).isFalse();
        });
    }

    private static List<String> fieldNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
            .map(Field::getName)
            .filter(n -> !n.contains("$"))
            .collect(Collectors.toList());
    }
}
