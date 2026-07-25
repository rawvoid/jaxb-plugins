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

package io.github.rawvoid.jaxb.utils;

import com.sun.codemodel.*;
import io.github.rawvoid.jaxb.plugin.ClassNameDetector;
import io.github.rawvoid.jaxb.plugin.TextParser;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared helpers for querying and mutating CodeModel annotations on {@link JAnnotatable} elements.
 * <p>
 * Covers existence checks, find/remove by FQCN, applying annox {@link XAnnotation} values,
 * reading simple string members, and walking annotation members for type references.
 * Domain-specific defaults (e.g. XmlElementWrapper namespace rules, XmlNs upsert) stay in the plugins.
 * </p>
 *
 * @author Rawvoid
 */
public final class AnnotationUtils {

    private AnnotationUtils() {
    }

    /**
     * Returns whether {@code annotatable} already has an annotation with the given FQCN.
     */
    public static boolean hasAnnotation(JAnnotatable annotatable, String fqcn) {
        return findAnnotation(annotatable, fqcn).isPresent();
    }

    /**
     * Returns whether {@code annotatable} already has an annotation of the given type.
     */
    public static boolean hasAnnotation(JAnnotatable annotatable, Class<? extends Annotation> annotationType) {
        return hasAnnotation(annotatable, annotationType.getName());
    }

    /**
     * Finds the first annotation on {@code annotatable} whose class FQCN matches.
     */
    public static Optional<JAnnotationUse> findAnnotation(JAnnotatable annotatable, String fqcn) {
        if (annotatable == null || fqcn == null) {
            return Optional.empty();
        }
        return annotatable.annotations().stream()
            .filter(a -> fqcn.equals(a.getAnnotationClass().fullName()))
            .findFirst();
    }

    /**
     * Finds the first annotation on {@code annotatable} of the given type.
     */
    public static Optional<JAnnotationUse> findAnnotation(JAnnotatable annotatable, Class<? extends Annotation> annotationType) {
        return findAnnotation(annotatable, annotationType.getName());
    }

    /**
     * Returns all annotations on {@code annotatable} whose class FQCN matches (supports repeatable).
     */
    public static List<JAnnotationUse> findAnnotations(JAnnotatable annotatable, String fqcn) {
        if (annotatable == null || fqcn == null) {
            return List.of();
        }
        return annotatable.annotations().stream()
            .filter(a -> fqcn.equals(a.getAnnotationClass().fullName()))
            .toList();
    }

    /**
     * Returns all annotations on {@code annotatable} of the given type.
     */
    public static List<JAnnotationUse> findAnnotations(JAnnotatable annotatable, Class<? extends Annotation> annotationType) {
        return findAnnotations(annotatable, annotationType.getName());
    }

    /**
     * Removes every annotation on {@code annotatable} whose class FQCN matches.
     */
    public static void removeAnnotations(JAnnotatable annotatable, String fqcn) {
        if (annotatable == null || fqcn == null) {
            return;
        }
        findAnnotations(annotatable, fqcn).forEach(annotatable::removeAnnotation);
    }

    /**
     * Removes every annotation on {@code annotatable} of the given type.
     */
    public static void removeAnnotations(JAnnotatable annotatable, Class<? extends Annotation> annotationType) {
        removeAnnotations(annotatable, annotationType.getName());
    }

    /**
     * Reads a string member from an annotation use.
     *
     * @return the string value, or {@code null} when the member is missing or not a string
     */
    public static String readStringMember(JAnnotationUse annotation, String member) {
        if (annotation == null || member == null) {
            return null;
        }
        var value = annotation.getAnnotationMembers().get(member);
        if (!(value instanceof JAnnotationStringValue stringValue)) {
            return null;
        }
        return stringValue.toString();
    }

    /**
     * Returns the existing annotation of {@code annotationType} if present; otherwise annotates with it.
     * <p>
     * Does not rewrite members of an existing annotation — callers that need params should only
     * set them when this method created a new use (or always overwrite intentionally).
     * </p>
     */
    public static JAnnotationUse annotateIfAbsent(JAnnotatable annotatable, Class<? extends Annotation> annotationType) {
        return findAnnotation(annotatable, annotationType)
            .orElseGet(() -> annotatable.annotate(annotationType));
    }

    /**
     * Returns the existing annotation whose class matches {@code annotationClass} if present;
     * otherwise annotates with it.
     */
    public static JAnnotationUse annotateIfAbsent(JAnnotatable annotatable, JClass annotationClass) {
        Objects.requireNonNull(annotationClass, "annotationClass");
        return findAnnotation(annotatable, annotationClass.fullName())
            .orElseGet(() -> annotatable.annotate(annotationClass));
    }

