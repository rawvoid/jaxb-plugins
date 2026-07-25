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
 * Injects interface implementations ({@code implements}) and superclasses ({@code extends})
 * into generated JAXB classes.
 *
 * <p>Target classes are selected by each rule's left-hand pattern (no global class filter).</p>
 *
 * <p>Compact CLI examples:</p>
 * <pre>{@code
 * -Xtype-parent -serializable=true
 * -Xtype-parent -interface=.*Request->com.example.BaseRequest
 * -Xtype-parent -super-class=.*Dto->com.example.AbstractDto
 * }</pre>
 *
 * <p><b>Intentional limits:</b></p>
 * <ul>
 *   <li>Bean classes only ({@code outline.getClasses()}); enums are not modified.</li>
 *   <li>Does not replace an existing non-{@code Object} superclass (XSD inheritance or a prior
 *       matching {@code -super-class} rule). Multiple matching super-class rules are first-wins.</li>
 *   <li>{@code -serializable} adds {@link Serializable} and a fixed {@code serialVersionUID = 1L}
 *       when the field is absent; does not overwrite an existing field.</li>
 *   <li>Interface injection is cumulative and skips duplicates already present on the class.</li>
 *   <li>Does not validate that {@code -interface}/{@code -super-class} targets are interfaces/classes
 *       or resolvable at generation time; invalid FQCNs fail later at Java compile.</li>
 *   <li>Target types must be on the consumer compile classpath.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xtype-parent", description = "Inject interfaces (implements) and superclasses (extends) into generated JAXB classes")
public class TypeParentPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(TypeParentPlugin.class);

    private static final String OBJECT_FQCN = "java.lang.Object";
    private static final long DEFAULT_SERIAL_VERSION_UID = 1L;

    @Option(name = "interface", description = "Interface mapping rules (compact format: pattern->Interface FQCN)")
    List<TypeParentConfig> interfaces;

    @Option(name = "super-class", description = "Superclass mapping rules (compact format: pattern->SuperClass FQCN)")
    List<TypeParentConfig> superClasses;

    @Option(name = "serializable", defaultValue = "false",
        description = "Add implements java.io.Serializable and a fixed serialVersionUID = 1L when missing")
    Boolean serializable;

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

            if (isSerializable) {
                if (lacksInterface(implClass, Serializable.class.getName())) {
                    implClass._implements(codeModel.ref(Serializable.class));
                }
                if (!implClass.fields().containsKey("serialVersionUID")) {
                    implClass.field(
                        JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
                        codeModel.LONG,
                        "serialVersionUID",
                        JExpr.lit(DEFAULT_SERIAL_VERSION_UID)
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
                for (var config : superClasses) {
                    if (config.name != null && config.to != null && config.name.matcher(implClass.fullName()).matches()) {
                        var superClassFqcn = config.to.trim();
                        if (!superClassFqcn.isEmpty()) {
                            var currentSuper = implClass._extends();
                            if (currentSuper == null || OBJECT_FQCN.equals(currentSuper.fullName())) {
                                implClass._extends(codeModel.ref(superClassFqcn));
                            } else {
                                log.debug("Skipping super-class '{}' for '{}': already extends '{}'",
                                    superClassFqcn, implClass.fullName(), currentSuper.fullName());
                            }
                        }
                    }
                }
            }
        }
        return true;
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
