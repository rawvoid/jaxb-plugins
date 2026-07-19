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

public class FlattenInnerClassPluginTest extends AbstractXJCMojoTestCase {

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("flatten-inner-class.xsd");
    }

    @Test
    void withoutPlugin_keepsDeepNesting() throws Exception {
        var classes = testExecute(List.of(), ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKeys("FlattenRoot", "Group", "AnotherGroup", "Entry");
        assertThat(byName.get("Group").getFirst().isMemberClass()).isTrue();
        assertThat(byName.get("AnotherGroup").getFirst().isMemberClass()).isTrue();
        assertThat(byName.get("Entry")).allMatch(Class::isMemberClass);
        assertThat(byName.get("Level1").getFirst().isMemberClass()).isTrue();
        assertThat(byName.get("Level2").getFirst().isMemberClass()).isTrue();
        assertThat(byName.get("Level3").getFirst().isMemberClass()).isTrue();
    }

    @Test
    void flattensUniqueNamesAndStopsOnConflict() throws Exception {
        var classes = testExecute(List.of("-Xflatten-inner-class"), ".*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKeys(
            "FlattenRoot", "Group", "AnotherGroup", "Entry",
            "DeepRoot", "Level1", "Level2", "Level3"
        );

        var flattenRoot = byName.get("FlattenRoot").getFirst();
        var group = byName.get("Group").getFirst();
        var anotherGroup = byName.get("AnotherGroup").getFirst();

        // Unique sibling wrappers lift to package scope.
        assertThat(group.isMemberClass()).isFalse();
        assertThat(anotherGroup.isMemberClass()).isFalse();
        assertThat(flattenRoot.getDeclaredField("group").getType()).isEqualTo(group);
        assertThat(flattenRoot.getDeclaredField("anotherGroup").getType()).isEqualTo(anotherGroup);

        // Conflicting Entry names stop under their wrappers.
        assertThat(byName.get("Entry")).hasSize(2).allMatch(Class::isMemberClass);
        assertThat(group.getDeclaredClasses()).extracting(Class::getSimpleName).containsExactly("Entry");
        assertThat(anotherGroup.getDeclaredClasses()).extracting(Class::getSimpleName).containsExactly("Entry");
        assertThat(group.getDeclaredField("entry").getType()).isEqualTo(byName.get("Entry").stream()
            .filter(c -> c.getEnclosingClass() == group)
            .findFirst()
            .orElseThrow());
        assertThat(anotherGroup.getDeclaredField("entry").getType()).isEqualTo(byName.get("Entry").stream()
            .filter(c -> c.getEnclosingClass() == anotherGroup)
            .findFirst()
            .orElseThrow());

        // Deep unique chain fully flattens to package scope.
        assertThat(byName.get("DeepRoot").getFirst().isMemberClass()).isFalse();
        assertThat(byName.get("Level1").getFirst().isMemberClass()).isFalse();
        assertThat(byName.get("Level2").getFirst().isMemberClass()).isFalse();
        assertThat(byName.get("Level3").getFirst().isMemberClass()).isFalse();

        var deepRoot = byName.get("DeepRoot").getFirst();
        var level1 = byName.get("Level1").getFirst();
        var level2 = byName.get("Level2").getFirst();
        var level3 = byName.get("Level3").getFirst();
        assertThat(deepRoot.getDeclaredField("level1").getType()).isEqualTo(level1);
        assertThat(level1.getDeclaredField("level2").getType()).isEqualTo(level2);
        assertThat(level2.getDeclaredField("level3").getType()).isEqualTo(level3);
    }

    private static Map<String, List<Class<?>>> bySimpleName(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.groupingBy(Class::getSimpleName));
    }
}
