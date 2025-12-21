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

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
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
            injectBindings(opt);
        }
        return x;
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
        return true;
    }

    public static class NamespaceMappingConfig {

        @Option(name = "ns", required = true, description = "XML target namespace URI (e.g., http://example.com/my-schema)")
        String namespace;

        @Option(name = "package", required = true, description = "Target Java package name for this namespace (e.g., com.example.myschema)")
        String packageName;
    }

}
