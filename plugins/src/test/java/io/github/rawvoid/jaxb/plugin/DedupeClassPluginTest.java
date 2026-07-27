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

public class DedupeClassPluginTest extends AbstractXJCMojoTestCase {

    private static Map<String, List<Class<?>>> bySimpleName(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.groupingBy(Class::getSimpleName));
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("dedupe-class.xsd");
    }

    @Test
    void nameKeyStripsTypeSuffix() {
        assertThat(DedupeClassPlugin.nameKey("AircraftCodeType")).isEqualTo("AircraftCode");
        assertThat(DedupeClassPlugin.nameKey("AircraftCode")).isEqualTo("AircraftCode");
        assertThat(DedupeClassPlugin.nameKey("Type")).isEqualTo("Type");
        assertThat(DedupeClassPlugin.nameKey("ListType")).isEqualTo("List");
    }

    @Test
    void withoutPluginKeepsSeparateNestedGroups() throws Exception {
        var classes = testExecute(List.of(), ".*", null);
        var byName = bySimpleName(classes);

        // Nested Group under holders + named GroupType
        assertThat(byName.get("Group")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(byName.get("Group")).allMatch(Class::isMemberClass);
        assertThat(byName).containsKey("GroupType");
    }

    @Test
    void exactMergeCollapsesIsomorphicGroupsIntoNamedHost() throws Exception {
        var args = List.of("-Xdedupe-class");
        var classes = testExecute(args, ".*Group.*|.*Holder.*|.*Party.*", null);
        var byName = bySimpleName(classes);

        // Anonymous Groups merge into package GroupType (nameKey Group ≡ GroupType).
        assertThat(byName).containsKey("GroupType");
        assertThat(byName.get("GroupType")).anyMatch(c -> !c.isMemberClass());
        assertThat(byName).doesNotContainKey("Group");
        // Party is isomorphic but different nameKey — kept separate.
        assertThat(byName).containsKey("Party");
        assertThat(byName.get("Party")).hasSize(1);

        var holderA = byName.get("HolderA").getFirst();
        assertThat(holderA.getDeclaredField("group").getType().getSimpleName()).isEqualTo("GroupType");
    }

    @Test
    void subsetMergesAnonymousAircraftCodeIntoNamedType() throws Exception {
        var args = List.of(
            "-Xdedupe-class",
            "-merge-subset"
        );
        var classes = testExecute(args, ".*AircraftCode.*|.*HolderA.*|.*HolderC.*", null);
        var byName = bySimpleName(classes);

        // Named AircraftCodeType remains; anonymous AircraftCode under HolderA is gone.
        assertThat(byName).containsKey("AircraftCodeType");
        assertThat(byName.get("AircraftCodeType")).anyMatch(c -> !c.isMemberClass());
        assertThat(byName).doesNotContainKey("AircraftCode");

        // HolderA.AircraftCode field type should be AircraftCodeType
        var holderA = byName.get("HolderA").getFirst();
        var field = holderA.getDeclaredField("aircraftCode");
        assertThat(field.getType().getSimpleName()).isEqualTo("AircraftCodeType");
    }

    @Test
    void sameNameDifferentStructureNotMerged() throws Exception {
        var args = List.of(
            "-Xdedupe-class",
            "-merge-subset"
        );
        var classes = testExecute(args, ".*Details.*", null);
        var byName = bySimpleName(classes);

        // HolderB.Details {note} vs HolderC.Details {amount} — two distinct types.
        assertThat(byName.get("Details")).hasSize(2);
    }
}
