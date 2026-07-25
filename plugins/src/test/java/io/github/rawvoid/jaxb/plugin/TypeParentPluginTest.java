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

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EventListener;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link TypeParentPlugin}.
 * Uses {@code type-parent.xsd}.
 */
class TypeParentPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.type_parent";
    private static final String USER_REQUEST_FILTER = PKG.replace(".", "\\.") + "\\.UserRequestType";
    private static final String USER_RESPONSE_FILTER = PKG.replace(".", "\\.") + "\\.UserResponseType";
    private static final String BASE_DATA_FILTER = PKG.replace(".", "\\.") + "\\.BaseDataType";
    private static final String EXTENDED_DATA_FILTER = PKG.replace(".", "\\.") + "\\.ExtendedDataType";

    private final String optionCmd = optionCommand(TypeParentPlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("type-parent.xsd");
    }

    @Test
    void testUsage() {
        var usage = new TypeParentPlugin().getUsage();
        assertThat(usage).isNotNull();
        assertThat(usage).doesNotContain("-class-name");
    }

    @Test
    void baselineWithoutPlugin() throws Exception {
        testExecute(List.of(), USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("Serializable");
            assertThat(source).doesNotContain("serialVersionUID");
            assertThat(source).doesNotContain("implements");
            assertThat(Serializable.class.isAssignableFrom(clazz)).isFalse();
        });
    }

    @Test
    void pluginWithoutSubOptionsIsNoOp() throws Exception {
        testExecute(List.of(optionCmd), USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("implements");
            assertThat(source).doesNotContain("serialVersionUID");
            assertThat(Serializable.class.isAssignableFrom(clazz)).isFalse();
        });
    }

    @Test
    void serializableShortcutFixedUid() throws Exception {
        var args = List.of(optionCmd, "-serializable=true");
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements Serializable");
            assertThat(source).contains("private static final long serialVersionUID = 1L;");
            assertThat(Serializable.class.isAssignableFrom(clazz)).isTrue();

            try {
                var field = clazz.getDeclaredField("serialVersionUID");
                assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
                field.setAccessible(true);
                assertThat(field.getLong(null)).isEqualTo(1L);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    void interfaceInjectionCompactFormat() throws Exception {
        var args = List.of(
            optionCmd,
            "-interface=.*UserRequestType->java.lang.Cloneable",
            "-interface=.*UserResponseType->java.util.EventListener"
        );
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements Cloneable");
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isTrue();
            assertThat(EventListener.class.isAssignableFrom(clazz)).isFalse();
        });
        testExecute(args, USER_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements EventListener");
            assertThat(EventListener.class.isAssignableFrom(clazz)).isTrue();
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isFalse();
        });
    }

    @Test
    void interfaceInjectionStructuredForm() throws Exception {
        var args = List.of(
            optionCmd,
            "-interface",
            "-name=.*UserRequestType",
            "-to=java.lang.Cloneable"
        );
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements Cloneable");
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isTrue();
        });
    }

    @Test
    void superclassInjectionCompactFormat() throws Exception {
        var args = List.of(
            optionCmd,
            "-super-class=.*BaseDataType->io.github.rawvoid.jaxb.plugin.TestBaseDto"
        );
        testExecute(args, BASE_DATA_FILTER, (source, clazz) -> {
            assertThat(source).contains("extends TestBaseDto");
            assertThat(TestBaseDto.class.isAssignableFrom(clazz)).isTrue();
            assertThat(clazz.getSuperclass()).isEqualTo(TestBaseDto.class);
        });
    }

    @Test
    void preservesXsdInheritance() throws Exception {
        var args = List.of(
            optionCmd,
            "-super-class=.*DataType->io.github.rawvoid.jaxb.plugin.TestBaseDto"
        );
        testExecute(args, EXTENDED_DATA_FILTER, (source, clazz) -> {
            // Direct XSD parent is kept; plugin does not rewrite ExtendedDataType's extends clause.
            // (BaseDataType may still get TestBaseDto from the same broad pattern — checked below.)
            assertThat(source).contains("extends BaseDataType");
            assertThat(source).doesNotContain("extends TestBaseDto");
            assertThat(clazz.getSuperclass().getSimpleName()).isEqualTo("BaseDataType");
        });
        // Broad pattern still injects into BaseDataType (no XSD parent).
        testExecute(args, BASE_DATA_FILTER, (source, clazz) -> {
            assertThat(source).contains("extends TestBaseDto");
            assertThat(clazz.getSuperclass()).isEqualTo(TestBaseDto.class);
        });
    }

    @Test
    void multipleInterfacesAccumulate() throws Exception {
        var args = List.of(
            optionCmd,
            "-serializable=true",
            "-interface=.*UserRequestType->java.lang.Cloneable"
        );
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("Serializable");
            assertThat(source).contains("Cloneable");
            assertThat(Serializable.class.isAssignableFrom(clazz)).isTrue();
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isTrue();
            var interfaces = Arrays.asList(clazz.getInterfaces());
            assertThat(interfaces).contains(Serializable.class, Cloneable.class);
        });
    }

    @Test
    void interfacePatternSelectsTargetClasses() throws Exception {
        var args = List.of(
            optionCmd,
            "-serializable=true",
            "-interface=.*UserRequestType->java.lang.Cloneable"
        );
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(Serializable.class.isAssignableFrom(clazz)).isTrue();
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isTrue();
        });
        // -serializable applies to all beans; interface pattern is the per-rule selector.
        testExecute(args, USER_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(Serializable.class.isAssignableFrom(clazz)).isTrue();
            assertThat(Cloneable.class.isAssignableFrom(clazz)).isFalse();
            assertThat(source).doesNotContain("Cloneable");
        });
    }
}
