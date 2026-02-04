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

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class NormalizeClassPluginTest extends AbstractXJCMojoTestCase {

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("normalize-class.xsd");
    }

    @Test
    void testNormalizeDuplicateClasses() throws Exception {
        var args = List.of(
            "-Xnormalize-class"
        );
        var classes = testExecute(args, ".*", null);
        var classBySimpleName = classes.stream()
            .collect(Collectors.groupingBy(Class::getSimpleName));

        assertThat(classBySimpleName).containsKeys("NormalizeRoot", "Group", "AnotherGroup", "Entry");
        assertThat(classBySimpleName).containsKey("BaseType");
        assertThat(classBySimpleName).containsKey("BaseNode");
        assertThat(classBySimpleName).containsKey("EmptyChild");
        assertThat(classBySimpleName).doesNotContainKey("Node");
        assertThat(classBySimpleName.get("Entry")).hasSize(1);

        var groupClass = classBySimpleName.get("Group").getFirst();
        var anotherGroupClass = classBySimpleName.get("AnotherGroup").getFirst();
        var entryClass = classBySimpleName.get("Entry").getFirst();
        var baseTypeClass = classBySimpleName.get("BaseType").getFirst();
        var baseNodeClass = classBySimpleName.get("BaseNode").getFirst();
        var emptyChildClass = classBySimpleName.get("EmptyChild").getFirst();
        var normalizeRootClass = classBySimpleName.get("NormalizeRoot").getFirst();

        assertThat(groupClass.getDeclaredField("entry").getType()).isEqualTo(entryClass);
        assertThat(anotherGroupClass.getDeclaredField("entry").getType()).isEqualTo(entryClass);
        assertThat(normalizeRootClass.getDeclaredField("emptyChild").getType()).isEqualTo(emptyChildClass);
        assertThat(normalizeRootClass.getDeclaredField("node").getType()).isEqualTo(baseNodeClass);
        assertThat(Modifier.isAbstract(baseTypeClass.getModifiers())).isTrue();
        assertThat(Modifier.isAbstract(baseNodeClass.getModifiers())).isFalse();
    }
}
