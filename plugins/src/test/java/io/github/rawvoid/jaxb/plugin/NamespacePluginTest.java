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
            "-package-map",
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
            "-package-map=" + NS + "->pkg1:n1"
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
            "-package-map=" + NS + "->pkg1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
        });
    }

    @Test
    void xmlnsRulePrefixBasic() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void xmlnsRulePackageFilterMatches() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void xmlnsRulePackageFilterMisses() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void xmlnsRuleMultipleNamespacesOnSamePackage() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void xmlnsRuleMultipleNamespacesCompact() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void xmlnsRuleUpdatesExistingPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void xmlnsRuleMultipleRulesMergeXmlns() throws Exception {
        var packageRegex = DEFAULT_PKG.replace(".", "\\.");
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
            "-package=" + packageRegex,
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-xmlns-rule",
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
    void xmlnsRuleDuplicateNamespaceReplacement() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-xmlns-rule",
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
    void packageMapAndXmlnsRuleCanCombine() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-map=" + NS + "->pkg1:n1",
            "-xmlns-rule",
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
    void packageMapNestedXmlnsWithPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-map",
            "-ns=" + NS,
            "-package=pkg1",
            "-prefix=n1",
            "-xmlns=" + EXTRA_NS + "->ex"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void packageMapXmlnsListWithoutTopLevelPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-map",
            "-ns=" + NS,
            "-package=pkg1",
            "-xmlns=" + NS + "->n1",
            "-xmlns=" + EXTRA_NS + "->ex"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
            assertThat(prefixFor(clazz, EXTRA_NS)).isEqualTo("ex");
        });
    }

    @Test
    void packageMapXmlnsListOverridesPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-map",
            "-ns=" + NS,
            "-package=pkg1",
            "-prefix=first",
            "-xmlns=" + NS + "->second"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(xmlNsFor(clazz, NS)).hasSize(1);
            assertThat(prefixFor(clazz, NS)).isEqualTo("second");
        });
    }

    @Test
    void generateBindingsUsesWildcardSchemaLocation() {
        var plugin = new NamespacePlugin();
        var packageMap = new NamespacePlugin.PackageMapConfig();
        packageMap.namespace = "http://example.com/a";
        packageMap.packageName = "com.example.a";
        plugin.packageMaps = List.of(packageMap);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("schemaLocation=\"*\"");
        assertThat(bindings).contains("node=\"/xs:schema[@targetNamespace='http://example.com/a']\"");
        assertThat(bindings).contains("name=\"com.example.a\"");
    }
}
