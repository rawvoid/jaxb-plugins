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
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.*;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ElementWrapperPlugin.class);

    @Option(name = "remove-wrapper-class", defaultValue = "true", description = "Whether to remove the wrapper class")
    Boolean removeWrapperClass;

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        // Identify candidate wrapper classes once and reuse across the run.
        var elementWrapperClasses = findElementWrapperClasses(outline);
        var allClasses = outline.getClasses().stream()
            .map(i -> i.implClass)
            .collect(Collectors.toSet());
        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            var fields = implClass.fields();

            fields.values().stream().filter(field -> {
                // Only handle fields whose type is a recognized wrapper class.
                if (!elementWrapperClasses.containsKey(field.type().fullName())) return false;

                // Skip if the field has a @XmlJavaTypeAdapter or @XmlJavaTypeAdapters annotation
                // because the adapter expects the wrapper type and changing it would break behavior.
                var hasTypeAdapter = getAnnotation(field, XmlJavaTypeAdapter.class) != null
                    || getAnnotation(field, XmlJavaTypeAdapters.class) != null;
                if (hasTypeAdapter) return false;

                // Skip if wrapper annotation already exists to avoid duplicate annotations.
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

            var message = String.join("\n    ", removedClasses.stream()
                .map(JDefinedClass::fullName).toList());
            log.info("Removed wrapper classes: {}", message);

            message = String.join("\n    ", notRemovedClasses.stream()
                .map(JDefinedClass::fullName).toList());
            log.info("Skipped removing wrapper classes: {}", message);

        }

        if (opt.debugMode) {
            JaxbClassRefactorUtil.fixJAXBDebugClass(outline);
        }

        return true;
    }

    /**
     * Flattens a wrapper field by migrating element annotations and retyping the field.
     * <p>
     * The wrapper class is expected to contain exactly one collection field. This method
     * moves the element-related annotations from the inner field to the outer field,
     * adds the {@link XmlElementWrapper} annotation, and updates accessor signatures
     * to the inner collection type.
     * </p>
     */
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

    /**
     * Adds {@link XmlElementWrapper} to the outer field and reuses compatible members from
     * the existing {@link XmlElement} annotation.
     * <p>
     * Only a subset of members are compatible with {@link XmlElementWrapper}; other members
     * (e.g., type) are intentionally ignored.
     * </p>
     */
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

    /**
     * Migrates element annotations from the inner field to the outer field.
     * <p>
     * Rules:
     * <ul>
     *     <li>Prefer {@link XmlElement}/{@link XmlElementRef} when present.</li>
     *     <li>Otherwise copy array variants {@link XmlElements}/{@link XmlElementRefs}.</li>
     *     <li>If no relevant annotation exists and the names differ, synthesize {@link XmlElement}.</li>
     * </ul>
     * If an element-ref style is used, merge {@link XmlSeeAlso} information from the wrapper
     * class into the owning class to keep polymorphic bindings intact.
     * </p>
     */
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

    /**
     * Copies a single annotation (if present) from the inner field to the outer field.
     */
    private void copyAnnotation(JFieldVar outerField, JFieldVar innerField, Class<? extends Annotation> annotationClass) {
        var targetAnnotation = getAnnotation(innerField, annotationClass);
        if (targetAnnotation == null) return;

        var newAnnotation = outerField.annotate(targetAnnotation.getAnnotationClass());
        copyAnnotationMembers(targetAnnotation, newAnnotation);
    }

    /**
     * Merges {@link XmlSeeAlso} class references from the wrapper class into the
     * owning class to preserve polymorphic element references after flattening.
     */
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
        }
        var outerSeeAlsoTypes = (JAnnotationArrayMember) outerClassSeeAlso.getAnnotationMembers().get("value");
        if (outerSeeAlsoTypes == null) {
            outerSeeAlsoTypes = outerClassSeeAlso.paramArray("value");
        }
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

    /**
     * Ensures the element name stays consistent when the wrapper field name differs
     * from the inner collection field name.
     */
    private void setXmlElementName(JAnnotationUse targetAnnotation, JFieldVar outerField, JFieldVar innerField) {
        var name = getAnnotationNameValue(targetAnnotation);
        if (name == null && !outerField.name().equals(innerField.name())) {
            targetAnnotation.param("name", innerField.name());
        }
    }

    /**
     * Copies all annotation members from source to target.
     */
    private void copyAnnotationMembers(JAnnotationUse source, JAnnotationUse target) {
        for (var member : source.getAnnotationMembers().entrySet()) {
            target.param(member.getKey(), member.getValue());
        }
    }

    /**
     * Returns the explicit "name" member of an annotation if present.
     */
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

    /**
     * Removes the wrapper class when it is not referenced by any other generated class.
     * <p>
     * References include: super types, field types, return types, and parameter types.
     * If any reference remains, the wrapper class is preserved.
     * </p>
     */
    private boolean removeWrapperClass(JDefinedClass wrapperClass, Set<JDefinedClass> allClasses) {
        // Wrapper classes with inner classes are not removable.
        if (wrapperClass.classes().hasNext()) {
            return false;
        }
        allClasses.removeIf(definedClass -> definedClass.equals(wrapperClass));
        var wrapperClassName = wrapperClass.fullName();
        var existsReference = allClasses.stream()
            .anyMatch(definedClass -> JaxbClassRefactorUtil.hasReference(definedClass, wrapperClassName));
        if (!existsReference) {
            return removeWrapperClass(wrapperClass);
        }

        return false;
    }

    /**
     * Removes the wrapper class from the parent container and ObjectFactory.
     *
     * @param wrapperClass The wrapper class to remove.
     */
    private boolean removeWrapperClass(JDefinedClass wrapperClass) {
        JaxbClassRefactorUtil.removeFromParentContainer(wrapperClass);
        JaxbClassRefactorUtil.removeFromObjectFactory(wrapperClass);

        return true;
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
    private Map<String, ClassOutline> findElementWrapperClasses(Outline outline) {
        return outline.getClasses().stream()
            .filter(this::isWrapperClass)
            .collect(Collectors.toMap(i -> i.implClass.fullName(), Function.identity()));
    }

    /**
     * Checks if a class is a wrapper class that can be flattened by the plugin.
     * <p>
     * A wrapper class is defined as:
     * <ul>
     *     <li>Not an abstract class</li>
     *     <li>Not an interface</li>
     *     <li>Has no superclass</li>
     *     <li>Contains exactly one non-static field</li>
     *     <li>The field type is a collection</li>
     *     <li>The field type is not JAXBElement</li>
     *     <li>The field has no conflicting JAXB annotations</li>
     * </ul>
     * </p>
     *
     * @param classOutline The class outline to check
     * @return {@code true} if the class is a wrapper class, {@code false} otherwise
     */
    private boolean isWrapperClass(ClassOutline classOutline) {
        var implClass = classOutline.implClass;
        // Check if the class is abstract, an interface, or has a superclass, it cannot be a wrapper class.
        if (implClass.isAbstract() || implClass.isInterface() || implClass.superClass() != null) {
            return false;
        }

        var fields = implClass.fields();
        if (fields.size() != 1) {
            return false;
        }

        // Get the single field
        var field = fields.values().iterator().next();
        if (isStatic(field.mods())) {
            return false;
        }
        var fieldTypeName = field.type().fullName();

        return isCollection(fieldTypeName)
            && !ClassNameDetector.detect(fieldTypeName, JAXBElement.class.getName())
            && !hasConflictingAnnotation(field);
    }

    /**
     * Returns true if the modifiers contain the static modifier.
     */
    private boolean isStatic(JMods mods) {
        return (mods.getValue() & JMod.STATIC) != 0;
    }

    /**
     * Returns true if the full type name is a collection type.
     */
    private boolean isCollection(String fullTypeName) {
        if (fullTypeName == null) {
            return false;
        }
        var idx = fullTypeName.indexOf('<');
        if (idx > 0) {
            fullTypeName = fullTypeName.substring(0, idx);
        }
        try {
            var clazz = Class.forName(fullTypeName);
            return Collection.class.isAssignableFrom(clazz);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks whether a field contains JAXB annotations that should prevent wrapper flattening.
     */
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
     * Returns true for JAXB annotations that conflict with wrapper flattening.
     */
    private boolean isConflictingJaxbAnnotation(String className) {
        return className.equals(XmlElementWrapper.class.getName())
            || className.equals(XmlAttribute.class.getName())
            || className.equals(XmlAnyAttribute.class.getName())
            || className.equals(XmlValue.class.getName())
            || className.equals(XmlList.class.getName())
            || className.equals(XmlTransient.class.getName());
    }


    /**
     * Gets the annotation of the specified type for the given annotatable element.
     *
     * @param annotatable The annotatable element to get the annotation for.
     * @param clazz       The class of the annotation to get.
     * @param <T>         The type of the annotation.
     * @return The annotation of the specified type, or null if not found.
     */
    private <T extends Annotation> JAnnotationUse getAnnotation(JAnnotatable annotatable, Class<T> clazz) {
        return annotatable.annotations().stream()
            .filter(anno -> anno.getAnnotationClass().fullName().equals(clazz.getName()))
            .findFirst()
            .orElse(null);
    }
}
