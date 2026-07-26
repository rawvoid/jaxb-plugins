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

import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.PackageOutline;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JAXB plugin that customizes Java package names and XML namespace prefixes for XML namespaces.
 * <p>
 * Package mappings are applied through injected SCD-based external bindings (one
 * {@code schemaBindings} per target namespace, safe when a namespace spans many XSD files).
 * Ns-prefix rules are applied on generated {@code @XmlSchema} annotations (including
 * package-filtered multi-namespace rules ported from the former {@code -Xns-prefix} plugin).
 * </p>
 * <p>
 * Usage examples:
 * </p>
 * <pre>
 * {@code
 * // Map namespace to package (optional prefix for that package's own namespace)
 * -Xnamespace -package-mapping=http://example.com->com.example:ex
 * -Xnamespace -package-mapping -ns=http://example.com -package=com.example -prefix=ex
 *
 * // Map package and declare multiple xmlns entries for that package
 * -Xnamespace -package-mapping -ns=http://a.com -package=com.a -prefix=a \
 *   -xmlns=http://b.com->b -xmlns -ns=http://c.com -prefix=c
 *
 * // Prefix-only rules on all packages (or a package regex filter)
 * -Xnamespace -ns-prefix -xmlns -ns=http://example.com -prefix=ex
 * -Xnamespace -ns-prefix -package=com\.example\.* -xmlns=http://example.com->ex
 *
 * // Multiple xmlns on one package (one -xmlns; repeated -ns starts the next item)
 * -Xnamespace -ns-prefix -xmlns -ns=http://a.com -prefix=a -ns=http://b.com -prefix=b
 * }
 * </pre>
 *
 * @author Rawvoid
 */
@Option(name = "Xnamespace", description = "Customize Java package names and XML namespace prefixes")
public class NamespacePlugin extends AbstractPlugin {

    private static final String BINDINGS_SYSTEM_ID =
        "urn:io.github.rawvoid:jaxb-plugins:namespace-package-mapping";

    @Option(name = "package-mapping", description = "Map an XML target namespace to a Java package (optional XML prefixes)")
    List<PackageMappingConfig> packageMappings;

