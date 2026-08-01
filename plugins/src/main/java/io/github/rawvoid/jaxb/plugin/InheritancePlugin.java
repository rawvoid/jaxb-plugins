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

import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JMod;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.AbstractPlugin;
import io.github.rawvoid.jaxb.plugin.option.Compact;
import io.github.rawvoid.jaxb.plugin.option.Option;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Injects interface implementations ({@code implements}) and superclasses ({@code extends})
 * into generated JAXB classes (Java supertypes / inheritance edges).
 *
 * <p>Application order per class: {@code -serializable} (optional UID), then {@code -interface}
 * rules in declaration order (cumulative), then {@code -super-class} rules in declaration order
 * (first-wins). Target classes are selected by each rule's left-hand pattern.</p>
 *
 * <p>Compact CLI examples:</p>
 * <pre>{@code
 * -Xinheritance -serializable=true
 * -Xinheritance -serializable=true -serial-version-uid=42
 * -Xinheritance -interface=.*Request->com.example.BaseRequest
 * -Xinheritance -super-class=.*Dto->com.example.AbstractDto
 * }</pre>
 *
 * <p><b>Intentional limits:</b></p>
 * <ul>
 *   <li>Bean classes only ({@code outline.getClasses()}); enums are not modified.</li>
 *   <li>Does not replace an existing non-{@code Object} superclass (XSD inheritance or a prior
 *       matching {@code -super-class} rule). Multiple matching super-class rules are first-wins;
 *       skips are reported as XJC warnings.</li>
 *   <li>{@code -serializable} adds {@link Serializable} and {@code serialVersionUID} (default
 *       {@code 1L}, overridable via {@code -serial-version-uid}) when the field is absent.</li>
 *   <li>Interface injection is cumulative and skips duplicates already present on the class.</li>
 *   <li>Does not validate that {@code -interface}/{@code -super-class} targets are interfaces/classes
 *       or resolvable at generation time; invalid FQCNs fail later at Java compile.</li>
 *   <li>Target types must be on the consumer compile classpath.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xinheritance", description = "Inject interfaces (implements) and superclasses (extends) into generated JAXB classes")
public class InheritancePlugin extends AbstractPlugin {

    private static final String OBJECT_FQCN = "java.lang.Object";

    @Option(name = "interface", description = "Interface mapping rules (compact format: pattern->Interface FQCN)")
    List<InheritanceConfig> interfaces;

    @Option(name = "super-class", description = "Superclass mapping rules (compact format: pattern->SuperClass FQCN)")
    List<InheritanceConfig> superClasses;

    @Option(name = "serializable", defaultValue = "false",
        description = "Add implements java.io.Serializable and serialVersionUID when missing")
    Boolean serializable;

    @Option(name = "serial-version-uid", defaultValue = "1",
        description = "serialVersionUID value used when -serializable is true (default: 1)")
    Long serialVersionUid;

    @Compact(formats = {"/{name}/->{to}", "{name}->{to}"})
    public static class InheritanceConfig {

        @Option(name = "name", required = true, description = "Regex pattern matching fully-qualified class names")
        Pattern name;

        @Option(name = "to", required = true, description = "Target interface or superclass FQCN")
        String to;
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var codeModel = outline.getCodeModel();
        var isSerializable = Boolean.TRUE.equals(serializable);
        var uid = serialVersionUid != null ? serialVersionUid : 1L;

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;

            if (isSerializable) {
                if (lacksInterface(implClass, Serializable.class.getName())) {
                    implClass._implements(codeModel.ref(Serializable.class));
                }
                if (!implClass.fields().containsKey("serialVersionUID")) {
                    implClass.field(
                        JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
                        codeModel.LONG,
                        "serialVersionUID",
                        JExpr.lit(uid)
                    );
                }
            }

            if (interfaces != null) {
                for (var config : interfaces) {
                    if (config.name != null && config.to != null && config.name.matcher(implClass.fullName()).matches()) {
                        var interfaceFqcn = config.to.trim();
                        if (!interfaceFqcn.isEmpty() && lacksInterface(implClass, interfaceFqcn)) {
                            implClass._implements(codeModel.ref(interfaceFqcn));
                        }
                    }
                }
            }

            if (superClasses != null) {
                applySuperClasses(classOutline, implClass, codeModel, errorHandler);
            }
        }
        return true;
    }

    private void applySuperClasses(
        ClassOutline classOutline,
        JDefinedClass implClass,
        JCodeModel codeModel,
        ErrorHandler errorHandler
    ) throws SAXException {
        for (var config : superClasses) {
            if (config.name == null || config.to == null || !config.name.matcher(implClass.fullName()).matches()) {
                continue;
            }
            var superClassFqcn = config.to.trim();
            if (superClassFqcn.isEmpty()) {
                continue;
            }
            var currentSuper = implClass._extends();
            if (currentSuper == null || OBJECT_FQCN.equals(currentSuper.fullName())) {
                implClass._extends(codeModel.ref(superClassFqcn));
            } else {
                warn(errorHandler, locatorOf(classOutline),
                    "Skipping super-class '%s' for '%s': already extends '%s'"
                        .formatted(superClassFqcn, implClass.fullName(), currentSuper.fullName()));
            }
        }
    }

    private static Locator locatorOf(ClassOutline classOutline) {
        return classOutline.target != null ? classOutline.target.getLocator() : null;
    }

    private void warn(ErrorHandler errorHandler, Locator locator, String message) throws SAXException {
        errorHandler.warning(new SAXParseException(message, locator));
    }

    private boolean lacksInterface(JDefinedClass implClass, String interfaceFqcn) {
        var it = implClass._implements();
        while (it.hasNext()) {
            if (interfaceFqcn.equals(it.next().fullName())) {
                return false;
            }
        }
        return true;
    }
}
