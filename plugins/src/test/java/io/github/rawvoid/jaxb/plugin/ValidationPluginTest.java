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
 * XJC integration tests for {@link ValidationPlugin}.
 * Uses {@code validation.xsd}.
 */
class ValidationPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.example.validation";
    private static final String USER_TYPE_FILTER = PKG + "\\.UserType";
    private static final String ADDRESS_TYPE_FILTER = PKG + "\\.AddressType";

    private final String optionCmd = optionCommand(ValidationPlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("validation.xsd");
    }

    @Test
    void testUsage() {
        assertThat(new ValidationPlugin().getUsage()).isNotNull();
    }

    @Test
    void baselineWithoutPlugin() throws Exception {
        testExecute(List.of(), USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("jakarta.validation");
            assertThat(source).doesNotContain("javax.validation");
            assertThat(source).doesNotContain("@NotNull");
            assertThat(source).doesNotContain("@Size");
            assertThat(source).doesNotContain("@Pattern");
            assertThat(source).doesNotContain("@Valid");
        });
    }

    @Test
    void defaultJakartaValidation() throws Exception {
        testExecute(List.of(optionCmd), USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).contains("jakarta.validation.constraints.");
            assertThat(source).contains("@NotNull");
            assertThat(source).contains("@Size");
            assertThat(source).contains("@Pattern");
            assertThat(source).contains("@Min");
            assertThat(source).contains("@Max");
            assertThat(source).contains("@Digits");
            assertThat(source).contains("@DecimalMin");
            assertThat(source).contains("@Valid");
        });
    }

    @Test
    void javaxValidationMode() throws Exception {
        var args = List.of(optionCmd, "-api=javax");
        testExecute(args, USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).contains("javax.validation.constraints.");
            assertThat(source).doesNotContain("jakarta.validation");
            assertThat(source).contains("@NotNull");
            assertThat(source).contains("@Size");
            assertThat(source).contains("@Valid");
        });
    }

    @Test
    void disableValidOption() throws Exception {
        var args = List.of(optionCmd, "-disable-valid=true");
        testExecute(args, USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).contains("@NotNull");
            assertThat(source).contains("@Size");
            assertThat(source).doesNotContain("@Valid");
        });
    }

    @Test
    void classNameFilter() throws Exception {
        var args = List.of(optionCmd, "-class-name=.*UserType");
        testExecute(args, USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).contains("@NotNull");
        });
        testExecute(args, ADDRESS_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("@NotNull");
            assertThat(source).doesNotContain("@Size");
        });
    }

    @Test
    void fieldNameFilter() throws Exception {
        var args = List.of(optionCmd, "-field-name=username");
        testExecute(args, USER_TYPE_FILTER, (source, clazz) -> {
            assertThat(source).contains("@NotNull");
            assertThat(source).contains("@Size");
            // Bio, email, age, salary fields skipped by filter
            assertThat(source).doesNotContain("@Pattern");
            assertThat(source).doesNotContain("@Min");
        });
    }
}
