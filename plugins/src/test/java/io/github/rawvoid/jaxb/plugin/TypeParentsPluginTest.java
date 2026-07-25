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
 * XJC integration tests for {@link TypeParentsPlugin}.
 * Uses {@code type-parents.xsd}.
 */
class TypeParentsPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.example.typeparents";
    private static final String USER_REQUEST_FILTER = PKG + "\\.UserRequestType";
    private static final String USER_RESPONSE_FILTER = PKG + "\\.UserResponseType";
    private static final String BASE_DATA_FILTER = PKG + "\\.BaseDataType";
    private static final String EXTENDED_DATA_FILTER = PKG + "\\.ExtendedDataType";

    private final String optionCmd = optionCommand(TypeParentsPlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("type-parents.xsd");
    }

    @Test
    void testUsage() {
        assertThat(new TypeParentsPlugin().getUsage()).isNotNull();
    }

    @Test
    void baselineWithoutPlugin() throws Exception {
        testExecute(List.of(), USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("Serializable");
            assertThat(source).doesNotContain("serialVersionUID");
            assertThat(source).doesNotContain("implements");
        });
    }

    @Test
    void serializableShortcutDefaultUid() throws Exception {
        var args = List.of(optionCmd, "-serializable=true");
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements Serializable");
            assertThat(source).contains("private static final long serialVersionUID = 1L;");
        });
    }

    @Test
    void serializableShortcutCustomUid() throws Exception {
        var args = List.of(optionCmd, "-serializable=true", "-serial-version-uid=987654321");
        testExecute(args, USER_REQUEST_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements Serializable");
            assertThat(source).contains("private static final long serialVersionUID = 987654321L;");
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
        });
        testExecute(args, USER_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements EventListener");
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
        });
    }

    @Test
    void preservesXsdInheritance() throws Exception {
        var args = List.of(
            optionCmd,
            "-super-class=.*DataType->io.github.rawvoid.jaxb.plugin.TestBaseDto"
        );
        testExecute(args, EXTENDED_DATA_FILTER, (source, clazz) -> {
            // ExtendedDataType extends BaseDataType in XSD, so superclass injection is skipped for it
            assertThat(source).contains("extends BaseDataType");
            assertThat(source).doesNotContain("extends TestBaseDto");
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
        });
    }
}
