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
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.ClassNameDetector;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Structural refactoring utilities for JAXB-generated code.
 * <p>
 * This utility centralizes the following capabilities:
 * <ul>
 *     <li>Fix the {@code createContext} generation logic in {@code JAXBDebug}</li>
 *     <li>Safely remove deleted classes from the parent container and {@code ObjectFactory}</li>
 *     <li>Find and replace class references in fields, methods, parameters, and annotations</li>
 *     <li>Remove XJC property accessors by model property names (not crude name prefixes)</li>
 * </ul>
 *
 */
public final class OutlineUtils {

    private OutlineUtils() {
    }

    /**
     * Removes XJC-generated property accessors from a class using the property model.
     * <p>
     * Names follow XJC ({@code prop.getName(true)} seed + {@code get}/{@code is}/{@code set}).
     * Removal also checks arity so unrelated overloads are kept:
     * </p>
     * <ul>
     *   <li>Bean getter: {@code getX()}/{@code isX()} — 0 params, non-void return</li>
     *   <li>Array indexed getter (collection): {@code getX(int)} — 1 param</li>
     *   <li>Array length (collection only): {@code getXLength()} — 0 params
     *       (XJC {@code ArrayField}, not a JavaBeans property)</li>
     *   <li>Bean / array setter: {@code setX(T)} or {@code setX(T[])} — 1 param</li>
     *   <li>Array indexed setter (collection): {@code setX(int, T)} — 2 params</li>
     * </ul>
     * <p>
     * {@code isSetX}/{@code unsetX} are not matched. Only
     * {@link ClassOutline#getDeclaredFields() declared} properties are considered.
     * </p>
     *
     * @param classOutline  target class outline
     * @param removeGetters remove property getters when true
     * @param removeSetters remove property setters when true
     */
    public static void removePropertyAccessors(ClassOutline classOutline, boolean removeGetters, boolean removeSetters) {
        if (!removeGetters && !removeSetters) {
            return;
        }
        var properties = new ArrayList<PropertyAccessors>();
        for (var fieldOutline : classOutline.getDeclaredFields()) {
            var prop = fieldOutline.getPropertyInfo();
            properties.add(new PropertyAccessors(prop.getName(true), prop.isCollection()));
        }
        if (properties.isEmpty()) {
            return;
        }
        classOutline.implClass.methods().removeIf(method -> {
            for (var property : properties) {
                if (removeGetters && isXjcPropertyGetter(method, property)) {
                    return true;
                }
                if (removeSetters && isXjcPropertySetter(method, property)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Whether {@code method} is an XJC-style getter for the given property.
     */
    private static boolean isXjcPropertyGetter(JMethod method, PropertyAccessors property) {
        if (isVoidReturn(method)) {
            return false;
        }
        var name = method.name();
        var arity = method.params().size();
        var getName = "get" + property.seed();
        var isName = "is" + property.seed();

        // getX() / isX() — single value or whole list/array
        if ((getName.equals(name) || isName.equals(name)) && arity == 0) {
            return true;
        }
        // ArrayField only: getX(int idx)
        if (property.collection() && getName.equals(name) && arity == 1) {
            return true;
        }
        // ArrayField only: getXLength()
        return property.collection()
            && ("get" + property.seed() + "Length").equals(name)
            && arity == 0;
    }

    /**
     * Whether {@code method} is an XJC-style setter for the given property.
     */
    private static boolean isXjcPropertySetter(JMethod method, PropertyAccessors property) {
        if (!("set" + property.seed()).equals(method.name())) {
            return false;
        }
        var arity = method.params().size();
        // setX(value) or setX(T[])
        if (arity == 1) {
            return true;
        }
        // ArrayField only: setX(int idx, T value)
        return property.collection() && arity == 2;
    }

    private static boolean isVoidReturn(JMethod method) {
        var type = method.type();
        return type != null && "void".equals(type.name());
    }

    /**
     * Fixes {@code JAXBDebug#createContext} generation to prevent stale references after class removal.
     * <p>
     * Key steps:
     * <ul>
     *     <li>Locate {@code JAXBDebug} in the root package, return if it does not exist</li>
     *     <li>Remove the old {@code createContext} method</li>
     *     <li>Rebuild the method and append arguments based on the model strategy</li>
     * </ul>
     *
     * @param outline the XJC outline containing the generated model and code
     */
    public static void fixJAXBDebugClass(Outline outline) {
        var model = outline.getModel();
        var codeModel = outline.getCodeModel();
        var rootPackage = codeModel.rootPackage();
        var jaxbDebugClass = rootPackage._getClass("JAXBDebug");
        if (jaxbDebugClass == null) {
            return;
        }
        // Remove the old createContext method to prevent duplicate definitions.
        jaxbDebugClass.methods().removeIf(method -> method.name().equals("createContext"));
        // Rebuild createContext and construct the JAXBContext.newInstance(...) call.
        var createContextMethod = jaxbDebugClass.method(JMod.PUBLIC | JMod.STATIC, JAXBContext.class, "createContext");
        var classLoader = createContextMethod.param(ClassLoader.class, "classLoader");
        createContextMethod._throws(JAXBException.class);
        var invoke = codeModel.ref(JAXBContext.class).staticInvoke("newInstance");
        createContextMethod.body()._return(invoke);

        var packageContexts = outline.getAllPackageContexts();
        switch (model.strategy) {
            case INTF_AND_IMPL -> {
                // Interface + implementation strategy: pass a package name joiner string.
                var joiner = new StringJoiner(":");
                for (var packageOutline : packageContexts) {
                    joiner.add(packageOutline._package().name());
                }
                invoke.arg(joiner.toString()).arg(classLoader);
            }
            case BEAN_ONLY -> {
                // Bean-only strategy: pass a list of class literals.
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
     * Removes the specified class from the JAXB {@link Outline} when it is not referenced by any other class.
     * <p>
     * Key steps:
     * <ul>
     *     <li>Scan all generated classes to detect references to the target type</li>
     *     <li>If any reference exists, skip removal and return {@code false}</li>
     *     <li>Remove the class from {@link Outline} lists and its parent container</li>
     *     <li>Remove related factory methods from {@code ObjectFactory}</li>
     * </ul>
     *
     * @param definedClass the class to remove
     * @param outline      the JAXB outline containing all generated classes
     * @return {@code true} if the class was removed, {@code false} if references were found or input is invalid
     */
    public static boolean removeClass(JDefinedClass definedClass, Outline outline) {
        if (definedClass == null || outline == null) {
            return false;
        }
        var classFullName = definedClass.fullName();
        var hasReference = outline.getClasses().stream()
            .map(classOutline -> classOutline.implClass)
            .filter(implClass -> implClass != definedClass)
            .anyMatch(implClass -> hasReference(implClass, classFullName));
        if (hasReference) {
            return false;
        }

        outline.getClasses().removeIf(classOutline -> classOutline.implClass == definedClass);
        outline.getAllPackageContexts().forEach(packageOutline ->
            packageOutline.getClasses().removeIf(classOutline -> classOutline.implClass == definedClass));

        removeFromParentContainer(definedClass);
        removeFromObjectFactory(definedClass);

        return true;
    }

    /**
     * Removes a class from its parent container (outer class or package).
     * <p>
     * Key steps:
     * <ul>
     *     <li>Locate the parent container</li>
     *     <li>Iterate over the container classes, find the target, and remove it</li>
     * </ul>
     *
     * @param definedClass the class to remove from its parent container
     */
    public static void removeFromParentContainer(JDefinedClass definedClass) {
        var parentContainer = definedClass.parentContainer();
        var classes = switch (parentContainer) {
            case JDefinedClass clazz -> clazz.classes();
            case JPackage jPackage -> jPackage.classes();
            default -> throw new IllegalArgumentException("Unknown parent container type: " + parentContainer);
        };
        // Remove the target class safely via iterator.
        while (classes.hasNext()) {
            var nextClass = classes.next();
            if (nextClass == definedClass) {
                classes.remove();
                break;
            }
        }
    }

    /**
     * Removes factory methods related to the target type from the package {@code ObjectFactory}.
     * <p>
     * Key steps:
     * <ul>
     *     <li>Locate the package and {@code ObjectFactory} class</li>
     *     <li>Remove methods whose return types match the target class</li>
     * </ul>
     *
     * @param removeClass the class whose factory methods should be removed
     */
    public static void removeFromObjectFactory(JDefinedClass removeClass) {
        var jPackage = removeClass._package();
        if (jPackage == null) {
            return;
        }
        var objectFactoryClass = jPackage._getClass("ObjectFactory");
        if (objectFactoryClass == null) {
            return;
        }
        // Remove factory methods returning the target class to avoid dangling references.
        objectFactoryClass.methods().removeIf(method ->
            ClassNameDetector.detect(method.type().fullName(), removeClass.fullName()));
    }

    /**
     * Determines whether a class references the specified type (superclass, fields, methods, parameters, annotations).
     *
     * @param definedClass  the class to inspect
     * @param classFullName the fully qualified class name to look for
     * @return true if any reference exists, false otherwise
     */
    public static boolean hasReference(JDefinedClass definedClass, String classFullName) {
        var superClass = definedClass.superClass();
        var isRefBySuperClass = superClass != null && ClassNameDetector.detect(superClass.fullName(), classFullName);
        // Aggregate reference checks across all possible sources.
        return isRefBySuperClass
            || isRefByAnnotation(definedClass, classFullName)
            || definedClass.fields().values().stream()
            .anyMatch(field -> isRefInField(field, classFullName))

            || definedClass.methods().stream()
            .anyMatch(method -> isRefInMethod(method, classFullName));
    }

    /**
     * Replaces all references to the target type in the specified class with the new type.
     * <p>
     * Key steps:
     * <ul>
     *     <li>Replace superclass references</li>
     *     <li>Replace type references in class-level annotations</li>
     *     <li>Traverse fields, methods, and parameters, replacing types and annotation references</li>
     * </ul>
     *
     * @param definedClass  the class whose members should be updated
     * @param targetType    the type to be replaced
     * @param newTargetType the replacement type
     */
    public static void replaceClassReferences(JDefinedClass definedClass, JDefinedClass targetType, JDefinedClass newTargetType) {
        if (definedClass == null) {
            return;
        }
        if (definedClass.superClass() == targetType) {
            definedClass._extends(newTargetType);
        }
        // Replace class-level annotation references first.
        replaceAnnotationReferences(definedClass, targetType, newTargetType);
        // Replace field types and annotation references on fields.
        definedClass.fields().forEach((fieldName, fieldVar) -> {
            replaceType(fieldVar.type(), targetType, newTargetType, fieldVar::type);
            replaceAnnotationReferences(fieldVar, targetType, newTargetType);
        });
        // Replace method return types, parameter types, and annotation references on methods/parameters.
        definedClass.methods().forEach(method -> {
            replaceType(method.type(), targetType, newTargetType, method::type);
            replaceAnnotationReferences(method, targetType, newTargetType);
            method.params().forEach(param -> {
                replaceType(param.type(), targetType, newTargetType, param::type);
                replaceAnnotationReferences(param, targetType, newTargetType);
            });
        });
    }

    /**
     * Replaces type references inside annotations on the specified element.
     *
     * @param annotatable   the annotated element to update
     * @param targetType    the type to be replaced
     * @param newTargetType the replacement type
     */
    public static void replaceAnnotationReferences(JAnnotatable annotatable, JDefinedClass targetType, JDefinedClass newTargetType) {
        if (annotatable == null) {
            return;
        }
        // Iterate over all annotations and replace type references in member values.
        annotatable.annotations().forEach(annotation -> replaceAnnotationUse(annotation, targetType, newTargetType));
    }

    /**
     * Replaces references to the target type within a type object.
     * <p>
     * This method supports three shapes:
     * <ul>
     *     <li>Direct type {@link JDefinedClass}</li>
     *     <li>Generic type {@code JNarrowedClass}</li>
     *     <li>Array type {@code JArrayClass}</li>
     * </ul>
     *
     * @param currentType   the current type to inspect
     * @param targetType    the type to be replaced
     * @param newTargetType the replacement type
     * @param typeSetter    callback to apply the replacement when needed
     */
    public static void replaceType(JType currentType, JDefinedClass targetType, JDefinedClass newTargetType, Consumer<JType> typeSetter) {
        if (!ClassNameDetector.detect(currentType.fullName(), targetType.fullName())) {
            return;
        }
        try {
            var clazz = currentType.getClass();
            if (currentType instanceof JDefinedClass) {
                if (currentType == targetType) {
                    // Direct type match; use the callback to set the new type.
                    typeSetter.accept(newTargetType);
                }
            } else if (clazz.getSimpleName().equals("JNarrowedClass")) {
                // Handle generic type narrowing.
                var newNarrowedClass = newJNarrowedClass(currentType, targetType, newTargetType);
                typeSetter.accept(newNarrowedClass);
            } else if (clazz.getSimpleName().equals("JArrayClass")) {
                // Handle array component types.
                var componentTypeField = clazz.getDeclaredField("componentType");
                componentTypeField.setAccessible(true);
                var componentType = (JType) componentTypeField.get(currentType);
                // Replace only when the component type equals the target type.
                if (componentType == targetType) {
                    componentTypeField.set(currentType, newTargetType);
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to replace type for " + currentType.fullName(), e);
        }
    }

    public static JType newJNarrowedClass(JType currentType, JDefinedClass targetType, JDefinedClass newTargetType) {
        var clazz = currentType.getClass();
        if (!clazz.getSimpleName().equals("JNarrowedClass")) {
            throw new IllegalArgumentException("Original type must be a JNarrowedClass");
        }

        try {
            var basisField = clazz.getDeclaredField("basis");
            basisField.setAccessible(true);
            var basis = (JClass) basisField.get(currentType);

            var argsField = clazz.getDeclaredField("args");
            argsField.setAccessible(true);
            var args = (List<JClass>) argsField.get(currentType);

            args = new ArrayList<>(args);
            args.replaceAll(arg -> arg == targetType ? newTargetType : arg);


            var constructor = clazz.getDeclaredConstructor(JClass.class, List.class);
            constructor.setAccessible(true);

            return constructor.newInstance(basis, args);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InstantiationException |
                 InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Determines whether a field references the specified type (field type or annotations).
     */
    private static boolean isRefInField(JFieldVar fieldVar, String classFullName) {
        var typeName = fieldVar.type().fullName();
        var isRefByType = ClassNameDetector.detect(typeName, classFullName);
        return isRefByType || isRefByAnnotation(fieldVar, classFullName);
    }

    /**
     * Determines whether a method references the specified type (return type, parameters, or annotations).
     */
    private static boolean isRefInMethod(JMethod method, String classFullName) {
        var returnTypeName = method.type().fullName();
        var isRefByReturn = ClassNameDetector.detect(returnTypeName, classFullName);
        return isRefByReturn || isRefByAnnotation(method, classFullName)
            || method.params().stream()
            .anyMatch(param -> isRefInMethodParam(param, classFullName));
    }

    /**
     * Determines whether a method parameter references the specified type (parameter type or annotations).
     */
    private static boolean isRefInMethodParam(JVar param, String classFullName) {
        var typeName = param.type().fullName();
        var isRefByType = ClassNameDetector.detect(typeName, classFullName);
        return isRefByType || isRefByAnnotation(param, classFullName);
    }

    /**
     * Determines whether annotation members on the element reference the specified type.
     */
    private static boolean isRefByAnnotation(JAnnotatable annotatable, String classFullName) {
        if (annotatable == null) {
            return false;
        }
        return annotatable.annotations().stream()
            .anyMatch(annotation -> isRefInAnnotation(annotation, classFullName));
    }

    /**
     * Checks whether a single annotation's member values reference the specified type.
     */
    private static boolean isRefInAnnotation(JAnnotationUse annotation, String classFullName) {
        if (annotation == null) {
            return false;
        }
        // Supports class values, array members, and nested annotations.
        return annotation.getAnnotationMembers().values().stream().anyMatch(value -> switch (value) {
            case JAnnotationClassValue classValue ->
                ClassNameDetector.detect(classValue.type().fullName(), classFullName);
            case JAnnotationArrayMember arrayMember -> isRefInAnnotationArrayMember(arrayMember, classFullName);
            case JAnnotationUse annotationUse -> isRefInAnnotation(annotationUse, classFullName);
            default -> false;
        });
    }

    /**
     * Checks whether an annotation array member references the specified type (supports nested arrays/annotations).
     */
    private static boolean isRefInAnnotationArrayMember(JAnnotationArrayMember arrayMember, String classFullName) {
        if (arrayMember == null) {
            return false;
        }
        // Recursively check type/annotation references in array elements.
        return arrayMember.annotations2().stream().anyMatch(value -> switch (value) {
            case JAnnotationClassValue classValue ->
                ClassNameDetector.detect(classValue.type().fullName(), classFullName);
            case JAnnotationArrayMember subArrayMember -> isRefInAnnotationArrayMember(subArrayMember, classFullName);
            case JAnnotationUse annotationUse -> isRefInAnnotation(annotationUse, classFullName);
            default -> false;
        });
    }

    /**
     * Replaces references to the target type inside annotation member values.
     */
    private static void replaceAnnotationUse(JAnnotationUse annotation, JDefinedClass targetType, JDefinedClass newTargetType) {
        if (annotation == null) {
            return;
        }
        // Iterate over all member values and replace by type.
        annotation.getAnnotationMembers().values().forEach(value -> replaceAnnotationValue(value, targetType, newTargetType));
    }

    /**
     * Recursively replaces type references in annotation member values.
     */
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
    private static void replaceAnnotationClassValue(JAnnotationClassValue classValue, JDefinedClass targetType, JDefinedClass newTargetType) {
        var valueType = classValue.type();
        if (valueType == null || !ClassNameDetector.detect(valueType.fullName(), targetType.fullName())) {
            return;
        }
        // Use reflection to locate the actual stored type field.
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
            // Replace with the new target type.
            candidateField.set(classValue, newTargetType);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to replace annotation type for " + valueType.fullName(), e);
        }
    }

    /**
     * XJC property accessor name seeds and whether the property is multi-valued
     * (list or array realization).
     */
    private record PropertyAccessors(String seed, boolean collection) {
    }
}
