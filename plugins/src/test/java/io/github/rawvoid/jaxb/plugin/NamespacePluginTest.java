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
import jakarta.xml.bind.annotation.XmlSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class NamespacePluginTest extends AbstractXJCMojoTestCase {

    @Test
    void testNamespacePackagePlugin() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-mapping",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=n1",
            "-package=pkg1",
            "-mapping",
            "-ns=https://www.github.com/rawvoid/xjc-plugins/jsr310",
            "-package=pkg2"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace = "https://www.github.com/rawvoid/xjc-plugins";
            if (namespace.equals(annotation.namespace())) {
                assertThat(clazz.getPackageName()).isEqualTo("pkg1");
                Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
                    .forEach(xmlns -> assertThat(xmlns.prefix()).isEqualTo("n1"));
            } else if ("https://www.github.com/rawvoid/xjc-plugins/jsr310".equals(annotation.namespace())) {
                assertThat(clazz.getPackageName()).isEqualTo("pkg2");
            }
        });
    }

}
