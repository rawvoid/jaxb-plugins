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

import static org.assertj.core.api.Assertions.assertThat;

class SchemaFacetsTest {

    @Test
    void parseIntAndLong() {
        assertThat(SchemaFacets.parseInt("42")).isEqualTo(42);
        assertThat(SchemaFacets.parseInt(" 7 ")).isEqualTo(7);
        assertThat(SchemaFacets.parseInt(null)).isNull();
        assertThat(SchemaFacets.parseInt("")).isNull();
        assertThat(SchemaFacets.parseInt("x")).isNull();

        assertThat(SchemaFacets.parseLong("18")).isEqualTo(18L);
        assertThat(SchemaFacets.parseLong("1.5")).isNull();
        assertThat(SchemaFacets.parseLong(null)).isNull();
    }

    @Test
    void ofNullReturnsEmpty() {
        var facets = SchemaFacets.of(null);
        assertThat(facets.get("minLength")).isNull();
        assertThat(facets.patterns()).isEmpty();
    }
}
