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

import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationStringValue;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JAXB plugin that customizes Java package names for XML namespaces.
 * <p>
 * This plugin allows users to define a mapping between XML namespaces and Java packages.
 * It modifies the generated JAXB classes to use the specified package names for the
 * corresponding XML namespaces.
 * </p>
 *
 * {@code @Deprecated} This plugin is deprecated and may be removed in future versions.
 *
 * @author Rawvoid
 */
@Deprecated
@Option(name = "Xnamespace", description = """
    Customize Java package names for XML namespaces.
    This plugin is deprecated and may be removed in future versions.
    """)
public class NamespacePlugin extends AbstractPlugin {

    @Option(name = "mapping", description = "Namespace to package mapping rule")
    List<NamespaceMappingConfig> mappings;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        injectBindings(opt);
    }

    /**
     * Injects custom bindings into the JAXB options.
     *
     * @param options The JAXB options to inject bindings into.
     */
    public void injectBindings(Options options) {
        var schemaLocations = collectSchemaLocation(options);
        var bindings = generateBindings(schemaLocations);
        if (bindings == null || bindings.isBlank()) return;

        var inputSource = new InputSource(new StringReader(bindings));
        inputSource.setSystemId("//" + inputSource.hashCode());
        options.addBindFile(inputSource);
    }

    /**
     * Generates custom bindings for the JAXB options.
     *
     * @param schemaLocations A map of schema locations to XML namespaces.
     * @return The generated bindings as a string, or {@code null} if no bindings are needed.
     */
    public String generateBindings(Map<String, String> schemaLocations) {
        var header = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jaxb:bindings
                xmlns:jaxb="https://jakarta.ee/xml/ns/jaxb"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                version="3.0">
            """;
        var bodyTemplate = """
            <jaxb:bindings schemaLocation="%s" node="/xs:schema">
                <jaxb:schemaBindings>
                  <jaxb:package name="%s"/>
                </jaxb:schemaBindings>
            </jaxb:bindings>
            """;
        var footer = "</jaxb:bindings>";

        var body = new StringBuilder();
        schemaLocations.forEach((schemaLocation, namespace) -> mappings.stream()
            .filter(m -> Objects.equals(m.namespace, namespace))
            .findFirst()
            .ifPresent(mapping -> {
                body.append(bodyTemplate.formatted(schemaLocation, mapping.packageName));
            }));
        if (body.isEmpty()) return null;
        return header + body + footer;
    }

    /**
     * Resolves the target namespace from the given XML schema input source.
     *
     * @param inputSource The XML schema input source.
     * @return The resolved target namespace, or {@code null} if not found.
     */
    public String resolveTargetNamespace(InputSource inputSource) {
        try {
            var doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(inputSource);
            return doc.getDocumentElement().getAttribute("targetNamespace");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collects the schema locations and their corresponding XML namespaces from the JAXB options.
     *
     * @param options The JAXB options to collect schema locations from.
     * @return A map of schema locations to XML namespaces.
     */
    public Map<String, String> collectSchemaLocation(Options options) {
        var grammars = options.getGrammars();
        return Arrays.stream(grammars)
            .filter(i -> i.getSystemId() != null)
            .collect(Collectors.toMap(InputSource::getSystemId, this::resolveTargetNamespace));
    }

    /**
     * Applies the custom namespace mappings to the JAXB outline.
     *
     * @param outline      The JAXB outline to apply mappings to.
     * @param opt          The JAXB options.
     * @param errorHandler The error handler.
     * @return {@code true} if the mappings were applied successfully, {@code false} otherwise.
     * @throws SAXException If a SAX error occurs.
     */
    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        outline.getAllPackageContexts().forEach(pkgOutline -> {
            var jPackage = pkgOutline._package();
            var xmlSchema = jPackage.annotations().stream()
                .filter(a -> a.getAnnotationClass().fullName().equals(XmlSchema.class.getName()))
                .findFirst()
                .orElse(null);
            if (xmlSchema == null) return;

            var namespaceValue = (JAnnotationStringValue) xmlSchema.getAnnotationMembers().get("namespace");
            if (namespaceValue == null) return;

            var namespace = namespaceValue.toString();
            var mapping = mappings.stream()
                .filter(m -> Objects.equals(m.namespace, namespace) && m.prefix != null)
                .findFirst()
                .orElse(null);
            if (mapping == null) return;

            var xmlns = (JAnnotationArrayMember) xmlSchema.getAnnotationMembers().get("xmlns");
            if (xmlns == null) {
                xmlns = xmlSchema.paramArray("xmlns");
                var anno = xmlns.annotate(XmlNs.class);
                anno.param("prefix", mapping.prefix);
                anno.param("namespaceURI", namespace);
            } else {
                xmlns.annotations().stream().filter(anno -> {
                    var namespaceURIValue = (JAnnotationStringValue) anno.getAnnotationMembers().get("namespaceURI");
                    return namespaceURIValue != null && Objects.equals(namespaceURIValue.toString(), namespace);
                }).forEach(anno -> anno.param("prefix", mapping.prefix));
            }
        });
        return true;
    }

    /**
     * Naming mapping rule configuration.
     */
    public static class NamespaceMappingConfig {

        @Option(name = "ns", required = true, description = "XML target namespace URI (e.g., http://example.com/my-schema)")
        String namespace;

        @Option(name = "prefix", description = "XML target namespace prefix (e.g., myschema)")
        String prefix;

        @Option(name = "package", required = true, description = "Target Java package name for this namespace (e.g., com.example.myschema)")
        String packageName;
    }

}
