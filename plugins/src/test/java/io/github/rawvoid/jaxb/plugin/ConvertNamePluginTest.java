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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XJC integration tests for {@link ConvertNamePlugin}.
 * Uses dedicated {@code convert-name.xsd} only.
 */
public class ConvertNamePluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com\\.github\\.rawvoid\\.xjc_plugins\\.convert_name";
    private static final String NS = "https://www.github.com/rawvoid/xjc-plugins/convert-name";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("convert-name.xsd");
    }

    @Test
    void testClassNameConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-class-name",
            "-input=Person",
            "-to=CustomPerson"
        );
        testExecute(args, PKG + "\\.CustomPerson", (source, clazz) -> {
            assertThat(clazz.getSimpleName()).isEqualTo("CustomPerson");
        });
    }

    @Test
    void testPropertyNameConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-property-name",
            "-input=name",
            "-to=fullName"
        );
        testExecute(args, PKG + "\\.Person", (source, clazz) -> {
            assertThat(clazz.getSimpleName()).isEqualTo("Person");
            // toPropertyName replaces the input; accessor casing follows the mapping as-is.
            var methodNames = Arrays.stream(clazz.getDeclaredMethods()).map(m -> m.getName()).toList();
            assertThat(methodNames).anyMatch(n -> n.equalsIgnoreCase("getfullName"));
        });
    }

    @Test
    void testPackageNameConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-package-name",
            "-input=" + NS,
            "-to=io.github.rawvoid.custom"
        );
        testExecute(args, "io\\.github\\.rawvoid\\.custom\\.Person", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("io.github.rawvoid.custom");
        });
    }

    @Test
    void testPackageNameConvertCompact() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-package-name=" + NS + "->io.github.rawvoid.custom"
        );
        testExecute(args, "io\\.github\\.rawvoid\\.custom\\.Person", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("io.github.rawvoid.custom");
        });
    }

    @Test
    void testClassNameConvertCompact() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-class-name=Person->CustomPerson",
            "-class-name=RootType->Root"
        );
        List<String> list = new ArrayList<>();
        testExecute(args, PKG + "\\.(CustomPerson|Root)", (source, clazz) -> {
            list.add(clazz.getSimpleName());
        });
        assertThat(list).containsExactlyInAnyOrder("CustomPerson", "Root");
    }

    @Test
    void testClassNameConvertCompactRegex() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-class-name=/(Per)(.*)/->Human$2"
        );
        // "Person" → groups Per, son → Human + son = Humanson (same as structured regex test intent)
        testExecute(args, PKG + "\\.Humanson", (source, clazz) -> {
            assertThat(clazz.getSimpleName()).isEqualTo("Humanson");
        });
    }

    @Test
    void testNameRegexConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-class-name",
            "-name=Per(.*)",
            "-to=Human$1"
        );
        testExecute(args, PKG + "\\.Humanson", (source, clazz) -> {
            // "Person" matches "Per(.*)" with group 1 as "son" → "Humanson"
            assertThat(clazz.getSimpleName()).isEqualTo("Humanson");
        });
    }

    @Test
    void testMixNameAndInputConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-class-name",
            "-name=Per(.*)",
            "-to=Human$1",
            "-class-name",
            "-input=RootType",
            "-to=Root"
        );
        List<String> list = new ArrayList<>();
        testExecute(args, PKG + "\\.(Humanson|Root)", (source, clazz) -> {
            list.add(clazz.getSimpleName());
        });

        assertThat(list).containsExactlyInAnyOrder("Humanson", "Root");
    }

    @Test
    void testClassNameGroupMarkerOnce() throws Exception {
        // Same child field (-input) starts the next list item without repeating -class-name.
        var args = List.of(
            "-Xconvert-name",
            "-class-name",
            "-input=Person",
            "-to=CustomPerson",
            "-input=RootType",
            "-to=Root"
        );
        List<String> list = new ArrayList<>();
        testExecute(args, PKG + "\\.(CustomPerson|Root)", (source, clazz) -> {
            list.add(clazz.getSimpleName());
        });

        assertThat(list).containsExactlyInAnyOrder("CustomPerson", "Root");
    }

    @Test
    void testInterleavedPackageAndClassNameMappings() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-package-name",
            "-input=" + NS,
            "-to=io.github.rawvoid.custom",
            "-class-name",
            "-input=Person",
            "-to=CustomPerson",
            "-package-name",
            "-input=https://unused.example/ns",
            "-to=io.github.rawvoid.ignored"
        );
        testExecute(args, "io\\.github\\.rawvoid\\.custom\\.CustomPerson", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("io.github.rawvoid.custom");
            assertThat(clazz.getSimpleName()).isEqualTo("CustomPerson");
        });
    }

    @Test
    void testConstantNameConvert() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-constant-name",
            "-input=red",
            "-to=SCARLET"
        );
        testExecute(args, PKG + "\\.Color", (source, clazz) -> {
            assertThat(clazz.isEnum()).isTrue();
            var constants = Arrays.stream(clazz.getEnumConstants()).map(Object::toString).toList();
            assertThat(constants).contains("SCARLET", "BLUE");
            assertThat(constants).doesNotContain("RED");
        });
    }

    @Test
    void testVariableNameConvertByInput() throws Exception {
        // XJC feeds toVariableName the property name (already toPropertyName'd), e.g. "Name" for element "name".
        var args = List.of(
            "-Xconvert-name",
            "-variable-name",
            "-input=Name",
            "-to=fullName"
        );
        testExecute(args, PKG + "\\.Person", (source, clazz) -> {
            var fieldNames = Arrays.stream(clazz.getDeclaredFields()).map(f -> f.getName()).toList();
            assertThat(fieldNames).contains("fullName");
            assertThat(fieldNames).doesNotContain("name");
        });
    }

    @Test
    void testVariableNameConvertByNameRegex() throws Exception {
        var args = List.of(
            "-Xconvert-name",
            "-variable-name",
            "-name=name",
            "-to=fullName"
        );
        testExecute(args, PKG + "\\.Person", (source, clazz) -> {
            var fieldNames = Arrays.stream(clazz.getDeclaredFields()).map(f -> f.getName()).toList();
            assertThat(fieldNames).contains("fullName");
            assertThat(fieldNames).doesNotContain("name");
        });
    }

    @Test
    void testRejectsBothInputAndName() {
        var plugin = new ConvertNamePlugin();
        var args = new String[]{
            "-Xconvert-name",
            "-class-name",
            "-input=Person",
            "-name=Person",
            "-to=CustomPerson"
        };
        assertThatThrownBy(() -> plugin.parseArgument(null, args, 0))
            .hasMessageContaining("exactly one of -input or -name");
    }

    @Test
    void testRejectsNeitherInputNorName() {
        var plugin = new ConvertNamePlugin();
        var args = new String[]{
            "-Xconvert-name",
            "-class-name",
            "-to=CustomPerson"
        };
        assertThatThrownBy(() -> plugin.parseArgument(null, args, 0))
            .hasMessageContaining("exactly one of -input or -name");
    }
}
