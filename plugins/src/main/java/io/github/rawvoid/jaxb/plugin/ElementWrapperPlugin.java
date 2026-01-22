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
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.*;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapters;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.lang.annotation.Annotation;
import java.util.*;
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
        var elementWrapperClasses = findElementWrapperClasses(outline);
        var allClasses = outline.getClasses().stream()
            .map(i -> i.implClass)
            .toList();
        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            var fields = implClass.fields();

            fields.values().stream().filter(field -> {
                if (!elementWrapperClasses.containsKey(field.type().fullName())) return false;

                // Skip if the field has a @XmlJavaTypeAdapter or @XmlJavaTypeAdapters annotation
                var hasTypeAdapter = getAnnotation(field, XmlJavaTypeAdapter.class) != null
                    || getAnnotation(field, XmlJavaTypeAdapters.class) != null;
                if (hasTypeAdapter) return false;

                return getAnnotation(field, XmlElementWrapper.class) == null;
            }).forEach(field -> handleWrapperField(field, implClass));
        }

        if (Boolean.TRUE.equals(removeWrapperClass)) {
            var removedClasses = new ArrayList<JDefinedClass>();
            var notRemovedClasses = new ArrayList<JDefinedClass>();
            elementWrapperClasses.values().stream().map(ClassOutline::getImplClass).forEach(wrapperClass -> {
                var removed = removeWrapperClass(wrapperClass, allClasses);
                if (removed) {
                    removedClasses.add(wrapperClass);
                } else {
                    notRemovedClasses.add(wrapperClass);
                }
            });

        }

        if (opt.debugMode) {
            fixJAXBDebugClass(outline);
        }

        return true;
    }

    private void handleWrapperField(JFieldVar outerField, JDefinedClass outerClass) {
        var type = outerField.type();

        var innerClass = (JDefinedClass) type;
        var innerField = innerClass.fields().values().iterator().next();

        var outerXmlElement = getAnnotation(outerField, XmlElement.class);
        // If @XmlElement specifies a type attribute, it cannot be converted to / wrapped with @XmlElementWrapper
        if (outerXmlElement != null && outerXmlElement.getAnnotationMembers().get("type") != null) return;

        migrateAnnotation(outerField, innerField, outerClass, innerClass);
        addXmlElementWrapper(outerField, outerXmlElement);

        var newType = innerField.type();
        outerField.type(newType);

        // Update getter and setter methods
        var fieldName = outerField.name();
        var capName = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        var getterName = "get" + capName;
        var setterName = "set" + capName;

        for (var method : outerClass.methods()) {
            if (method.name().equals(getterName) && method.params().isEmpty()) {
                method.type(newType);
            } else if (method.name().equals(setterName) && method.params().size() == 1) {
                method.params().getFirst().type(newType);
            }
        }
    }

    private void addXmlElementWrapper(JFieldVar outerField, JAnnotationUse outerXmlElement) {
        var xmlElementWrapper = outerField.annotate(XmlElementWrapper.class);
        if (outerXmlElement == null) return;

        outerField.removeAnnotation(outerXmlElement);
        outerXmlElement.getAnnotationMembers().forEach((key, value) -> {
            switch (key) {
                case "name" -> xmlElementWrapper.param("name", value);
                case "nillable" -> xmlElementWrapper.param("nillable", value);
                case "required" -> xmlElementWrapper.param("required", value);
                case "namespace" -> xmlElementWrapper.param("namespace", value);
                default -> {
                    // Ignore other members
                }
            }
        });
    }

    private void migrateAnnotation(JFieldVar outerField, JFieldVar innerField, JDefinedClass outerClass, JDefinedClass innerClass) {
        JAnnotationUse targetAnnotation;
        if ((targetAnnotation = getAnnotation(innerField, XmlElement.class)) != null
            || (targetAnnotation = getAnnotation(innerField, XmlElementRef.class)) != null) {
            var newAnnotation = outerField.annotate(targetAnnotation.getAnnotationClass());
            copyAnnotationMembers(targetAnnotation, newAnnotation);

            setXmlElementName(newAnnotation, outerField, innerField);
        } else if ((targetAnnotation = getAnnotation(innerField, XmlElements.class)) != null
            || (targetAnnotation = getAnnotation(innerField, XmlElementRefs.class)) != null) {
            var newArrayAnnotation = outerField.annotate(targetAnnotation.getAnnotationClass());
            copyAnnotationMembers(targetAnnotation, newArrayAnnotation);

            var value = (JAnnotationArrayMember) targetAnnotation.getAnnotationMembers().get("value");

            // override the value array
            var newValues = newArrayAnnotation.paramArray("value");
            value.annotations().forEach(anno -> {
                var newAnno = newValues.annotate(anno.getAnnotationClass());
                copyAnnotationMembers(anno, newAnno);

                setXmlElementName(newAnno, outerField, innerField);
            });
        } else if (!outerField.name().equals(innerField.name())) {
            var newXmlElement = outerField.annotate(XmlElement.class);

            setXmlElementName(newXmlElement, outerField, innerField);
        }

        var targetAnnotationName = targetAnnotation == null ? null : targetAnnotation.getAnnotationClass().fullName();
        if (Objects.equals(targetAnnotationName, XmlElementRef.class.getName()) || Objects.equals(targetAnnotationName, XmlElementRefs.class.getName())) {
            mergeSeeAlsoClass(outerClass, innerClass);
        }

        copyAnnotation(outerField, innerField, XmlJavaTypeAdapter.class);
        copyAnnotation(outerField, innerField, XmlJavaTypeAdapters.class);
    }

    private void copyAnnotation(JFieldVar outerField, JFieldVar innerField, Class<? extends Annotation> annotationClass) {
        var targetAnnotation = getAnnotation(innerField, annotationClass);
        if (targetAnnotation == null) return;

        var newAnnotation = outerField.annotate(targetAnnotation.getAnnotationClass());
        copyAnnotationMembers(targetAnnotation, newAnnotation);
    }

    private void mergeSeeAlsoClass(JDefinedClass outerClass, JDefinedClass innerClass) {
        var innerClassSeeAlso = getAnnotation(innerClass, XmlSeeAlso.class);
        if (innerClassSeeAlso == null) return;

        var innerSeeAlsoTypes = (JAnnotationArrayMember) innerClassSeeAlso.getAnnotationMembers().get("value");

        var innerSeeAlsoTypesList = innerSeeAlsoTypes.annotations2().stream()
            .map(i -> (JAnnotationClassValue) i)
            .map(i -> i.type().fullName())
            .toList();
        var outerClassSeeAlso = getAnnotation(outerClass, XmlSeeAlso.class);
        if (outerClassSeeAlso == null) {
            outerClassSeeAlso = outerClass.annotate(XmlSeeAlso.class);
            outerClassSeeAlso.paramArray("value");
        }
        var outerSeeAlsoTypes = (JAnnotationArrayMember) outerClassSeeAlso.getAnnotationMembers().get("value");
        var outerSeeAlsoTypesList = outerSeeAlsoTypes.annotations2().stream()
            .map(i -> (JAnnotationClassValue) i)
            .map(i -> i.type().fullName())
            .toList();

        for (var className : innerSeeAlsoTypesList) {
            if (!outerSeeAlsoTypesList.contains(className)) {
                outerSeeAlsoTypes.param(outerClass.owner().ref(className));
            }
        }
    }

    private void setXmlElementName(JAnnotationUse targetAnnotation, JFieldVar outerField, JFieldVar innerField) {
        var name = getAnnotationNameValue(targetAnnotation);
        if (name == null && !outerField.name().equals(innerField.name())) {
            targetAnnotation.param("name", innerField.name());
        }
    }

    private void copyAnnotationMembers(JAnnotationUse source, JAnnotationUse target) {
        for (var member : source.getAnnotationMembers().entrySet()) {
            target.param(member.getKey(), member.getValue());
        }
    }

    private String getAnnotationNameValue(JAnnotationUse annotation) {
        if (annotation == null) {
            return null;
        }
        var value = annotation.getAnnotationMembers().get("name");
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private boolean removeWrapperClass(JDefinedClass wrapperClass, List<JDefinedClass> allClasses) {
        var wrapperClassName = wrapperClass.fullName();
        var existsReference = allClasses.stream().anyMatch(jDefinedClass -> {
            if (jDefinedClass.fullName().equals(wrapperClassName)) {
                return false;
            }
            var superClass = jDefinedClass.superClass();
            if (superClass != null && superClass.fullName().equals(wrapperClassName)) {
                return true;
            }

            var hasReference = jDefinedClass.fields().values().stream().anyMatch(field -> {
                var typeName = field.type().fullName();
                return typeName.contains(wrapperClassName);
            });

            if (hasReference) {
                return true;
            }

            hasReference = jDefinedClass.methods().stream().anyMatch(method -> {
                var returnTypeName = method.type().fullName();
                if (returnTypeName.contains(wrapperClassName)) {
                    return true;
                }

                var paramTypeNames = method.params().stream()
                    .map(JVar::type)
                    .map(JType::fullName)
                    .toList();

                return paramTypeNames.stream().anyMatch(typeName -> typeName.contains(wrapperClassName));
            });

            return hasReference;
        });
        if (!existsReference) {
            removeWrapperClass(wrapperClass);
        }

        return !existsReference;
    }

    /**
     * Removes the wrapper class from the parent container and ObjectFactory.
     *
     * @param wrapperClass The wrapper class to remove.
     */
    private void removeWrapperClass(JDefinedClass wrapperClass) {
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
     * <p>
     * A wrapper class must declare exactly one collection property and must not
     * inherit any additional properties from its base classes.
     * </p>
     *
     * @param outline The JAXB outline to search.
     * @return A map of wrapper class names to their {@link CClassInfo} instances.
     */
    public Map<String, ClassOutline> findElementWrapperClasses(Outline outline) {
        return outline.getClasses().stream()
            .filter(this::checkClassStructure)
            .filter(this::checkFieldAnnotation)
            .collect(Collectors.toMap(i -> i.implClass.fullName(), Function.identity()));
    }

    /**
     * 1. The class must declare exactly one collection property.
     * 2. The class must not inherit any additional properties from its base classes.
     *
     * @param classOutline The class to check.
     * @return {@code true} if the class is a wrapper class, {@code false} otherwise.
     */
    private boolean checkClassStructure(ClassOutline classOutline) {
        var classInfo = classOutline.target;
        var properties = classInfo.getProperties();
        if (properties == null || properties.size() != 1) return false;
        var baseClass = classInfo.getBaseClass();
        while (baseClass != null) {
            var baseProperties = baseClass.getProperties();
            if (baseProperties != null && !baseProperties.isEmpty()) return false;
            baseClass = baseClass.getBaseClass();
        }
        var propertyInfo = properties.getFirst();
        if (!(propertyInfo.isCollection() && propertyInfo.ref().size() == 1)) return false;

        return classOutline.implClass.fields().size() == 1;
    }

    private boolean checkFieldAnnotation(ClassOutline classOutline) {
        var implClass = classOutline.implClass;
        var fields = implClass.fields();
        return fields.values().stream().noneMatch(this::hasConflictingAnnotation);
    }

    private boolean isConflictingJaxbAnnotation(String className) {
        return className.equals(XmlElementWrapper.class.getName())
            || className.equals(XmlAttribute.class.getName())
            || className.equals(XmlAnyAttribute.class.getName())
            || className.equals(XmlValue.class.getName())
            || className.equals(XmlList.class.getName())
            || className.equals(XmlTransient.class.getName());
    }

    private boolean hasConflictingAnnotation(JFieldVar field) {
        var classNamePrefix = XmlElementWrapper.class.getPackageName() + ".";
        return field.annotations().stream().anyMatch(anno -> {
            var className = anno.getAnnotationClass().fullName();
            if (!className.startsWith(classNamePrefix)) {
                return false;
            }
            return isConflictingJaxbAnnotation(className);
        });
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
        var classLoader = createContextMethod.param(ClassLoader.class, "classLoader");
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
                invoke.arg(joiner.toString()).arg(classLoader);
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
