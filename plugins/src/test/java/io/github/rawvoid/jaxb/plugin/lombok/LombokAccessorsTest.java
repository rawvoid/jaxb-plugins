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

/**
 * Names must come from Lombok {@code HandlerUtil} (shadow), not a local reimplementation.
 */
class LombokAccessorsTest {

    @Test
    void handlerUtilBootstrapSucceeded() {
        // Module depends on lombok; CI must not silently degrade to fallback naming.
        assertThat(LombokAccessors.isHandlerUtilAvailable()).isTrue();
    }

    @Test
    void scalarNames() {
        assertThat(LombokAccessors.toGetterName("id", false)).isEqualTo("getId");
        assertThat(LombokAccessors.toSetterName("id", false)).isEqualTo("setId");
        assertThat(LombokAccessors.toGetterName("requestId", false)).isEqualTo("getRequestId");
        assertThat(LombokAccessors.toSetterName("requestId", false)).isEqualTo("setRequestId");
    }

    @Test
    void primitiveBooleanUsesIsPrefix() {
        assertThat(LombokAccessors.toGetterName("active", true)).isEqualTo("isActive");
        assertThat(LombokAccessors.toSetterName("active", true)).isEqualTo("setActive");
    }

    @Test
    void boxedBooleanUsesGetPrefix() {
        assertThat(LombokAccessors.toGetterName("active", false)).isEqualTo("getActive");
        assertThat(LombokAccessors.toSetterName("active", false)).isEqualTo("setActive");
    }

    @Test
    void isPrefixedBooleanField() {
        assertThat(LombokAccessors.toGetterName("isRunning", true)).isEqualTo("isRunning");
        assertThat(LombokAccessors.toSetterName("isRunning", true)).isEqualTo("setRunning");
    }

    @Test
    void fallbackMatchesMinimalBeanConvention() {
        assertThat(LombokAccessors.fallbackName("id", false, true)).isEqualTo("getId");
        assertThat(LombokAccessors.fallbackName("active", true, true)).isEqualTo("isActive");
        assertThat(LombokAccessors.fallbackName("isRunning", true, false)).isEqualTo("setRunning");
    }

    @Test
    void basicCapitalizeMatchesLombokBasicStrategy() {
        assertThat(LombokAccessors.basicCapitalize("id")).isEqualTo("Id");
        assertThat(LombokAccessors.basicCapitalize("requestId")).isEqualTo("RequestId");
        // Second char upper → first char uppercased (BASIC), not only title-cased.
        assertThat(LombokAccessors.basicCapitalize("uRL")).isEqualTo("URL");
        assertThat(LombokAccessors.basicCapitalize("ID")).isEqualTo("ID");
        assertThat(LombokAccessors.fallbackName("uRL", false, true)).isEqualTo("getURL");
    }
}
