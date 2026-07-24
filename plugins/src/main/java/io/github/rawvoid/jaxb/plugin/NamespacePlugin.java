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
import com.sun.codemodel.JAnnotationStringValue;
import com.sun.codemodel.JAnnotationUse;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.PackageOutline;
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JAXB plugin that customizes Java package names and XML namespace prefixes for XML namespaces.
 * <p>
 * Package mapping is applied through injected external bindings and does not depend on
 * command-line order of schema files. Prefix rules are applied on generated {@code @XmlSchema}
 * annotations (including package-filtered multi-namespace rules ported from the former
 * {@code -Xns-prefix} plugin).
 * </p>
 * <p>
 * Usage examples:
 * </p>
 * <pre>
 * {@code
 * // Map namespace to package (optional prefix for that package's own namespace)
 * -Xnamespace -mapping=http://example.com->com.example:ex
 * -Xnamespace -mapping -ns=http://example.com -package=com.example -prefix=ex
 *
 * // Map package and declare multiple xmlns entries for that package
 * -Xnamespace -mapping -ns=http://a.com -package=com.a -prefix=a \
 *   -xmlns=http://b.com->b -xmlns -ns=http://c.com -prefix=c
 *
 * // Prefix-only rules on all packages (or a package regex filter)
 * -Xnamespace -config -xmlns -ns=http://example.com -prefix=ex
 * -Xnamespace -config -package=com\.example\.* -xmlns=http://example.com->ex
 *
 * // Multiple xmlns on one package (one -xmlns; repeated -ns starts the next item)
 * -Xnamespace -config -xmlns -ns=http://a.com -prefix=a -ns=http://b.com -prefix=b
 * }
 * </pre>
 *
 * @author Rawvoid
 */
@Option(name = "Xnamespace", description = "Customize Java package names and XML namespace prefixes")
public class NamespacePlugin extends AbstractPlugin {

    private static final String BINDINGS_SYSTEM_ID = "namespace-plugin-bindings.xml";

    @Option(name = "mapping", description = "Namespace to Java package mapping rule (optional XML prefix)")
    List<NamespaceMappingConfig> mappings;