    /**
     * Applies an annox {@link XAnnotation} onto a CodeModel target.
     * <p>
     * If the annotation type is not {@link Repeatable} and the target already has the same
     * annotation, existing instances are removed first (replace). Repeatable annotations append.
     * </p>
     *
     * @return the newly created {@link JAnnotationUse}
     */
    public static JAnnotationUse applyXAnnotation(JAnnotatable target, XAnnotation<?> xAnnotation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(xAnnotation, "xAnnotation");

        var annotationClass = xAnnotation.getAnnotationClass();
        var existing = findAnnotations(target, annotationClass.getName());
        if (!existing.isEmpty() && annotationClass.getAnnotation(Repeatable.class) == null) {
            existing.forEach(target::removeAnnotation);
        }

        var annotationUse = target.annotate(annotationClass);
        xAnnotation.getFieldsList().forEach(field ->
            fillParam(annotationUse, field.getName(), field.getValue()));
        return annotationUse;
    }

    /**
     * Fills one named member of an annotation use from a value produced by annox or reflection.
     */
    public static void fillParam(JAnnotationUse annotationUse, String paramName, Object paramValue) {
        switch (paramValue) {
            case String strValue -> annotationUse.param(paramName, strValue);
            case Integer intValue -> annotationUse.param(paramName, intValue);
            case Boolean boolValue -> annotationUse.param(paramName, boolValue);
            case Character charValue -> annotationUse.param(paramName, charValue);
            case Byte byteValue -> annotationUse.param(paramName, byteValue);
            case Short shortValue -> annotationUse.param(paramName, shortValue);
            case Long longValue -> annotationUse.param(paramName, longValue);
            case Float floatValue -> annotationUse.param(paramName, floatValue);
            case Double doubleValue -> annotationUse.param(paramName, doubleValue);
            case Class<?> clazz -> annotationUse.param(paramName, clazz);
            case Enum<?> enumValue -> annotationUse.param(paramName, enumValue);
            case JEnumConstant enumConstant -> annotationUse.param(paramName, enumConstant);
            case JExpression expression -> annotationUse.param(paramName, expression);
            case JType type -> annotationUse.param(paramName, type);
            case XAnnotation<?> xAnno -> {
                var nestedUse = annotationUse.annotationParam(paramName, xAnno.getAnnotationClass());
                xAnno.getFieldsList().forEach(field ->
                    fillParam(nestedUse, field.getName(), field.getValue()));
            }
            case Annotation anno -> {
                var nestedUse = annotationUse.annotationParam(paramName, anno.annotationType());
                fillFromAnnotationInstance(nestedUse, anno);
            }
            default -> {
                Iterable<?> iterable;
                if (paramValue instanceof Iterable<?>) {
                    iterable = (Iterable<?>) paramValue;
                } else if (paramValue.getClass().isArray()) {
                    var length = Array.getLength(paramValue);
                    var list = new ArrayList<>(length);
                    for (var i = 0; i < length; i++) {
                        list.add(Array.get(paramValue, i));
                    }
                    iterable = list;
                } else {
                    throw new IllegalStateException("Unexpected value: " + paramValue);
                }
                var array = annotationUse.paramArray(paramName);
                iterable.forEach(item -> fillArrayParam(array, item));
            }
        }
    }

