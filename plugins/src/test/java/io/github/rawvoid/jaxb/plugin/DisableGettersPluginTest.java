/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class DisableGettersPluginTest extends AbstractXJCMojoTestCase {

    Option option = DisableGettersPlugin.class.getAnnotation(Option.class);

    @Test
    public void testDisablePlugin() throws Exception {
        testExecute(List.of(), ".*Person", ((source, clazz) -> {
            assertThat(hasGetter(clazz)).isTrue();
        }));
    }

    @Test
    public void testPlugin() throws Exception {
        var optionCmd = option.prefix() + option.name();
        testExecute(List.of(optionCmd), ".*Person", (source, clazz) -> {
            assertThat(hasGetter(clazz)).isFalse();
        });
    }

    public boolean hasGetter(Class<?> clazz) {
        var methods = clazz.getDeclaredMethods();
        return Arrays.stream(methods)
            .anyMatch(method -> (method.getName().startsWith("get") || method.getName().startsWith("is")) && method.getParameterCount() == 0);
    }
}
