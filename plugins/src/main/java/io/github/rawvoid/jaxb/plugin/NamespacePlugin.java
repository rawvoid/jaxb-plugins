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
 * Package maps are applied through injected external bindings and do not depend on
 * command-line order of schema files. Xmlns rules are applied on generated {@code @XmlSchema}
 * annotations (including package-filtered multi-namespace rules ported from the former
 * {@code -Xns-prefix} plugin).
 * </p>
 * <p>
 * Usage examples:
 * </p>
 * <pre>
 * {@code
 * // Map namespace to package (optional prefix for that package's own namespace)
 * -Xnamespace -package-map=http://example.com->com.example:ex
 * -Xnamespace -package-map -ns=http://example.com -package=com.example -prefix=ex
 *
 * // Map package and declare multiple xmlns entries for that package
 * -Xnamespace -package-map -ns=http://a.com -package=com.a -prefix=a \
 *   -xmlns=http://b.com->b -xmlns -ns=http://c.com -prefix=c
 *
 * // Prefix-only rules on all packages (or a package regex filter)
 * -Xnamespace -xmlns-rule -xmlns -ns=http://example.com -prefix=ex
 * -Xnamespace -xmlns-rule -package=com\.example\.* -xmlns=http://example.com->ex
 *
 * // Multiple xmlns on one package (one -xmlns; repeated -ns starts the next item)
 * -Xnamespace -xmlns-rule -xmlns -ns=http://a.com -prefix=a -ns=http://b.com -prefix=b
 * }
 * </pre>
 *
 * @author Rawvoid
 */
@Option(name = "Xnamespace", description = "Customize Java package names and XML namespace prefixes")
public class NamespacePlugin extends AbstractPlugin {

    private static final String BINDINGS_SYSTEM_ID = "namespace-plugin-bindings.xml";

    @Option(name = "package-map", description = "Map an XML target namespace to a Java package (optional XML prefixes)")
    List<PackageMapConfig> packageMaps;

    @Option(name = "xmlns-rule", description = "Package-scoped xmlns rule (optional Java package regex filter)")
    List<XmlNsRuleConfig> xmlnsRules;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        validatePackageMaps();
        injectBindings(opt);
    }

    private void validatePackageMaps() throws BadCommandLineException {
        if (packageMaps == null || packageMaps.isEmpty()) {
            return;
        }
        var seenNamespaces = new HashSet<String>();
        for (var packageMap : packageMaps) {
            if (packageMap.namespace == null || packageMap.namespace.isBlank()) {
                throw new BadCommandLineException("Package map requires a non-blank -ns value");
            }
            if (packageMap.packageName == null || packageMap.packageName.isBlank()) {
                throw new BadCommandLineException(
                    "Package map for '%s' requires -package".formatted(packageMap.namespace));
            }
            if (!seenNamespaces.add(packageMap.namespace)) {
                throw new BadCommandLineException(
                    "Duplicate package map for namespace '%s'".formatted(packageMap.namespace));
            }
            validateXmlNsConfigs(packageMap.xmlNsConfigs, "package-map '%s'".formatted(packageMap.namespace));
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
     * Builds an external binding file for all configured package maps.
     *
     * @return binding XML, or {@code null} when no package maps are configured
     */
    String generateBindings() {
        if (packageMaps == null || packageMaps.isEmpty()) {
            return null;
        }
        var body = new StringBuilder();
        for (var packageMap : packageMaps) {
            var node = "/xs:schema[@targetNamespace=%s]".formatted(xpathLiteral(packageMap.namespace));
            body.append("""
                <jaxb:bindings schemaLocation="*" node="%s">
                    <jaxb:schemaBindings>
                      <jaxb:package name="%s"/>
                    </jaxb:schemaBindings>
                </jaxb:bindings>
                """.formatted(escapeXml(node), escapeXml(packageMap.packageName)));
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
        if (packageMaps != null) {
            for (var packageMap : packageMaps) {
                for (var packageOutline : outline.getAllPackageContexts()) {
                    applyPackageMapXmlns(packageOutline, packageMap);
                }
            }
        }
        if (xmlnsRules != null) {
            for (var packageOutline : outline.getAllPackageContexts()) {
                processXmlNsRule(packageOutline);
            }
        }
        return true;
    }

    private void applyPackageMapXmlns(PackageOutline packageOutline, PackageMapConfig packageMap) {
        var hasPrefix = packageMap.prefix != null;
        var hasXmlNsList = packageMap.xmlNsConfigs != null && !packageMap.xmlNsConfigs.isEmpty();
        if (!hasPrefix && !hasXmlNsList) {
            return;
        }
        var xmlSchema = findXmlSchema(packageOutline);
        if (xmlSchema == null) {
            return;
        }
        var packageNamespace = readAnnotationString(xmlSchema, "namespace");
        if (!Objects.equals(packageNamespace, packageMap.namespace)) {
            return;
        }
        var xmlns = ensureXmlnsArray(xmlSchema);
        if (hasPrefix) {
            upsertXmlnsEntry(xmlns, packageMap.namespace, packageMap.prefix);
        }
        if (hasXmlNsList) {
            for (var xmlNsConfig : packageMap.xmlNsConfigs) {
                upsertXmlnsEntry(xmlns, xmlNsConfig.namespace, xmlNsConfig.prefix);
            }
        }
    }

    private void processXmlNsRule(PackageOutline packageOutline) {
        var jPackage = packageOutline._package();
        var packageName = jPackage.name();
        var matchedRules = xmlnsRules.stream()
            .filter(rule -> rule.packageRegex == null || rule.packageRegex.matcher(packageName).matches())
            .filter(rule -> rule.xmlNsConfigs != null && !rule.xmlNsConfigs.isEmpty())
            .toList();
        if (matchedRules.isEmpty()) {
            return;
        }

        var xmlSchema = findXmlSchema(packageOutline);
        if (xmlSchema == null) {
            return;
        }

        var xmlns = ensureXmlnsArray(xmlSchema);
        for (var matchedRule : matchedRules) {
            for (var xmlNsConfig : matchedRule.xmlNsConfigs) {
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
     * Namespace to Java package map, with optional XML prefixes for the mapped package.
     * <p>
     * Compact: {@code -package-map=http://a.com->com.example.a} or
     * {@code -package-map=http://a.com->com.example.a:prefix} (more specific template first).
     * Structured form may nest {@code -xmlns} entries for additional prefixes on the same package.
     * </p>
     */
    @Compact(formats = {"{ns}->{package}:{prefix}", "{ns}->{package}"})
    public static class PackageMapConfig {

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
     * Package-scoped XML namespace prefix rules (optional Java package regex filter).
     */
    public static class XmlNsRuleConfig {

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
