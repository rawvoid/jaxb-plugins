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
 * XJC integration tests for {@link RenameMultiElementPropPlugin}.
 * Uses dedicated {@code rename-multi-element-prop.xsd} only.
 */
class RenameMultiElementPropPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com\\.github\\.rawvoid\\.xjc_plugins\\.rename_multi_element_prop";
    private static final String BAG = PKG + "\\.Bag";
    private static final String DUAL = PKG + "\\.DualChoice";
    private static final String PLAIN = PKG + "\\.PlainList";
    private static final String TIMED = PKG + "\\.TimedDerived";

    private static final String OPTION = "-Xrename-multi-element-prop";

    private static List<String> fieldNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
            .map(Field::getName)
            .filter(n -> !n.contains("$"))
            .collect(Collectors.toList());
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("rename-multi-element-prop.xsd");
    }

    @Test
    void baselineChoiceKeepsCompositeOrName() throws Exception {
        testExecute(List.of(), BAG, (source, clazz) -> {
            assertThat(fieldNames(clazz))
                .anyMatch(n -> n.contains("Or") || n.contains("or"));
            assertThat(fieldNames(clazz)).doesNotContain("items");
        });
    }

    @Test
    void renamesSingleChoiceToItems() throws Exception {
        testExecute(List.of(OPTION), BAG, (source, clazz) -> {
            assertThat(fieldNames(clazz)).contains("items");
            assertThat(fieldNames(clazz)).noneMatch(n -> n.contains("Or"));
            // XJC maps multi-element choice of element refs to @XmlElementRefs (or @XmlElements).
            assertThat(source).containsAnyOf("@XmlElements", "@XmlElementRefs");
        });
    }

    @Test
    void renamesTwoChoicesToItemsAndItems2() throws Exception {
        testExecute(List.of(OPTION), DUAL, (source, clazz) -> {
            assertThat(fieldNames(clazz)).containsExactlyInAnyOrder("items", "items2");
        });
    }

    @Test
    void leavesPlainListPropertyName() throws Exception {
        testExecute(List.of(OPTION), PLAIN, (source, clazz) -> {
            assertThat(fieldNames(clazz)).contains("tag");
            assertThat(fieldNames(clazz)).doesNotContain("items");
        });
    }

    @Test
    void baselineExposesMultiMemberRestFallback() throws Exception {
        // TimedDerived: Time collision + Note → multi-member catch-all "rest" (or "content").
        testExecute(List.of(), TIMED, (source, clazz) -> {
            assertThat(fieldNames(clazz).stream().map(String::toLowerCase).toList())
                .anyMatch(n -> n.equals("rest") || n.equals("content"));
        });
    }

    @Test
    void renamesMultiMemberRestFallbackToItems() throws Exception {
        testExecute(List.of(OPTION), TIMED, (source, clazz) -> {
            var names = fieldNames(clazz).stream().map(String::toLowerCase).toList();
            assertThat(names).contains("items");
            assertThat(names).noneMatch(n -> n.equals("rest") || n.equals("content"));
        });
    }
}
