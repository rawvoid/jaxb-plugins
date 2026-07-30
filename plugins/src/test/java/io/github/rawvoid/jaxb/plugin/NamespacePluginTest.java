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
            "-package-mapping",
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
            "-package-mapping=" + NS + "->pkg1:n1"
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
            "-package-mapping=" + NS + "->pkg1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
        });
    }

    @Test
    void mapsNamespaceCompactTrailingEmptyPrefix() throws Exception {
        // "ns->pkg:" keeps package clean and writes an explicit empty XmlNs prefix.
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=" + NS + "->pkg1:"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEmpty();
        });
    }

    @Test
    void mapsNamespaceCompactWithWhitespaceAroundSeparators() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=" + NS + " -> pkg1 : n1"
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void mapsNamespaceCompactEmptyPrefixWithWhitespace() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=" + NS + "  ->  pkg1  :  "
        );
        testExecute(args, "pkg1\\.Item", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg1");
            assertThat(prefixFor(clazz, NS)).isEmpty();
        });
    }

    @Test
    void nsPrefixBasic() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
            "-xmlns",
            "-ns=" + NS,
            "-prefix=n1"
        );
        testExecute(args, DEFAULT_ITEM, (source, clazz) -> {
            assertThat(prefixFor(clazz, NS)).isEqualTo("n1");
        });
    }

    @Test
    void nsPrefixPackageFilterMatches() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void nsPrefixPackageFilterMisses() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void nsPrefixMultipleNamespacesOnSamePackage() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void nsPrefixMultipleNamespacesCompact() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void nsPrefixUpdatesExistingPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void nsPrefixMultipleRulesMergeXmlns() throws Exception {
        var packageRegex = DEFAULT_PKG.replace(".", "\\.");
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
            "-package=" + packageRegex,
            "-xmlns",
            "-ns=" + NS,
            "-prefix=first",
            "-ns-prefix",
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
    void nsPrefixDuplicateNamespaceReplacement() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-ns-prefix",
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
    void packageMappingAndNsPrefixCanCombine() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=" + NS + "->pkg1:n1",
            "-ns-prefix",
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
    void packageMappingNestedXmlnsWithPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping",
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
    void packageMappingXmlnsListWithoutTopLevelPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping",
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
    void packageMappingXmlnsListOverridesPrefix() throws Exception {
        var args = List.of(
            "-Xnamespace",
            "-package-mapping",
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
    void generateBindingsUsesScdByTargetNamespace() {
        var plugin = new NamespacePlugin();
        var packageMapping = new NamespacePlugin.PackageMappingConfig();
        packageMapping.namespace = "http://example.com/a";
        packageMapping.packageName = "com.example.a";
        plugin.packageMappings = List.of(packageMapping);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("scd=\"x-schema::ns0\"");
        assertThat(bindings).contains("xmlns:ns0=\"http://example.com/a\"");
        assertThat(bindings).contains("if-exists=\"true\"");
        assertThat(bindings).contains("name=\"com.example.a\"");
        assertThat(bindings).doesNotContain("schemaLocation");
    }

    @Test
    void generateBindingsEscapesXmlInAttributes() {
        var plugin = new NamespacePlugin();
        var packageMapping = new NamespacePlugin.PackageMappingConfig();
        packageMapping.namespace = "http://example.com/a&b";
        packageMapping.packageName = "com.example.\"a\"";
        plugin.packageMappings = List.of(packageMapping);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("xmlns:ns0=\"http://example.com/a&amp;b\"");
        assertThat(bindings).contains("name=\"com.example.&quot;a&quot;\"");
    }

    @Test
    void mapsPackageWhenMultipleDocumentsShareTargetNamespace() throws Exception {
        // Same targetNamespace split across two documents must not raise
        // "Multiple schemaBindings are defined for the target namespace".
        schemaIncludes = List.of("namespace.xsd", "namespace-shared.xsd");
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=" + NS + "->pkg.shared:n1"
        );
        var classes = testExecute(args, "pkg\\.shared\\.(Item|OtherItem)", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg.shared");
        });
        assertThat(classes.stream().map(Class::getName))
            .contains("pkg.shared.Item", "pkg.shared.OtherItem");
        var item = classes.stream()
            .filter(c -> "pkg.shared.Item".equals(c.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(prefixFor(item, NS)).isEqualTo("n1");
    }

    @Test
    void mapsEmptyNamespaceToPackageStructured() throws Exception {
        schemaIncludes = List.of("namespace-empty.xsd");
        var args = List.of(
            "-Xnamespace",
            "-package-mapping",
            "-ns=",
            "-package=pkg.empty"
        );
        testExecute(args, "pkg\\.empty\\.NoNsItem", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg.empty");
        });
    }

    @Test
    void mapsEmptyNamespaceToPackageOmittingNs() throws Exception {
        // -ns is optional; omit it entirely for schemas without targetNamespace.
        schemaIncludes = List.of("namespace-empty.xsd");
        var args = List.of(
            "-Xnamespace",
            "-package-mapping",
            "-package=pkg.empty"
        );
        testExecute(args, "pkg\\.empty\\.NoNsItem", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg.empty");
        });
    }

    @Test
    void mapsEmptyNamespaceToPackageCompact() throws Exception {
        schemaIncludes = List.of("namespace-empty.xsd");
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=->pkg.empty"
        );
        testExecute(args, "pkg\\.empty\\.NoNsItem", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg.empty");
        });
    }

    @Test
    void mapsEmptyNamespaceToPackageCompactWithTrailingPrefix() throws Exception {
        // Compact empty-ns + prefix form must still map the package; XJC often omits
        // @XmlSchema for the empty namespace, so prefix application may no-op there.
        schemaIncludes = List.of("namespace-empty.xsd");
        var args = List.of(
            "-Xnamespace",
            "-package-mapping=->pkg.empty:n0"
        );
        testExecute(args, "pkg\\.empty\\.NoNsItem", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo("pkg.empty");
        });
    }

    @Test
    void generateBindingsSupportsEmptyNamespace() {
        var plugin = new NamespacePlugin();
        var packageMapping = new NamespacePlugin.PackageMappingConfig();
        packageMapping.namespace = "";
        packageMapping.packageName = "pkg.empty";
        plugin.packageMappings = List.of(packageMapping);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("scd=\"x-schema::\"");
        assertThat(bindings).contains("xmlns=\"\"");
        assertThat(bindings).contains("name=\"pkg.empty\"");
        assertThat(bindings).contains("if-exists=\"true\"");
    }

    @Test
    void generateBindingsSupportsNullNamespaceAsEmpty() {
        var plugin = new NamespacePlugin();
        var packageMapping = new NamespacePlugin.PackageMappingConfig();
        packageMapping.namespace = null;
        packageMapping.packageName = "pkg.empty";
        plugin.packageMappings = List.of(packageMapping);

        var bindings = plugin.generateBindings();
        assertThat(bindings).contains("scd=\"x-schema::\"");
        assertThat(bindings).contains("name=\"pkg.empty\"");
    }
}
