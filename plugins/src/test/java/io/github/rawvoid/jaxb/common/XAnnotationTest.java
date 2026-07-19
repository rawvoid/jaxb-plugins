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

package io.github.rawvoid.jaxb.common;

import org.junit.jupiter.api.Test;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
public class XAnnotationTest {

    @Test
    void testXAnnotationParser() throws Exception {
        var source = """
            @javax.annotation.processing.Generated(value = "Xjc", date = "2025-01-01T00:00:00Z")
            """;
        var xAnnotation = XAnnotationParser.INSTANCE.parse(source);
        var annotationClass = xAnnotation.getAnnotationClass();
        assertThat(annotationClass).isEqualTo(javax.annotation.processing.Generated.class);
        for (var xAnnotationField : xAnnotation.getFieldsList()) {
            var fieldName = xAnnotationField.getName();
            if ("value".equals(fieldName)) {
                assertThat(((String[]) xAnnotationField.getValue())[0]).isEqualTo("Xjc");
            } else if ("date".equals(fieldName)) {
                assertThat(xAnnotationField.getValue()).isEqualTo("2025-01-01T00:00:00Z");
            }
        }
    }

}