    @Option(name = "ns-prefix", description = "Package-scoped namespace prefix mapping (optional Java package regex filter)")
    List<NsPrefixConfig> nsPrefixes;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) {
        injectBindings(opt);
    }

    /**
     * Injects external bindings that assign Java packages by XML target namespace.
     * <p>
     * Bindings use SCD ({@code x-schema::prefix}) so each mapping attaches
     * {@code schemaBindings} once per target namespace, even when that namespace is
     * split across many schema documents. {@code schemaLocation="*"} cannot do this:
     * XJC ignores {@code node} under {@code *} and would inject one binding per document,
     * which fails with "Multiple schemaBindings are defined for the target namespace".
     * </p>
     * <p>
     * SCD requires extension mode; this method enables it when package mappings are present.
     * </p>
     */
    private void injectBindings(Options options) {
        var bindings = generateBindings();
        if (bindings == null || bindings.isBlank()) {
            return;
        }
        // SCD selectors need extension mode; without it XJC reports SCD_NOT_ENABLED as an error.
        options.compatibilityMode = Options.EXTENSION;
        var inputSource = new InputSource(new StringReader(bindings));
        inputSource.setSystemId(BINDINGS_SYSTEM_ID);
        options.addBindFile(inputSource);
    }

    /**
     * Builds an external binding file for all configured package mappings.
     *
     * @return binding XML, or {@code null} when no package mappings are configured
     */
    String generateBindings() {
        if (packageMappings == null || packageMappings.isEmpty()) {
            return null;
        }
        var body = new StringBuilder();
        var index = 0;
        for (var packageMapping : packageMappings) {
            var ns = packageMapping.namespace == null ? "" : packageMapping.namespace;
            var pkg = packageMapping.packageName == null ? "" : packageMapping.packageName;
            if (ns.isEmpty()) {
                // Empty targetNamespace: SCD empty prefix resolves via default xmlns.
                body.append("""
                    <jaxb:bindings scd="x-schema::" xmlns="" if-exists="true">
                        <jaxb:schemaBindings>
                          <jaxb:package name="%s"/>
                        </jaxb:schemaBindings>
                    </jaxb:bindings>
                    """.formatted(escapeXml(pkg)));
            } else {
                var prefix = "ns" + index++;
                body.append("""
                    <jaxb:bindings scd="x-schema::%s" xmlns:%s="%s" if-exists="true">
                        <jaxb:schemaBindings>
                          <jaxb:package name="%s"/>
                        </jaxb:schemaBindings>
                    </jaxb:bindings>
                    """.formatted(prefix, prefix, escapeXml(ns), escapeXml(pkg)));
            }
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <jaxb:bindings
                xmlns:jaxb="https://jakarta.ee/xml/ns/jaxb"
                version="3.0">
            """ + body + "</jaxb:bindings>";
    }

    private static String escapeXml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        if (packageMappings != null) {
            for (var packageMapping : packageMappings) {
                for (var packageOutline : outline.getAllPackageContexts()) {
                    applyPackageMappingXmlns(packageOutline, packageMapping);
                }
            }
        }
        if (nsPrefixes != null) {
            for (var packageOutline : outline.getAllPackageContexts()) {
                processNsPrefix(packageOutline);
            }
        }
        return true;
    }

    private void applyPackageMappingXmlns(PackageOutline packageOutline, PackageMappingConfig packageMapping) {
        // Compact form "ns->pkg:" yields an empty prefix string; treat blank as absent.
        var hasPrefix = packageMapping.prefix != null && !packageMapping.prefix.isBlank();
        var hasXmlNsList = packageMapping.xmlNsConfigs != null && !packageMapping.xmlNsConfigs.isEmpty();
        if (!hasPrefix && !hasXmlNsList) {
            return;
        }
        var xmlSchema = findXmlSchema(packageOutline);
        if (xmlSchema == null) {
            return;
        }
        var packageNamespace = AnnotationUtils.readStringMember(xmlSchema, "namespace");
        if (!Objects.equals(packageNamespace, packageMapping.namespace)) {
            return;
        }
        var xmlns = ensureXmlnsArray(xmlSchema);
        if (hasPrefix) {
            upsertXmlnsEntry(xmlns, packageMapping.namespace, packageMapping.prefix);
        }
        if (hasXmlNsList) {
            for (var xmlNsConfig : packageMapping.xmlNsConfigs) {
                upsertXmlnsEntry(xmlns, xmlNsConfig.namespace, xmlNsConfig.prefix);
            }
        }
    }

    private void processNsPrefix(PackageOutline packageOutline) {
        var jPackage = packageOutline._package();
        var packageName = jPackage.name();
        var matchedConfigs = nsPrefixes.stream()
            .filter(config -> config.packageRegex == null || config.packageRegex.matcher(packageName).matches())
            .filter(config -> config.xmlNsConfigs != null && !config.xmlNsConfigs.isEmpty())
            .toList();
        if (matchedConfigs.isEmpty()) {
            return;
        }

        var xmlSchema = findXmlSchema(packageOutline);
        if (xmlSchema == null) {
            return;
        }

        var xmlns = ensureXmlnsArray(xmlSchema);
        for (var matchedConfig : matchedConfigs) {
            for (var xmlNsConfig : matchedConfig.xmlNsConfigs) {
                upsertXmlnsEntry(xmlns, xmlNsConfig.namespace, xmlNsConfig.prefix);
            }
        }
    }

    private static JAnnotationUse findXmlSchema(PackageOutline packageOutline) {
        return AnnotationUtils.findAnnotation(packageOutline._package(), XmlSchema.class).orElse(null);
    }

    private static JAnnotationArrayMember ensureXmlnsArray(JAnnotationUse xmlSchema) {
        var xmlns = (JAnnotationArrayMember) xmlSchema.getAnnotationMembers().get("xmlns");
        if (xmlns == null) {
            xmlns = xmlSchema.paramArray("xmlns");
        }
        return xmlns;
    }

    private static void upsertXmlnsEntry(JAnnotationArrayMember xmlns, String namespace, String prefix) {
        var existing = findExistingXmlnsEntry(xmlns, namespace);
        if (existing.isPresent()) {
            existing.get().param("prefix", prefix);
            return;
        }
        var entry = xmlns.annotate(XmlNs.class);
        entry.param("prefix", prefix);
        entry.param("namespaceURI", namespace);
    }

    private static Optional<JAnnotationUse> findExistingXmlnsEntry(JAnnotationArrayMember xmlns, String targetNamespace) {
        return xmlns.annotations().stream()
            .filter(anno -> Objects.equals(AnnotationUtils.readStringMember(anno, "namespaceURI"), targetNamespace))
            .findFirst();
    }

    /**
     * Namespace to Java package mapping, with optional XML prefixes for the mapped package.
     * <p>
     * Compact: {@code -package-mapping=http://a.com->com.example.a} or
     * {@code -package-mapping=http://a.com->com.example.a:prefix} (more specific template first).
     * Structured form may nest {@code -xmlns} entries for additional prefixes on the same package.
     * </p>
     */
    @Compact(formats = {"{ns}->{package}:{prefix}", "{ns}->{package}"})
    public static class PackageMappingConfig {

        @Option(name = "ns", required = true, description = "XML target namespace URI")
        String namespace;

        @Option(name = "package", required = true, description = "Target Java package name for this namespace")
        String packageName;

        @Option(name = "prefix", description = "XML namespace prefix written on @XmlSchema for this namespace")
        String prefix;

        @Option(name = "xmlns", description = "Xmlns entries applied to the package produced by this map")
        List<XmlNsConfig> xmlNsConfigs;
    }

    /**
     * Package-scoped XML namespace prefix mapping (optional Java package regex filter).
     */
    public static class NsPrefixConfig {

        @Option(name = "package", description = "Java package name regex pattern; omit to match all packages")
        Pattern packageRegex;

        @Option(name = "xmlns", description = "XML namespace to prefix mappings")
        List<XmlNsConfig> xmlNsConfigs;
    }

    /**
     * Single namespace URI to prefix mapping.
     * <p>
     * Compact: {@code -xmlns=http://example.com->ex} (repeatable).
     * </p>
     */
    @Compact(formats = {"{ns}->{prefix}"})
    public static class XmlNsConfig {

        @Option(name = "ns", required = true, description = "XML namespace URI")
        String namespace;

        @Option(name = "prefix", required = true, description = "XML namespace prefix")
        String prefix;
    }
}