    /**
     * Fills one element of an annotation array member.
     */
    public static void fillArrayParam(JAnnotationArrayMember array, Object value) {
        switch (value) {
            case String strValue -> array.param(strValue);
            case Integer intValue -> array.param(intValue);
            case Boolean boolValue -> array.param(boolValue);
            case Character charValue -> array.param(charValue);
            case Byte byteValue -> array.param(byteValue);
            case Short shortValue -> array.param(shortValue);
            case Long longValue -> array.param(longValue);
            case Float floatValue -> array.param(floatValue);
            case Double doubleValue -> array.param(doubleValue);
            case Class<?> clazz -> array.param(clazz);
            case Enum<?> enumValue -> array.param(enumValue);
            case JEnumConstant enumConstant -> array.param(enumConstant);
            case JExpression expression -> array.param(expression);
            case JType type -> array.param(type);
            case XAnnotation<?> xAnno -> {
                var nestedUse = array.annotate(xAnno.getAnnotationClass());
                xAnno.getFieldsList().forEach(field ->
                    fillParam(nestedUse, field.getName(), field.getValue()));
            }
            case Annotation anno -> {
                var nestedUse = array.annotate(anno.annotationType());
                fillFromAnnotationInstance(nestedUse, anno);
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        }
    }

    /**
     * Parses an annotation source string via annox (e.g. {@code @lombok.Data}).
     *
     * @throws IllegalStateException if parsing fails
     */
    public static XAnnotation<?> parseXAnnotation(String source) {
        try {
            return XAnnotationParser.INSTANCE.parse(source);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse annotation: " + source, e);
        }
    }

    /**
     * TextParser for annox {@link XAnnotation} CLI option values.
     */
    @SuppressWarnings("rawtypes")
    public static TextParser<XAnnotation> xAnnotationTextParser() {
        return (optionName, text) -> XAnnotationParser.INSTANCE.parse(text.toString());
    }

    /**
     * Returns whether any annotation member on {@code annotatable} references
     * {@code classFullName} as a class value (including nested annotations and arrays).
     */
    public static boolean referencesType(JAnnotatable annotatable, String classFullName) {
        if (annotatable == null || classFullName == null) {
            return false;
        }
        return annotatable.annotations().stream()
            .anyMatch(annotation -> isRefInAnnotation(annotation, classFullName));
    }

    /**
     * Replaces type references inside annotation members on the specified element.
     *
     * @param annotatable   the annotated element to update
     * @param targetType    the type to be replaced
     * @param newTargetType the replacement type
     */
    public static void replaceAnnotationReferences(
        JAnnotatable annotatable,
        JDefinedClass targetType,
        JDefinedClass newTargetType
    ) {
        if (annotatable == null) {
            return;
        }
        annotatable.annotations().forEach(annotation -> replaceAnnotationUse(annotation, targetType, newTargetType));
    }

    private static void fillFromAnnotationInstance(JAnnotationUse annotationUse, Annotation anno) {
        for (var method : anno.annotationType().getDeclaredMethods()) {
            try {
                var val = method.invoke(anno);
                fillParam(annotationUse, method.getName(), val);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to invoke annotation method: " + method.getName(), e);
            }
        }
    }

    private static boolean isRefInAnnotation(JAnnotationUse annotation, String classFullName) {
        if (annotation == null) {
            return false;
        }
        return annotation.getAnnotationMembers().values().stream().anyMatch(value -> switch (value) {
            case JAnnotationClassValue classValue ->
                ClassNameDetector.detect(classValue.type().fullName(), classFullName);
            case JAnnotationArrayMember arrayMember -> isRefInAnnotationArrayMember(arrayMember, classFullName);
            case JAnnotationUse annotationUse -> isRefInAnnotation(annotationUse, classFullName);
            default -> false;
        });
    }

    private static boolean isRefInAnnotationArrayMember(JAnnotationArrayMember arrayMember, String classFullName) {
        if (arrayMember == null) {
            return false;
        }
        return arrayMember.annotations2().stream().anyMatch(value -> switch (value) {
            case JAnnotationClassValue classValue ->
                ClassNameDetector.detect(classValue.type().fullName(), classFullName);
            case JAnnotationArrayMember subArrayMember -> isRefInAnnotationArrayMember(subArrayMember, classFullName);
            case JAnnotationUse annotationUse -> isRefInAnnotation(annotationUse, classFullName);
            default -> false;
        });
    }

    private static void replaceAnnotationUse(JAnnotationUse annotation, JDefinedClass targetType, JDefinedClass newTargetType) {
        if (annotation == null) {
            return;
        }
        annotation.getAnnotationMembers().values().forEach(value ->
            replaceAnnotationValue(value, targetType, newTargetType));
    }

    private static void replaceAnnotationValue(JAnnotationValue value, JDefinedClass targetType, JDefinedClass newTargetType) {
        switch (value) {
            case JAnnotationClassValue classValue -> replaceAnnotationClassValue(classValue, targetType, newTargetType);
            case JAnnotationArrayMember arrayMember ->
                arrayMember.annotations2().forEach(item -> replaceAnnotationValue(item, targetType, newTargetType));
            case JAnnotationUse annotationUse -> replaceAnnotationUse(annotationUse, targetType, newTargetType);
            default -> {
            }
        }
    }

    /**
     * Replaces type references for {@code Class} values inside an annotation.
     * <p>
     * Since {@link JAnnotationClassValue} internals are not directly accessible,
     * use reflection to locate the {@link JClass}/{@link JType} field and perform the replacement.
     * </p>
     */
    private static void replaceAnnotationClassValue(
        JAnnotationClassValue classValue,
        JDefinedClass targetType,
        JDefinedClass newTargetType
    ) {
        var valueType = classValue.type();
        if (valueType == null || !ClassNameDetector.detect(valueType.fullName(), targetType.fullName())) {
            return;
        }
        var clazz = classValue.getClass();
        Field candidateField = null;
        try {
            for (var field : clazz.getDeclaredFields()) {
                if (JClass.class.isAssignableFrom(field.getType()) || JType.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    var current = field.get(classValue);
                    if (current instanceof JClass currentClass
                        && ClassNameDetector.detect(currentClass.fullName(), targetType.fullName())) {
                        candidateField = field;
                        break;
                    }
                }
            }
            if (candidateField == null) {
                throw new IllegalStateException("Failed to locate annotation class value field for " + valueType.fullName());
            }
            candidateField.set(classValue, newTargetType);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to replace annotation type for " + valueType.fullName(), e);
        }
    }
}
