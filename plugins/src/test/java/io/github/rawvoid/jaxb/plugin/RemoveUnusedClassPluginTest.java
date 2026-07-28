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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RemoveUnusedClassPlugin}.
 */
class RemoveUnusedClassPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.remove_unused_class.";
    private final String optionCmd = optionCommand(RemoveUnusedClassPlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("remove-unused-class.xsd");
    }

    @Test
    void baselineGeneratesAllClasses() throws Exception {
        var classes = testExecute(List.of(), ".*", null);
        var names = classes.stream().map(Class::getName).toList();

        assertThat(names).contains(
            PKG + "MainRequestType",
            PKG + "UsedChildType",
            PKG + "UsedEnum",
            PKG + "BasePolymorphicType",
            PKG + "DerivedPolymorphicType",
            PKG + "UnusedType",
            PKG + "UnusedEnum",
            PKG + "CyclicA",
            PKG + "CyclicB",
            PKG + "KeepCandidateType"
        );
    }

    @Test
    void removesUnusedClassesAndEnums() throws Exception {
        var args = List.of(optionCmd, "-verbose");
        var classes = testExecute(args, ".*", null);
        var names = classes.stream().map(Class::getName).toList();

        // Reachable types preserved
        assertThat(names).contains(
            PKG + "MainRequestType",
            PKG + "UsedChildType",
            PKG + "UsedEnum",
            PKG + "BasePolymorphicType"
        );

        // Unreachable types (including unreferenced polymorphic subtypes when preserve-polymorphism=false) pruned
        assertThat(names).doesNotContain(
            PKG + "DerivedPolymorphicType",
            PKG + "UnusedType",
            PKG + "UnusedEnum",
            PKG + "CyclicA",
            PKG + "CyclicB",
            PKG + "KeepCandidateType"
        );
    }

    @Test
    void keepsClassesMatchingKeepRegex() throws Exception {
        var args = List.of(
            optionCmd,
            "-keep-classes=.*KeepCandidate.*"
        );
        var classes = testExecute(args, ".*", null);
        var names = classes.stream().map(Class::getName).toList();

        assertThat(names).contains(PKG + "KeepCandidateType");
        assertThat(names).doesNotContain(PKG + "UnusedType", PKG + "UnusedEnum");
    }

    @Test
    void respectsPreservePolymorphismTrue() throws Exception {
        var args = List.of(
            optionCmd,
            "-preserve-polymorphism=true"
        );
        var classes = testExecute(args, ".*", null);
        var names = classes.stream().map(Class::getName).toList();

        assertThat(names).contains(PKG + "BasePolymorphicType", PKG + "DerivedPolymorphicType");
    }
}
