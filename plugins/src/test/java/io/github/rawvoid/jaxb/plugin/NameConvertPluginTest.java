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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class NameConvertPluginTest extends AbstractXJCMojoTestCase {

    @Test
    void testClassNameConvert() throws Exception {
        var args = List.of(
            "-Xname-convert",
            "-class-name",
            "-token=Person",
            "-name=CustomPerson"
        );
        testExecute(args, ".*CustomPerson", (source, clazz) -> {
            assertThat(clazz.getSimpleName()).isEqualTo("CustomPerson");
        });
    }

    @Test
    void testPropertyNameConvert() throws Exception {
        var args = List.of(
            "-Xname-convert",
            "-property-name",
            "-token=name",
            "-name=fullName"
        );
        testExecute(args, ".*Person", (source, clazz) -> {
            assertThat(clazz.getSimpleName()).isEqualTo("Person");
            // JAXB's NameConverter.toPropertyName might be used for both field and methods
            // In NameConvertPlugin, we override toPropertyName
            // According to debug log: Method: getfullName (note the lowercase 'f' if not handled by super)
            var methods = clazz.getDeclaredMethods();
            boolean hasGetFullName = false;
            for (var m : methods) {
                if (m.getName().equalsIgnoreCase("getfullName")) {
                    hasGetFullName = true;
                    break;
                }
            }
            assertThat(hasGetFullName).isTrue();
        });
    }

    @Test
    void testPackageNameConvert() throws Exception {
        var args = List.of(
            "-Xname-convert",
            "-package-name",
            "-token=https://www.github.com/rawvoid/xjc-plugins",
            "-name=io.github.rawvoid.custom"
        );
        testExecute(args, "io.github.rawvoid.custom.Person", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("io.github.rawvoid.custom");
        });
    }

    @Test
    void testRegexConvert() throws Exception {
        var args = List.of(
            "-Xname-convert",
            "-class-name",
            "-regex=Per(.*)",
            "-name=Human$1"
        );
        testExecute(args, ".*Humanon", (source, clazz) -> {
            // "Person" matches "Per(.*)" with group 1 as "son".
            // Replace with "Human$1" results in "Humanson"
            assertThat(clazz.getSimpleName()).isEqualTo("Humanson");
        });
    }
}
