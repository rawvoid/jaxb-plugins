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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link LombokPlugin}.
 * Uses dedicated {@code lombok.xsd} only.
 */
class LombokPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com\\.github\\.rawvoid\\.xjc_plugins\\.lombok";
    private static final String PERSON = PKG + "\\.Person";
    private static final String ORDER = PKG + "\\.Order";
    private static final String EMPTY_CHILD = PKG + "\\.EmptyChild";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("lombok.xsd");
    }

    @Test
    void testBaselineWithoutPlugin() throws Exception {
        testExecute(List.of(), PERSON, (source, clazz) -> {
            assertThat(source).doesNotContain("@Data");
            assertThat(hasPropertyGetterInSource(source)).isTrue();
            assertThat(hasPropertySetterInSource(source)).isTrue();
        });
    }

    @Test
    void testDefaultLombok() throws Exception {
        testExecute(List.of("-Xlombok"), PERSON, (source, clazz) -> {
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
        testExecute(args, PERSON, (source, clazz) -> {
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
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isTrue();
        });
    }

    @Test
    void testBuilderOnStandaloneType() throws Exception {
        // Person: superclass Object, no generated subclasses → official @Builder.
        var args = List.of("-Xlombok", "-builder");
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).contains("@Builder");
            assertThat(source).doesNotContain("@SuperBuilder");
            assertThat(source).contains("@NoArgsConstructor");
            assertThat(source).contains("@AllArgsConstructor");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
            assertThat(hasPropertySetterInSource(source)).isFalse();
        });
    }

    @Test
    void testSuperBuilderOnInheritanceChain() throws Exception {
        // EmptyChild extends BaseType (both generated this round) → SuperBuilder on the whole chain.
        var args = List.of("-Xlombok", "-builder");
        testExecute(args, EMPTY_CHILD, (source, clazz) -> {
            assertThat(source).contains("@SuperBuilder");
            assertThat(source).doesNotContain("@lombok.Builder");
            assertThat(source).contains("@NoArgsConstructor");
            // EmptyChild has no declared fields → no @AllArgsConstructor.
            assertThat(source).doesNotContain("@AllArgsConstructor");
        });
        testExecute(args, PKG + "\\.BaseType", (source, clazz) -> {
            // Abstract base still gets SuperBuilder so the child builder can chain.
            assertThat(source).contains("@SuperBuilder");
            assertThat(source).doesNotContain("@lombok.Builder");
            assertThat(source).contains("@NoArgsConstructor");
            assertThat(java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())).isTrue();
        });
    }

    @Test
    void testRegexFilter() throws Exception {
        var args = List.of("-Xlombok", "-regex=.*Person");
        testExecute(args, PERSON, (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(hasPropertyGetterInSource(source)).isFalse();
        });
        testExecute(args, ORDER, (source, clazz) -> {
            assertThat(source).doesNotContain("@Data");
            assertThat(hasPropertyGetterInSource(source)).isTrue();
        });
    }

    @Test
    void testEqualsAndHashCodeCallSuperOnSubclass() throws Exception {
        testExecute(List.of("-Xlombok"), EMPTY_CHILD, (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).contains("@EqualsAndHashCode");
            assertThat(source).contains("callSuper");
            assertThat(source).contains("true");
        });
    }

    @Test
    void testNoCallSuperOnRootType() throws Exception {
        testExecute(List.of("-Xlombok"), PERSON, (source, clazz) -> {
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
