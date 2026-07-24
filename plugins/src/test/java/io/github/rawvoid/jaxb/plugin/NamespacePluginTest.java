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
 * XJC integration tests for {@link NamespacePlugin}.
 * Uses dedicated {@code namespace.xsd} only.
 */
class NamespacePluginTest extends AbstractXJCMojoTestCase {

    private static final String NS = "https://www.github.com/rawvoid/xjc-plugins/namespace";
    private static final String EXTRA_NS = "https://www.github.com/rawvoid/xjc-plugins/namespace/extra";
    private static final String DEFAULT_PKG = "com.github.rawvoid.xjc_plugins.namespace";
    private static final String DEFAULT_ITEM = "com\\.github\\.rawvoid\\.xjc_plugins\\.namespace\\.Item";

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

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("namespace.xsd");
    }

    @Test
    void mapsNamespaceToPackageAndPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-mapping",
            "-ns=" + NS,
            "-prefix=n1",
            "-package=pkg1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            var annotation = xmlSchema(clazz);
            assertThat(annotation.namespace()).isEqualTo(NS);
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void mapsNamespaceCompactWithPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-mapping=" + NS + "->pkg1:n1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void mapsNamespaceCompactPackageOnly() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-mapping=" + NS + "->pkg1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
        });
    }

    @Test
    void configPrefixBasic() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void configPackageFilterMatches() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-package=" + DEFAULT_PKG.replace(".", "\\."),
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo(DEFAULT_PKG);
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void configPackageFilterMisses() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-package=com\\.example\\.other",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(xmlSchema(clazz).xmlns())
                .noneMatch(xmlns -> NS.equals(xmlns.namespaceURI()) && "n1".equals(xmlns.prefix()));
        });
    }

    @Test
    void configMultipleNamespacesOnSamePackage() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1",
            "-xmlns",
            "-ns=" + EXTRA_NS,
            "-prefix=ex"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void configMultipleNamespacesCompact() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-xmlns=" + NS + "->n1",
            "-xmlns=" + EXTRA_NS + "->ex"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void emptyOptionsIsNoOp() throws Exception {
        var args = List.of("-Xnamespace");
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(xmlSchema(clazz)).isNotNull();
            assertThat(xmlSchema(clazz).namespace()).isEqualTo(NS);
        });
    }

    @Test
    void configUpdatesExistingPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=updated"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("updated");
            assertThat(xmlNsFor(clazz, NS)).hasSize(1);
        });
    }

    @Test
    void configMultipleConfigsMergeXmlns() throws Exception {
        var packageRegex = DEFAULT_PKG.replace(".", "\\.");
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-package=" + packageRegex,
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-config",
            "-package=" + packageRegex,
            "-xmlns",
            "-ns=" + EXTRA_NS,
            "-prefix=second"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("first");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("second");
        });
    }

    @Test
    void configDuplicateNamespaceReplacement() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-config",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=second"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(xmlNsFor(clazz, NS)).hasSize(1);
            assertThat(prefixFor(clazz, NS)).isEqualTo("second");
        });
    }

    @Test
    void mappingAndConfigCanCombine() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-mapping=" + NS + "->pkg1:n1",
            "-config",
            "-package=pkg1",
            "-xmlns=" + EXTRA_NS + "->ex"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void xpathLiteralAndEscapeXmlHelpers() {
        assertThat(NamespacePlugin.xpathLiteral("http://a.com")).isEqualTo("'http://a.com'");
        assertThat(NamespacePlugin.xpathLiteral("a'b")).isEqualTo("\"a'b\"");
        assertThat(NamespacePlugin.xpathLiteral("a'b\"c")).isEqualTo("concat('a',\"'\",'b\"c')");
        assertThat(NamespacePlugin.escapeXml("a&b\"c'd<e>f"))
            .isEqualTo("a&amp;b&quot;c&apos;d&lt;e&gt;f");
    }

    @Test
    void generateBindingsUsesWildcardSchemaLocation() {
        var plugin = new NamespacePlugin();
        var mapping = new NamespacePlugin.NamespaceMappingConfig();
        mapping.namespace = "http://example.com/a&b";
        mapping.packageName = "com.example.a";
        plugin.mappings = List.of(mapping);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("schemaLocation=\"*\"");
        assertThat(bindings).contains("targetNamespace=&apos;http://example.com/a&amp;b&apos;");
        assertThat(bindings).contains("name=\"com.example.a\"");
    }
}
