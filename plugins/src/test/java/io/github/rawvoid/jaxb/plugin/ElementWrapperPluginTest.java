/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
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
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ElementWrapperPluginTest extends AbstractXJCMojoTestCase {

    @Test
    public void testElementWrapper() throws Exception {
        var args = List.<String>of(
            "-Xelement-wrapper"
        );
        testExecute(args, ".*RootType", (source, clazz) -> {
            var itemsField = clazz.getDeclaredField("items");
            var xmlElementAnno = itemsField.getAnnotation(XmlElement.class);
            var xmlElementWrapper = itemsField.getAnnotation(XmlElementWrapper.class);
            assertThat(xmlElementAnno).isNotNull();
            assertThat(xmlElementAnno.name()).isEqualTo("item");
            assertThat(xmlElementWrapper).isNotNull();
            assertThat(xmlElementWrapper.name()).isEqualTo("##default");
        });
    }
}
