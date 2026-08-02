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
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link RemoveGetterPlugin}.
 * Uses dedicated {@code remove-getter.xsd} only.
 */
class RemoveGetterPluginTest extends AbstractXJCMojoTestCase {

    private static final String PERSON = "com\\.github\\.rawvoid\\.xjc_plugins\\.remove_getter\\.Person";

    private final String optionCmd = optionCommand(RemoveGetterPlugin.class);

    private static String optionCommand(Class<? extends OptionPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    private static Set<String> methodNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("remove-getter.xsd");
    }

    @Test
    void baselineKeepsAccessors() throws Exception {
        testExecute(List.of(), PERSON, (source, clazz) -> {
            assertThat(methodNames(clazz)).contains("getName", "isActive", "getTag", "setName", "setActive");
        });
    }

    @Test
    void removesPropertyGettersOnly() throws Exception {
        testExecute(List.of(optionCmd), PERSON, (source, clazz) -> {
            var names = methodNames(clazz);
            assertThat(names).doesNotContain("getName", "isActive", "getTag");
            // Setters must remain when only getters are removed.
            assertThat(names).contains("setName", "setActive");
            // Fields stay for marshalling.
            assertThat(clazz.getDeclaredField("name")).isNotNull();
            assertThat(clazz.getDeclaredField("active")).isNotNull();
        });
    }
}
