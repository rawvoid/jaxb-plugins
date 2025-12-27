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
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.lang.annotation.Annotation;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Rawvoid
 */
@Option(name = "Xelement-wrapper")
public class ElementWrapperPlugin extends AbstractPlugin {

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        var model = outline.getModel();
        var wrapperClassInfoMap = model.beans().values().stream()
            .filter(bean -> {
                var properties = bean.getProperties();
                if (properties.isEmpty()) return false;
                var firstProperty = properties.getFirst();
                return properties.size() == 1 && firstProperty.isCollection() && firstProperty.ref().size() == 1;
            })
            .collect(Collectors.toMap(CClassInfo::getName, Function.identity()));

        outline.getClasses().forEach(classOutline -> {
            var implClass = classOutline.implClass;
            var fields = implClass.fields();
            fields.values().forEach(field -> {
                var type = field.type();
                var typeName = type.fullName();

                if (wrapperClassInfoMap.containsKey(typeName)) {
                    if (type instanceof JDefinedClass typeClass) {
                        var innerField = typeClass.fields().values().iterator().next();
                        var wrapperName = getXmlElementName(field);
                        var elementName = getXmlElementName(innerField);

                        var xmlElementAnno = getAnnotation(field, XmlElement.class);
                        if (xmlElementAnno == null) {
                            xmlElementAnno = field.annotate(XmlElement.class);
                        }
                        xmlElementAnno.param("name", elementName);
                        var xmlElementWrapper = field.annotate(XmlElementWrapper.class);
                        xmlElementWrapper.param("name", wrapperName);

                        var newType = innerField.type();
                        field.type(newType);

                        // Update getter and setter
                        var fieldName = field.name();
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

                        // Remove the original class from the parent container
                        var parentContainer = typeClass.parentContainer();
                        if (parentContainer instanceof JDefinedClass clazz) {
                            var iterator = clazz.classes();
                            while (iterator.hasNext() && typeClass.equals(iterator.next())) {
                                iterator.remove();
                            }
                        } else if (parentContainer instanceof JPackage pkg) {
                            var iterator = pkg.classes();
                            while (iterator.hasNext() && typeClass.equals(iterator.next())) {
                                iterator.remove();
                            }
                        }

                        // Remove the original class from the ObjectFactory
                        var pkg = typeClass._package();
                        var objectFactoryClass = pkg._getClass("ObjectFactory");
                        objectFactoryClass.methods().removeIf(method -> method.type().equals(typeClass));
                    }
                }
            });
        });

        return true;
    }

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

    public JAnnotationUse getAnnotation(JAnnotatable annotatable, Class<? extends Annotation> clazz) {
        return annotatable.annotations().stream()
            .filter(anno -> anno.getAnnotationClass().fullName().equals(clazz.getName()))
            .findFirst()
            .orElse(null);
    }
}
