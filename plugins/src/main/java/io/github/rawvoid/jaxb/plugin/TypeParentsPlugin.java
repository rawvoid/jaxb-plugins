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

import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JMod;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * XJC plugin for dynamically injecting implements interfaces and extends superclasses into generated JAXB classes.
 *
 * @author Rawvoid
 */
@Option(name = "Xtype-parents", description = "Inject interfaces (implements) and superclasses (extends) into generated JAXB classes")
public class TypeParentsPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(TypeParentsPlugin.class);

    private static final String OBJECT_FQCN = "java.lang.Object";

    @Option(name = "interface", description = "Interface mapping rules (compact format: pattern->Interface FQCN)")
    List<TypeParentConfig> interfaces;

    @Option(name = "super-class", description = "Superclass mapping rules (compact format: pattern->SuperClass FQCN)")
    List<TypeParentConfig> superClasses;

    @Option(name = "serializable", defaultValue = "false",
        description = "Add implements java.io.Serializable and automatically generate random serialVersionUID")
    Boolean serializable;

    @Option(name = "class-name", description = "Global regex filter for matching fully-qualified class names (repeatable)")
    List<Pattern> classNames;
    
    @Compact(formats = {"/{name}/->{to}", "{name}->{to}"})
    public static class TypeParentConfig {

        @Option(name = "name", required = true, description = "Regex pattern matching fully-qualified class names")
        Pattern name;

        @Option(name = "to", required = true, description = "Target interface or superclass FQCN")
        String to;
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var codeModel = outline.getCodeModel();
        var isSerializable = Boolean.TRUE.equals(serializable);

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            if (!matchesClassName(implClass.fullName())) {
                continue;
            }

            // 1. Serializable shortcut
            if (isSerializable) {
                if (!alreadyImplements(implClass, Serializable.class.getName())) {
                    implClass._implements(codeModel.ref(Serializable.class));
                }
                if (!implClass.fields().containsKey("serialVersionUID")) {
                    var randomUid = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                    implClass.field(
                        JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
                        codeModel.LONG,
                        "serialVersionUID",
                        JExpr.lit(randomUid)
                    );
                }
            }

            // 2. Interfaces
            if (interfaces != null) {
                for (var config : interfaces) {
                    if (config.name != null && config.to != null && config.name.matcher(implClass.fullName()).matches()) {
                        var interfaceFqcn = config.to.trim();
                        if (!interfaceFqcn.isEmpty() && !alreadyImplements(implClass, interfaceFqcn)) {
                            implClass._implements(codeModel.ref(interfaceFqcn));
                        }
                    }
                }
            }

            // 3. Superclass
            if (superClasses != null) {
                for (var config : superClasses) {
                    if (config.name != null && config.to != null && config.name.matcher(implClass.fullName()).matches()) {
                        var superClassFqcn = config.to.trim();
                        if (!superClassFqcn.isEmpty()) {
                            var currentSuper = implClass._extends();
                            if (currentSuper == null || OBJECT_FQCN.equals(currentSuper.fullName())) {
                                implClass._extends(codeModel.ref(superClassFqcn));
                            } else {
                                log.info("Preserving XSD inheritance for '{}': already extends '{}'",
                                    implClass.fullName(), currentSuper.fullName());
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean alreadyImplements(JDefinedClass implClass, String interfaceFqcn) {
        var it = implClass._implements();
        while (it.hasNext()) {
            if (interfaceFqcn.equals(it.next().fullName())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesClassName(String className) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        return classNames.stream().anyMatch(p -> p.matcher(className).matches());
    }
}
