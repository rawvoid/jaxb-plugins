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
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.PackageOutline;
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JAXB plugin that manages XML namespace prefixes in @XmlSchema annotations.
 * <p>
 * This plugin allows users to define mappings between XML namespaces and their prefixes,
 * and modifies the generated @XmlSchema annotations in package-info.java files to include
 * the specified xmlns attributes. Configuration can be applied to specific packages using regex patterns,
 * or to all packages when no package filter is specified.
 * </p>
 * <p>
 * Usage examples:
 * </p>
 * <pre>
 * {@code
 * // Basic usage - apply to all packages
 * -Xns-prefix -config -xmlns -ns=http://example.com -prefix=ex
 *
 * // Package-specific configuration
 * -Xns-prefix -config -package=com\.example\.* -xmlns -ns=http://example.com -prefix=ex
 *
 * // Multiple namespaces for a package (one -xmlns; repeated -ns starts the next item)
 * -Xns-prefix -config -package=com\.example\.* -xmlns -ns=http://example.com -prefix=ex -ns=http://test.com -prefix=tst
 *
 * // Multiple package configurations (one -config; repeated -package starts the next item)
 * -Xns-prefix -config -package=com\.example\.* -xmlns -ns=http://example.com -prefix=ex \
 *                  -package=com\.test\.* -xmlns -ns=http://test.com -prefix=tst
 * }
 * </pre>
 *
 * @author Rawvoid
 */
@Option(name = "Xns-prefix", description = "Manage XML namespace prefixes in @XmlSchema annotations")
public class NsPrefixPlugin extends AbstractPlugin {

    @Option(name = "config", description = "XML namespace to prefix mapping rule")
    List<PackageXmlNsConfig> configs;

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        if (configs != null && !configs.isEmpty()) {
            outline.getAllPackageContexts().forEach(this::processPackage);
        }
        return true;
    }

    private void processPackage(PackageOutline packageOutline) {
        var jPackage = packageOutline._package();
        var packageName = jPackage.name();
        var matchedConfigs = configs.stream()
            .filter(config -> config.packageRegex == null || config.packageRegex.matcher(packageName).matches())
            .toList();

        if (matchedConfigs.isEmpty()) {
            return;
        }

        var xmlSchema = jPackage.annotations().stream()
            .filter(a -> a.getAnnotationClass().fullName().equals(XmlSchema.class.getName()))
            .findFirst()
            .orElse(null);

        if (xmlSchema == null) {
            return;
        }

        var xmlns = (JAnnotationArrayMember) xmlSchema.getAnnotationMembers().get("xmlns");
        if (xmlns == null) {
            xmlns = xmlSchema.paramArray("xmlns");
        }

        for (var matchedConfig : matchedConfigs) {
            for (var xmlNsConfig : matchedConfig.xmlNsConfigs) {
                updateXmlnsEntry(xmlns, xmlNsConfig);
            }
        }
    }

    private void updateXmlnsEntry(JAnnotationArrayMember xmlns, XmlNsConfig config) {
        var existingEntry = findExistingXmlnsEntry(xmlns, config.namespace);

        if (existingEntry.isPresent()) {
            existingEntry.get().param("prefix", config.prefix);
        } else {
            var newEntry = xmlns.annotate(XmlNs.class);
            newEntry.param("prefix", config.prefix);
            newEntry.param("namespaceURI", config.namespace);
        }
    }

    private Optional<JAnnotationUse> findExistingXmlnsEntry(JAnnotationArrayMember xmlns, String targetNamespace) {
        return xmlns.annotations().stream()
            .filter(anno -> {
                var namespaceURIValue = (JAnnotationStringValue) anno.getAnnotationMembers().get("namespaceURI");
                return namespaceURIValue != null && Objects.equals(namespaceURIValue.toString(), targetNamespace);
            })
            .findFirst();
    }

    /**
     * Configuration class for package-level XML namespace to prefix mappings.
     * <p>
     * This class defines namespace prefix rules that can be applied to specific packages
     * using regex patterns, or to all packages when no pattern is specified.
     * </p>
     */
    public static class PackageXmlNsConfig {

        @Option(name = "package", description = "Java package name regex pattern")
        Pattern packageRegex;

        @Option(name = "xmlns", description = "XML namespace to prefix mappings")
        List<XmlNsConfig> xmlNsConfigs;

    }

    /**
     * Configuration class for XML namespace to prefix mappings.
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
