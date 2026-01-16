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

import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JAXB plugin that simplifies element wrappers in the generated code.
 * <p>
 * This plugin identifies wrapper classes (classes with a single collection property)
 * and flattens them by moving the {@link jakarta.xml.bind.annotation.XmlElementWrapper}
 * and {@link jakarta.xml.bind.annotation.XmlElement} annotations to the field
 * that uses the wrapper class, then optionally removing the wrapper class itself.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xelement-wrapper")
public class ElementWrapperPlugin extends AbstractPlugin {

    @Option(name = "remove-wrapper-class", defaultValue = "true", description = "Whether to remove the wrapper class")
    Boolean removeWrapperClass;

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        var wrapperClasses = findWrapperClasses(outline);

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            var fields = implClass.fields();

            for (var jFieldVar : fields.values()) {
                var type = jFieldVar.type();
                var typeName = type.fullName();

                var wrapperClass = wrapperClasses.get(typeName);
                if (wrapperClass == null) continue;

                var typeClass = (JDefinedClass) type;
                var innerField = typeClass.fields().values().iterator().next();
                var wrapperName = getXmlElementName(jFieldVar);
                var elementName = getXmlElementName(innerField);

                var xmlElementAnno = getAnnotation(jFieldVar, XmlElement.class);
                if (xmlElementAnno == null) {
                    xmlElementAnno = jFieldVar.annotate(XmlElement.class);
                }
                xmlElementAnno.param("name", elementName);
                var xmlElementWrapper = jFieldVar.annotate(XmlElementWrapper.class);
                xmlElementWrapper.param("name", wrapperName);

                var newType = innerField.type();
                jFieldVar.type(newType);

                // Update getter and setter methods
                var fieldName = jFieldVar.name();
                var capName = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                var getterName = "get" + capName;
                var setterName = "set" + capName;

                for (var method : implClass.methods()) {
                    if (method.name().equals(getterName) && method.params().isEmpty()) {
                        method.type(newType);
                    } else if (method.name().equals(setterName) && method.params().size() == 1) {
                        method.params().getFirst().type(newType);
                    }
                }

                if (Boolean.TRUE.equals(removeWrapperClass)) {
                    removeWrapperClass(typeClass);
                }
            }
        }

        if (opt.debugMode) {
            fixJAXBDebugClass(outline);
        }

        return true;
    }

    /**
     * Removes the wrapper class from the parent container and ObjectFactory.
     *
     * @param wrapperClass The wrapper class to remove.
     */
    public void removeWrapperClass(JDefinedClass wrapperClass) {
        if (wrapperClass.classes().hasNext()) {
            return;
        }
        // Remove the wrapper class from the parent container
        var parentContainer = wrapperClass.parentContainer();
        var classes = switch (parentContainer) {
            case JDefinedClass clazz -> clazz.classes();
            case JPackage jPackage -> jPackage.classes();
            default -> throw new IllegalArgumentException("Unknown parent container type: " + parentContainer);
        };
        while (classes.hasNext() && wrapperClass.equals(classes.next())) {
            classes.remove();
        }

        // Remove the wrapper class from the ObjectFactory
        var jPackage = wrapperClass._package();
        var objectFactoryClass = jPackage._getClass("ObjectFactory");
        objectFactoryClass.methods().removeIf(method -> method.type().equals(wrapperClass));
    }

    /**
     * Finds wrapper classes in the JAXB outline.
     *
     * @param outline The JAXB outline to search.
     * @return A map of wrapper class names to their {@link CClassInfo} instances.
     */
    public Map<String, CClassInfo> findWrapperClasses(Outline outline) {
        var model = outline.getModel();
        return model.beans().values().stream()
            .filter(bean -> {
                var properties = bean.getProperties();
                if (properties == null || properties.size() != 1) return false;
                var propertyInfo = properties.getFirst();
                return propertyInfo.isCollection() && propertyInfo.ref().size() == 1;
            })
            .collect(Collectors.toMap(CClassInfo::getName, Function.identity()));
    }

    /**
     * Fixes the JAXBDebug class in the JAXB outline.
     *
     * @param outline The JAXB outline to fix.
     */
    public void fixJAXBDebugClass(Outline outline) {
        var model = outline.getModel();
        var codeModel = outline.getCodeModel();
        var rootPackage = codeModel.rootPackage();
        var jaxbDebugClass = rootPackage._getClass("JAXBDebug");
        if (jaxbDebugClass == null) return;

        jaxbDebugClass.methods().removeIf(method -> method.name().equals("createContext"));

        var createContextMethod = jaxbDebugClass.method(JMod.PUBLIC | JMod.STATIC, JAXBContext.class, "createContext");
        var $classLoader = createContextMethod.param(ClassLoader.class, "classLoader");
        createContextMethod._throws(JAXBException.class);
        var invoke = codeModel.ref(JAXBContext.class).staticInvoke("newInstance");
        createContextMethod.body()._return(invoke);

        var packageContexts = outline.getAllPackageContexts();
        switch (model.strategy) {
            case INTF_AND_IMPL -> {
                var joiner = new StringJoiner(":");
                for (var packageOutline : packageContexts) {
                    joiner.add(packageOutline._package().name());
                }
                invoke.arg(joiner.toString()).arg($classLoader);
            }
            case BEAN_ONLY -> {
                for (var packageContext : packageContexts) {
                    var jPackage = packageContext._package();
                    jPackage.classes().forEachRemaining(classInfo -> {
                        invoke.arg(JExpr.dotclass(classInfo));
                    });
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    /**
     * Gets the XML element name for a field.
     *
     * @param field The field to get the XML element name for.
     * @return The XML element name.
     */
    public String getXmlElementName(JFieldVar field) {
        var xmlElementAnno = getAnnotation(field, XmlElement.class);
        if (xmlElementAnno == null) {
            xmlElementAnno = getAnnotation(field, XmlRootElement.class);
        }
        if (xmlElementAnno == null) {
            return field.name();
        }
        var name = xmlElementAnno.getAnnotationMembers().get("name");
        if (name == null) {
            return field.name();
        }
        return name.toString();
    }

    /**
     * Gets the annotation of the specified type for the given annotatable element.
     *
     * @param annotatable The annotatable element to get the annotation for.
     * @param clazz       The class of the annotation to get.
     * @param <T>         The type of the annotation.
     * @return The annotation of the specified type, or null if not found.
     */
    public <T extends Annotation> JAnnotationUse getAnnotation(JAnnotatable annotatable, Class<T> clazz) {
        return annotatable.annotations().stream()
            .filter(anno -> anno.getAnnotationClass().fullName().equals(clazz.getName()))
            .findFirst()
            .orElse(null);
    }
}
