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
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link NsPrefixPlugin}.
 * Uses dedicated {@code ns-prefix.xsd} only.
 */
class NsPrefixPluginTest extends AbstractXJCMojoTestCase {

    private static final String NS = "https://www.github.com/rawvoid/xjc-plugins/ns-prefix";
    private static final String EXTRA_NS = "https://www.github.com/rawvoid/xjc-plugins/ns-prefix/extra";
    private static final String PKG = "com.github.rawvoid.xjc_plugins.ns_prefix";
    private static final String ITEM = "com\\.github\\.rawvoid\\.xjc_plugins\\.ns_prefix\\.Item";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("ns-prefix.xsd");
    }

    @Test
    void testNsPrefixPluginBasic() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void testPackageFilterMatches() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-package=com\\.github\\.rawvoid\\.xjc_plugins\\.ns_prefix",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo(PKG);
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void testPackageFilterMisses() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-package=com\\.example\\.other",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(xmlSchema(clazz).xmlns())
                .noneMatch(xmlns -> NS.equals(xmlns.namespaceURI()) && "n1".equals(xmlns.prefix()));
        });
    }

    @Test
    void testMultipleNamespacesOnSamePackage() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1",
            "-xmlns",
            "-ns=" + EXTRA_NS,
            "-prefix=ex"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void testEmptyConfigsIsNoOp() throws Exception {
        var args = List.of("-Xns-prefix");
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(xmlSchema(clazz)).isNotNull();
            assertThat(xmlSchema(clazz).namespace()).isEqualTo(NS);
        });
    }

    @Test
    void testUpdateExistingPrefix() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=updated"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("updated");
            assertThat(xmlNsFor(clazz, NS)).hasSize(1);
        });
    }

    @Test
    void testMultipleConfigsMergeXmlns() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-package=com\\.github\\.rawvoid\\.xjc_plugins\\.ns_prefix",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-config",
            "-package=com\\.github\\.rawvoid\\.xjc_plugins\\.ns_prefix",
            "-xmlns",
            "-ns=" + EXTRA_NS,
            "-prefix=second"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("first");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("second");
        });
    }

    @Test
    void testDuplicateNamespaceReplacement() throws Exception {
        var args = List.of(
            "-Xns-prefix",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=second"
        );
        testExecute(args, ITEM, (source, clazz) -> {
            assertThat(xmlNsFor(clazz, NS)).hasSize(1);
            assertThat(prefixFor(clazz, NS)).isEqualTo("second");
        });
    }

    private static XmlSchema xmlSchema(Class<?> clazz) {
        var annotation = clazz.getPackage().getAnnotation(XmlSchema.class);
        assertThat(annotation).isNotNull();
        return annotation;
    }

    private static List<XmlNs> xmlNsFor(Class<?> clazz, String namespace) {
        return Arrays.stream(xmlSchema(clazz).xmlns())
            .filter(xmlns -> namespace.equals(xmlns.namespaceURI()))
            .toList();
    }

    private static String prefixFor(Class<?> clazz, String namespace) {
        var matched = xmlNsFor(clazz, namespace);
        assertThat(matched).as("xmlns for %s", namespace).isNotEmpty();
        return matched.getFirst().prefix();
    }
}
