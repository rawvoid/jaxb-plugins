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
import jakarta.xml.bind.annotation.XmlSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test cases for NsPrefixPlugin.
 *
 * @author Rawvoid
 */
class NsPrefixPluginTest extends AbstractXJCMojoTestCase {

    @Test
    void testNsPrefixPluginBasic() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=n1"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace = "https://www.github.com/rawvoid/xjc-plugins";

            if (namespace.equals(annotation.namespace())) {
                Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
                    .forEach(xmlns -> assertThat(xmlns.prefix()).isEqualTo("n1"));
            }
        });
    }

    @Test
    void testNsPrefixPluginWithPackageFilter() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-package=com\\.github\\.rawvoid\\..*",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=n1"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace = "https://www.github.com/rawvoid/xjc-plugins";

            if (namespace.equals(annotation.namespace())) {
                List<jakarta.xml.bind.annotation.XmlNs> matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
                    .toList();

                String packageName = clazz.getPackageName();
                if (packageName.startsWith("com.github.rawvoid")) {
                    assertThat(matchedNamespaces).isNotEmpty();
                    assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("n1");
                } else {
                    assertThat(matchedNamespaces).isEmpty();
                }
            }
        });
    }

    @Test
    void testNsPrefixPluginMultipleNamespaces() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=n1",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins/test",
            "-prefix=test"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace1 = "https://www.github.com/rawvoid/xjc-plugins";
            var namespace2 = "https://www.github.com/rawvoid/xjc-plugins/test";

            if (namespace1.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace1.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("n1");
            } else if (namespace2.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace2.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("test");
            }
        });
    }

    @Test
    void testNsPrefixPluginWithEmptyConfigs() throws Exception {
        var args = List.of("-Xns-prefix");
        testExecute(args, ".*package-info", (source, clazz) -> {
            assertThat(clazz).isNotNull();
        });
    }

    @Test
    void testNsPrefixPluginUpdateExisting() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=updated"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace = "https://www.github.com/rawvoid/xjc-plugins";

            if (namespace.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("updated");
            }
        });
    }

    @Test
    void testNsPrefixPluginMultipleConfigs() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-package=com\\.github\\.rawvoid\\..*",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=first",
            "-config",
            "-package=com\\.github\\.rawvoid\\..*",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins/test",
            "-prefix=second"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace1 = "https://www.github.com/rawvoid/xjc-plugins";
            var namespace2 = "https://www.github.com/rawvoid/xjc-plugins/test";

            if (namespace1.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace1.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("first");
            } else if (namespace2.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace2.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("second");
            }
        });
    }

    @Test
    void testNsPrefixPluginDuplicateNamespaceReplacement() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=first",
            "-xmlns",
            "-ns=https://www.github.com/rawvoid/xjc-plugins",
            "-prefix=second"
        );
        testExecute(args, ".*package-info", (source, clazz) -> {
            var annotation = clazz.getAnnotation(XmlSchema.class);
            var namespace = "https://www.github.com/rawvoid/xjc-plugins";

            if (namespace.equals(annotation.namespace())) {
                var matchedNamespaces = Arrays.stream(annotation.xmlns())
                    .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
                    .toList();
                assertThat(matchedNamespaces).hasSize(1);
                assertThat(matchedNamespaces.getFirst().prefix()).isEqualTo("second");
            }
        });
    }
}
