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
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlSchema;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Rawvoid
 */
@Option(name = "Xnamespace", description = "Customize Java package names for XML namespaces")
public class NamespacePlugin extends AbstractPlugin {

    @Option(name = "mapping", description = "Namespace to package mapping rule")
    List<NamespaceMappingConfig> mappings;

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        var x = super.parseArgument(opt, args, i);
        if (x > 0) {
            // 1. inject bindings
            // injectBindings(opt);

            // 2. use NameConverter
            var nameConverter = createNameConverter();
            opt.setNameConverter(nameConverter, this);
        }
        return x;
    }

    public NameConverter createNameConverter() throws BadCommandLineException {
        var namespaceToPackage = mappings.stream()
            .filter(m -> m.namespace != null && m.packageName != null
                && !m.namespace.isBlank() && !m.packageName.isBlank())
            .collect(Collectors.toMap(m -> m.namespace, m -> m.packageName));
        return new NameConverter.Standard() {
            @Override
            public String toPackageName(String namespaceUri) {
                return namespaceToPackage.getOrDefault(namespaceUri, super.toPackageName(namespaceUri));
            }
        };
    }

    public void injectBindings(Options options) {
        var schemaLocations = collectSchemaLocation(options);
        var bindings = generateBindings(schemaLocations);
        if (bindings == null || bindings.isBlank()) return;

        var inputSource = new InputSource(new StringReader(bindings));
        inputSource.setSystemId("//" + inputSource.hashCode());
        options.addBindFile(inputSource);
    }

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

    public Map<String, String> collectSchemaLocation(Options options) {
        var grammars = options.getGrammars();
        return Arrays.stream(grammars)
            .filter(i -> i.getSystemId() != null)
            .collect(Collectors.toMap(InputSource::getSystemId, this::resolveTargetNamespace));
    }

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

    public static class NamespaceMappingConfig {

        @Option(name = "ns", required = true, description = "XML target namespace URI (e.g., http://example.com/my-schema)")
        String namespace;

        @Option(name = "prefix", description = "XML target namespace prefix (e.g., myschema)")
        String prefix;

        @Option(name = "package", required = true, description = "Target Java package name for this namespace (e.g., com.example.myschema)")
        String packageName;
    }

}
