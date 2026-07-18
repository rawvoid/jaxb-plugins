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

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.*;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.*;
import java.util.stream.Collectors;

import static io.github.rawvoid.jaxb.utils.ModelUtils.*;

/**
 * JAXB plugin that normalizes duplicated generated classes by merging identical structures.
 *
 * @author Rawvoid
 */
@Option(name = "Xnormalize-class", description = "Normalize generated classes")
public class NormalizeClassPlugin extends AbstractPlugin {

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var pkg = model.codeModel._package("");
        var classGroups = model.beans().values().stream()
            .collect(Collectors.groupingBy((classInfo -> classInfo.getOwnerPackage())));
        model.beans().values().forEach(c -> {
            var nestedClasses = getAllNestedClasses(model, c);
            var name = c.shortName;
            var isBoxedType = c.isBoxedType();
            var isElement = c.isElement();
            var parent = c.parent();
            var isRootElement = isRootElementClass(model, c);
            var customizations = c.getCustomizations();
            customizations.forEach(customization -> {
                System.out.println("Customization: " + customization.element);
            });
            System.out.println("Class: " + name + ", isBoxedType: " + isBoxedType + ", isElement: " + isElement + ", parent: " + parent + ", isRootElement: " + isRootElement + ", customizations: " + customizations);
        });
        removeEmptyDerivedClasses(model);
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        return true;
    }

    private void removeEmptyDerivedClasses(Model model) {
        var classes = new ArrayList<>(model.beans().values());
        classes.forEach(classInfo -> {
            if (!isEmptyDerivedClass(model, classInfo)) {
                return;
            }
            var baseClass = classInfo.getBaseClass();

            replaceClassReferences(model, classInfo, baseClass);
            removeClass(model, classInfo);

            if (isAbstract(baseClass)) {
                clearAbstract(baseClass);
            }
        });
    }

    private boolean isEmptyDerivedClass(Model model, CClassInfo classInfo) {
        var baseClass = classInfo.getBaseClass();
        if (baseClass == null) {
            return false;
        }
        if (!isEmptyClass(classInfo)) {
            return false;
        }
        if (!isSimilarClassName(classInfo.shortName, baseClass.shortName)) {
            return false;
        }

        return !isRootElementClass(model, classInfo);
    }

    private boolean isRootElementClass(Model model, CClassInfo classInfo) {
        for (var element : model.getAllElements()) {
            if (element.getProperty().ref().contains(classInfo)) return true;
        }
        return false;
    }

    private boolean isEmptyClass(CClassInfo classInfo) {
        return classInfo.getProperties().isEmpty();
    }

    private boolean isSimilarClassName(String className, String superClassName) {
        return className.startsWith(superClassName)
            || className.endsWith(superClassName)
            || superClassName.startsWith(className)
            || superClassName.endsWith(className);
    }

    private boolean isAbstract(CClassInfo classInfo) {
        try {
            return (boolean) ABSTRACT_FIELD.get(classInfo);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Failed to read abstract flag", ex);
        }
    }

    private void clearAbstract(CClassInfo classInfo) {
        try {
            ABSTRACT_FIELD.set(classInfo, false);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Failed to clear abstract flag", ex);
        }
    }

}
