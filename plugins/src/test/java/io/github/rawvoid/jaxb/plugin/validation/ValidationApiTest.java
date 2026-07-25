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

package io.github.rawvoid.jaxb.plugin.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationApiTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"jakarta", "Jakarta", " JAKARTA "})
    void parseJakarta(String raw) {
        var api = ValidationApi.parse(raw);
        assertThat(api.constraint("NotNull")).isEqualTo("jakarta.validation.constraints.NotNull");
        assertThat(api.validFqcn()).isEqualTo("jakarta.validation.Valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javax", "Javax", " JAVAX "})
    void parseJavax(String raw) {
        var api = ValidationApi.parse(raw);
        assertThat(api.constraint("Size")).isEqualTo("javax.validation.constraints.Size");
        assertThat(api.validFqcn()).isEqualTo("javax.validation.Valid");
    }

    @Test
    void parseInvalid() {
        assertThatThrownBy(() -> ValidationApi.parse("foo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("-api");
    }
}
