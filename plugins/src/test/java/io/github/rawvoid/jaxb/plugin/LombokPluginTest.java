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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class LombokPluginTest extends AbstractXJCMojoTestCase {

    @Test
    void testBaselineWithoutPlugin() throws Exception {
        testExecute(List.of(), ".*Person", (source, clazz) -> {
            assertThat(source).doesNotContain("@Data");
            assertThat(hasPropertyGetterInSource(source)).isTrue();
            assertThat(hasPropertySetterInSource(source)).isTrue();
        });
    }

    @Test
    void testDefaultLombok() throws Exception {
        testExecute(List.of("-Xlombok"), ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isFalse();
            // Lombok APT restores accessors on the compiled class
            assertThat(hasGetter(clazz)).isTrue();
            assertThat(hasSetter(clazz)).isTrue();
        });
    }

    @Test
    void testCustomAnnotations() throws Exception {
        var args = List.of(
            "-Xlombok",
            "-anno=@lombok.Getter",
            "-anno=@lombok.Setter"
        );
        testExecute(args, ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Getter");
            assertThat(source).contains("@Setter");
            assertThat(source).doesNotContain("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isFalse();
        });
    }

    @Test
    void testKeepSetters() throws Exception {
        var args = List.of("-Xlombok", "-remove-setter=false");
        testExecute(args, ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isTrue();
        });
    }

    @Test
    void testBuilder() throws Exception {
        var args = List.of("-Xlombok", "-builder");
        testExecute(args, ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).contains("@Builder");
            assertThat(source).contains("@NoArgsConstructor");
            assertThat(source).contains("@AllArgsConstructor");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isFalse();
        });
    }

    @Test
    void testRegexFilter() throws Exception {
        var args = List.of("-Xlombok", "-regex=.*Person");
        testExecute(args, ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
        });
        // Order uses same schema package when schema.xsd is the only include; Person-only regex
        // means other generated types (if any) keep accessors — schema.xsd is Person-centric.
    }

    @Test
    void testEqualsAndHashCodeCallSuperOnSubclass() throws Exception {
        schemaIncludes = List.of("normalize-class.xsd");
        testExecute(List.of("-Xlombok"), ".*EmptyChild", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).contains("@EqualsAndHashCode");
            assertThat(source).contains("callSuper");
            assertThat(source).contains("true");
        });
    }

    @Test
    void testNoCallSuperOnRootType() throws Exception {
        testExecute(List.of("-Xlombok"), ".*Person", (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).doesNotContain("@EqualsAndHashCode");
        });
    }

    private static boolean hasPropertyGetterInSource(String source) {
        return source.lines().anyMatch(line -> {
            var trimmed = line.trim();
            return trimmed.startsWith("public ")
                && (trimmed.contains(" get") || trimmed.contains(" is"))
                && trimmed.contains("()")
                && !trimmed.contains("getClass");
        });
    }

    private static boolean hasPropertySetterInSource(String source) {
        return source.lines().anyMatch(line -> {
            var trimmed = line.trim();
            return trimmed.startsWith("public ") && trimmed.contains(" set") && trimmed.contains("(");
        });
    }

    private static boolean hasGetter(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .anyMatch(method ->
                (method.getName().startsWith("get") || method.getName().startsWith("is"))
                    && method.getParameterCount() == 0
                    && !method.getName().equals("getClass"));
    }

    private static boolean hasSetter(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .anyMatch(method -> method.getName().startsWith("set") && method.getParameterCount() == 1);
    }
}
