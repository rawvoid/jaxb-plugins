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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class FlattenInnerClassPluginTest extends AbstractXJCMojoTestCase {

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("flatten-inner-class.xsd");
    }

    @Test
    void testFlattenInnerClasses() throws Exception {
        var args = List.of(
            "-Xflatten-inner-class"
        );
        var classes = testExecute(args, ".*", null);
        var classBySimpleName = classes.stream()
            .collect(Collectors.groupingBy(Class::getSimpleName));

        assertThat(classBySimpleName).containsKeys("FlattenRoot", "Group", "AnotherGroup", "Entry");

        var flattenRootClass = classBySimpleName.get("FlattenRoot").getFirst();
        var groupClass = classBySimpleName.get("Group").getFirst();
        var anotherGroupClass = classBySimpleName.get("AnotherGroup").getFirst();

        assertThat(flattenRootClass.getDeclaredField("group").getType()).isEqualTo(groupClass);
        assertThat(flattenRootClass.getDeclaredField("anotherGroup").getType()).isEqualTo(anotherGroupClass);

        assertThat(groupClass.isMemberClass()).isFalse();
        assertThat(anotherGroupClass.isMemberClass()).isFalse();
    }
}
