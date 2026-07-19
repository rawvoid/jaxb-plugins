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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class RenameClassPluginTest extends AbstractXJCMojoTestCase {

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("rename-class.xsd");
    }

    @Test
    void renamesMatchingClass() throws Exception {
        var args = List.of(
            "-Xrename-class",
            "-mapping",
            "-regex=Person",
            "-to=CustomPerson"
        );
        var classes = testExecute(args, ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKey("CustomPerson");
        assertThat(byName).doesNotContainKey("Person");
        assertThat(byName.get("CustomPerson").getFirst().getSimpleName()).isEqualTo("CustomPerson");
    }

    @Test
    void renamesWithRegexReplacement() throws Exception {
        var args = List.of(
            "-Xrename-class",
            "-mapping",
            "-regex=(.*)Type",
            "-to=$1"
        );
        var classes = testExecute(args, ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKey("Address");
        assertThat(byName).doesNotContainKey("AddressType");
    }

    @Test
    void packageFilterLimitsRename() throws Exception {
        // Default package derived from namespace; wrong package must leave Person untouched.
        var wrongPackage = List.of(
            "-Xrename-class",
            "-mapping",
            "-package=com.does.not.exist",
            "-regex=Person",
            "-to=CustomPerson"
        );
        var classes = testExecute(wrongPackage, ".*", null);
        assertThat(bySimpleName(classes)).containsKey("Person");
        assertThat(bySimpleName(classes)).doesNotContainKey("CustomPerson");
    }

    @Test
    void renamesEnum() throws Exception {
        var args = List.of(
            "-Xrename-class",
            "-mapping",
            "-regex=Color",
            "-to=Colour"
        );
        var classes = testExecute(args, ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKey("Colour");
        assertThat(byName.get("Colour").getFirst().isEnum()).isTrue();
        assertThat(byName).doesNotContainKey("Color");
    }

    @Test
    void conflictKeepsOriginalNamesAndStillSucceeds() throws Exception {
        // Map Alpha and Beta both to SharedName → conflict group; both stay original.
        var args = List.of(
            "-Xrename-class",
            "-mapping",
            "-regex=Alpha|Beta",
            "-to=SharedName",
            // Unrelated rename still applies.
            "-mapping",
            "-regex=Person",
            "-to=CustomPerson"
        );
        var classes = testExecute(args, ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKeys("Alpha", "Beta", "CustomPerson");
        assertThat(byName).doesNotContainKey("SharedName");
        assertThat(byName).doesNotContainKey("Person");
    }

    @Test
    void conflictWhenMappingOntoExistingName() throws Exception {
        // Alpha → Person while Person stays Person → both blocked for that slot.
        var args = List.of(
            "-Xrename-class",
            "-mapping",
            "-regex=^Alpha$",
            "-to=Person"
        );
        var classes = testExecute(args, ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKeys("Alpha", "Person");
        // Only one Person; Alpha was not renamed onto it.
        assertThat(byName.get("Person")).hasSize(1);
    }

    private static Map<String, List<Class<?>>> bySimpleName(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.groupingBy(Class::getSimpleName));
    }
}
