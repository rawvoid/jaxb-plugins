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

package io.github.rawvoid.jaxb.plugin.lombok;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LombokSingularsTest {

    @Test
    void autoSingularizeMatchesLombokRules() {
        assertThat(LombokSingulars.autoSingularize("nicknames")).isEqualTo("nickname");
        assertThat(LombokSingulars.autoSingularize("items")).isEqualTo("item");
        assertThat(LombokSingulars.autoSingularize("children")).isEqualTo("child");
        // Already singular / not auto-singularizable → null (caller must set @Singular value).
        assertThat(LombokSingulars.autoSingularize("tag")).isNull();
        assertThat(LombokSingulars.autoSingularize("flight")).isNull();
    }
}