    @Option(name = "config", description = "Package-scoped XML namespace prefix mapping rule")
    List<PackageXmlNsConfig> configs;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        validateMappings();
        injectBindings(opt);
    }

    private void validateMappings() throws BadCommandLineException {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        var seenNamespaces = new HashSet<String>();
        for (var mapping : mappings) {
            if (mapping.namespace == null || mapping.namespace.isBlank()) {
                throw new BadCommandLineException("Namespace mapping requires a non-blank -ns value");
            }
            if (mapping.packageName == null || mapping.packageName.isBlank()) {
                throw new BadCommandLineException(
                    "Namespace mapping for '%s' requires -package".formatted(mapping.namespace));
            }
            if (!seenNamespaces.add(mapping.namespace)) {
                throw new BadCommandLineException(
                    "Duplicate namespace mapping for '%s'".formatted(mapping.namespace));
            }
            validateXmlNsConfigs(mapping.xmlNsConfigs, "mapping '%s'".formatted(mapping.namespace));
        }
    }

    private static void validateXmlNsConfigs(List<XmlNsConfig> xmlNsConfigs, String owner)
        throws BadCommandLineException {
        if (xmlNsConfigs == null || xmlNsConfigs.isEmpty()) {
            return;
        }
        var seen = new HashSet<String>();
        for (var xmlNs : xmlNsConfigs) {
            if (xmlNs.namespace == null || xmlNs.namespace.isBlank()) {
                throw new BadCommandLineException(
                    "Xmlns entry under %s requires a non-blank -ns value".formatted(owner));
            }
            if (xmlNs.prefix == null || xmlNs.prefix.isBlank()) {
                throw new BadCommandLineException(
                    "Xmlns entry for '%s' under %s requires -prefix".formatted(xmlNs.namespace, owner));
            }
            if (!seen.add(xmlNs.namespace)) {
                throw new BadCommandLineException(
                    "Duplicate xmlns namespace '%s' under %s".formatted(xmlNs.namespace, owner));
            }
        }
    }

    /**
     * Injects external bindings that assign Java packages by XML target namespace.
     * Bindings use {@code schemaLocation="*"} so they do not depend on when schemas were
     * registered relative to plugin arguments.
     */
    private void injectBindings(Options options) {
        var bindings = generateBindings();
        if (bindings == null || bindings.isBlank()) {
            return;
        }
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
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        var body = new StringBuilder();
        for (var mapping : mappings) {
            var node = "/xs:schema[@targetNamespace=%s]".formatted(xpathLiteral(mapping.namespace));
            body.append("""
                <jaxb:bindings schemaLocation="*" node="%s">
                    <jaxb:schemaBindings>
                      <jaxb:package name="%s"/>
                    </jaxb:schemaBindings>
                </jaxb:bindings>
                """.formatted(escapeXml(node), escapeXml(mapping.packageName)));
        }
        if (body.isEmpty()) {
            return null;
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <jaxb:bindings
                xmlns:jaxb="https://jakarta.ee/xml/ns/jaxb"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                version="3.0">
            """ + body + "</jaxb:bindings>";
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        if (mappings != null) {
            for (var mapping : mappings) {
                for (var packageOutline : outline.getAllPackageContexts()) {
                    applyMappingXmlns(packageOutline, mapping);
                }
            }
        }
        if (configs != null) {
            for (var packageOutline : outline.getAllPackageContexts()) {
                processPackageConfig(packageOutline);
            }
        }
        return true;
    }

    private void applyMappingXmlns(PackageOutline packageOutline, NamespaceMappingConfig mapping) {
        var hasPrefix = mapping.prefix != null;
        var hasXmlNsList = mapping.xmlNsConfigs != null && !mapping.xmlNsConfigs.isEmpty();
        if (!hasPrefix && !hasXmlNsList) {
            return;
        }
        var xmlSchema = findXmlSchema(packageOutline);
        if (xmlSchema == null) {
            return;
        }
        var packageNamespace = readAnnotationString(xmlSchema, "namespace");
        if (!Objects.equals(packageNamespace, mapping.namespace)) {
            return;
        }
        var xmlns = ensureXmlnsArray(xmlSchema);
        if (hasPrefix) {
            upsertXmlnsEntry(xmlns, mapping.namespace, mapping.prefix);
        }
        if (hasXmlNsList) {
            for (var xmlNsConfig : mapping.xmlNsConfigs) {
                upsertXmlnsEntry(xmlns, xmlNsConfig.namespace, xmlNsConfig.prefix);
            }
        }
    }

    private void processPackageConfig(PackageOutline packageOutline) {
        var jPackage = packageOutline._package();
        var packageName = jPackage.name();
        var matchedConfigs = configs.stream()
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
        return packageOutline._package().annotations().stream()
            .filter(a -> a.getAnnotationClass().fullName().equals(XmlSchema.class.getName()))
            .findFirst()
            .orElse(null);
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
            .filter(anno -> Objects.equals(readAnnotationString(anno, "namespaceURI"), targetNamespace))
            .findFirst();
    }

    private static String readAnnotationString(JAnnotationUse annotation, String member) {
        var value = annotation.getAnnotationMembers().get(member);
        if (!(value instanceof JAnnotationStringValue stringValue)) {
            return null;
        }
        return stringValue.toString();
    }

    /**
     * Quotes {@code value} as an XPath string literal.
     */
    static String xpathLiteral(String value) {
        if (value == null) {
            return "''";
        }
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        if (!value.contains("\"")) {
            return "\"" + value + "\"";
        }
        var parts = value.split("'", -1);
        var concat = new StringBuilder("concat(");
        for (var i = 0; i < parts.length; i++) {
            if (i > 0) {
                concat.append(",\"'\",");
            }
            concat.append('\'').append(parts[i]).append('\'');
        }
        concat.append(')');
        return concat.toString();
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /**
     * Namespace to Java package mapping, with optional XML prefixes for the mapped package.
     * <p>
     * Compact: {@code -mapping=http://a.com->com.example.a} or
     * {@code -mapping=http://a.com->com.example.a:prefix} (more specific template first).
     * Structured form may nest {@code -xmlns} entries for additional prefixes on the same package.
     * </p>
     */
    @Compact(formats = {"{ns}->{package}:{prefix}", "{ns}->{package}"})
    public static class NamespaceMappingConfig {

        @Option(name = "ns", required = true, description = "XML target namespace URI")
        String namespace;

        @Option(name = "package", required = true, description = "Target Java package name for this namespace")
        String packageName;

        @Option(name = "prefix", description = "XML namespace prefix written on @XmlSchema for this namespace")
        String prefix;

        @Option(name = "xmlns", description = "Xmlns entries applied to the package produced by this mapping")
        List<XmlNsConfig> xmlNsConfigs;
    }

    /**
     * Package-scoped XML namespace prefix rules (optional Java package regex filter).
     */
    public static class PackageXmlNsConfig {

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
