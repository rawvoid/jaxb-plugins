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

import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.PackageOutline;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * JAXB plugin that normalizes duplicated generated classes by merging identical structures.
 *
 * @author Rawvoid
 */
@Option(name = "Xnormalize-class", description = "Normalize generated classes")
public class NormalizeClassPlugin extends AbstractPlugin {

    private static final String FIELD_MODS = "mods";

    private static final java.lang.reflect.Field JMODS_MODS_FIELD = getField(JMods.class, FIELD_MODS);

    private static java.lang.reflect.Field getField(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ex) {
            throw new IllegalStateException("Failed to access field '" + name + "' on " + type.getName(), ex);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        removeEmptyDerivedClasses(outline);
        outline.getAllPackageContexts().forEach(packageOutline -> {
            removeDuplicateClasses(packageOutline);
        });
        if (opt.debugMode) {
            JaxbClassRefactorUtil.fixJAXBDebugClass(outline);
        }
        return true;
    }

    private void removeEmptyDerivedClasses(Outline outline) {
        var classOutlines = new ArrayList<>(outline.getClasses());
        classOutlines.forEach(classOutline -> {
            var implClass = classOutline.implClass;
            var superClass = implClass.superClass();
            if (!(superClass instanceof JDefinedClass definedSuperClass)) {
                return;
            }
            if (!isEmptyClass(implClass)) {
                return;
            }
            var className = implClass.name();
            var superClassName = definedSuperClass.name();
            if (!(className.startsWith(superClassName) || className.endsWith(superClassName)
                || superClassName.startsWith(className) || superClassName.endsWith(className))) {
                return;
            }

            JaxbClassRefactorUtil.removeFromParentContainer(implClass);
            JaxbClassRefactorUtil.removeFromObjectFactory(implClass);

            outline.getClasses().forEach(c ->
                JaxbClassRefactorUtil.replaceClassReferences(c.implClass, implClass, definedSuperClass));

            if (definedSuperClass.isAbstract()) {
                clearAbstractModifier(definedSuperClass.mods());
            }
        });
    }

    private boolean isEmptyClass(JDefinedClass definedClass) {
        return definedClass.fields().isEmpty()
            && definedClass.methods().isEmpty()
            && !definedClass.classes().hasNext();
    }

    private void clearAbstractModifier(JMods mods) {
        try {
            var flags = (int) JMODS_MODS_FIELD.get(mods);
            JMODS_MODS_FIELD.set(mods, flags & ~JMod.ABSTRACT);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Failed to clear abstract modifier", ex);
        }
    }

    private void removeDuplicateClasses(PackageOutline packageOutline) {
        var classOutlines = packageOutline.getClasses();
        var classOutlinesGroup = classOutlines.stream()
            .collect(Collectors.groupingBy(c -> c.implClass.name().toUpperCase()));

        classOutlinesGroup.forEach((name, sameNameClasses) -> {
            if (sameNameClasses.size() == 1) {
                return;
            }
            var groupedClasses = groupingEqualClasses(sameNameClasses);
            if (groupedClasses.size() > 1) {
                return;
            }
            for (var groupedClass : groupedClasses) {
                if (groupedClass.size() <= 1) {
                    continue;
                }
                groupedClass.sort(Comparator.comparing(this::innerDepth));
                var savingClass = groupedClass.getFirst();

                var removeClasses = groupedClass.subList(1, groupedClass.size());
                removeAndReplaceClass(removeClasses, savingClass);
            }
        });
    }

    private int innerDepth(ClassOutline classOutline) {
        return innerDepth(classOutline.implClass);
    }

    private int innerDepth(JDefinedClass definedClass) {
        var depth = 0;
        var currentContainer = definedClass.parentContainer();
        while (currentContainer instanceof JDefinedClass) {
            depth++;
            currentContainer = currentContainer.parentContainer();
        }
        return depth;
    }

    private List<List<ClassOutline>> groupingEqualClasses(Collection<? extends ClassOutline> classes) {
        List<List<ClassOutline>> groupedClasses = new ArrayList<>();
        classes.forEach(classOutline -> groupedClasses.stream()
            .filter(group -> isEqual(group.getFirst().implClass, classOutline.implClass))
            .findFirst()
            .ifPresentOrElse(group -> group.add(classOutline),
                () -> groupedClasses.add(new ArrayList<>(List.of(classOutline)))));
        return groupedClasses;
    }

    private void removeAndReplaceClass(List<ClassOutline> removeClasses, ClassOutline replaceClassOutline) {
        var replaceClass = replaceClassOutline.implClass;
        var outline = replaceClassOutline.parent();

        removeClasses.forEach(classOutline -> {
            var removeClass = classOutline.implClass;
            JaxbClassRefactorUtil.removeFromParentContainer(removeClass);
            JaxbClassRefactorUtil.removeFromObjectFactory(removeClass);

            outline.getClasses().forEach(c ->
                JaxbClassRefactorUtil.replaceClassReferences(c.implClass, removeClass, replaceClass));
        });
    }

    private boolean isEqual(JDefinedClass class1, JDefinedClass class2) {
        if (!Objects.equals(class1.name(), class2.name())) {
            return false;
        }

        if (class1.classes().hasNext() || class2.classes().hasNext()) {
            return false;
        }

        if (class1.superClass() != class2.superClass()) {
            return false;
        }

        var implements1 = StreamSupport.stream(Spliterators
                .spliteratorUnknownSize(class1._implements(), Spliterator.ORDERED), false)
            .collect(Collectors.toSet());
        var implements2 = StreamSupport.stream(Spliterators
                .spliteratorUnknownSize(class2._implements(), Spliterator.ORDERED), false)
            .collect(Collectors.toSet());

        if (!Objects.equals(implements1, implements2)) {
            return false;
        }

        if (!isEqual(class1.annotations(), class2.annotations())) {
            return false;
        }

        var fields1 = class1.fields();
        var fields2 = class2.fields();

        if (fields1.size() != fields2.size()) {
            return false;
        }

        for (var entry : fields1.entrySet()) {
            var fieldName = entry.getKey();
            var field1 = entry.getValue();
            var field2 = fields2.get(fieldName);

            if (field2 == null) {
                return false;
            }

            if (!Objects.equals(field1.type().fullName(), field2.type().fullName())) {
                return false;
            }

            if (!isEqual(field1.annotations(), field2.annotations())) {
                return false;
            }
        }

        return true;
    }

    private boolean isEqual(Collection<JAnnotationUse> annos1, Collection<JAnnotationUse> annos2) {
        if (annos1.size() != annos2.size()) {
            return false;
        }

        var map1 = annos1.stream()
            .collect(Collectors.groupingBy(a -> a.getAnnotationClass().fullName()));
        var map2 = annos2.stream()
            .collect(Collectors.groupingBy(a -> a.getAnnotationClass().fullName()));

        if (!Objects.equals(map1.keySet(), map2.keySet())) {
            return false;
        }

        for (var fullName : map1.keySet()) {
            var list1 = map1.get(fullName);
            var list2 = map2.get(fullName);

            if (list1.size() != list2.size()) {
                return false;
            }

            for (var i = 0; i < list1.size(); i++) {
                var anno1 = list1.get(i);
                var anno2 = list2.get(i);

                if (!isEqual(anno1, anno2)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isEqual(JAnnotationUse anno1, JAnnotationUse anno2) {
        var members1 = anno1.getAnnotationMembers();
        var members2 = anno2.getAnnotationMembers();

        if (members1.size() != members2.size()) {
            return false;
        }

        for (var entry : members1.entrySet()) {
            var memberName = entry.getKey();
            var member1 = entry.getValue();
            var member2 = members2.get(memberName);

            if (member2 == null) {
                return false;
            }

            if (member1.getClass() != member2.getClass()) {
                return false;
            }

            if (!isEqual(member1, member2)) {
                return false;
            }
        }

        return true;
    }

    private boolean isEqual(JAnnotationValue value1, JAnnotationValue value2) {
        if (value1.getClass() != value2.getClass()) {
            return false;
        }

        if (value1 instanceof JAnnotationStringValue) {
            var stringValue1 = value1.toString();
            var stringValue2 = value2.toString();
            if (!Objects.equals(stringValue1, stringValue2)) {
                return false;
            }
        } else if (value1 instanceof JAnnotationClassValue) {
            var type1 = ((JAnnotationClassValue) value1).type();
            var type2 = ((JAnnotationClassValue) value2).type();
            if (!Objects.equals(type1.fullName(), type2.fullName())) {
                return false;
            }

            var value1Value = ((JAnnotationClassValue) value1).value();
            var value2Value = ((JAnnotationClassValue) value2).value();
            if (!Objects.equals(value1Value, value2Value)) {
                return false;
            }
        } else if (value1 instanceof JAnnotationArrayMember) {
            var values1 = ((JAnnotationArrayMember) value1).annotations2()
                .stream().toList();
            var values2 = ((JAnnotationArrayMember) value2).annotations2()
                .stream().toList();

            if (values1.size() != values2.size()) {
                return false;
            }

            for (var i = 0; i < values1.size(); i++) {
                var value1Item = values1.get(i);
                var value2Item = values2.get(i);
                if (!isEqual(value1Item, value2Item)) {
                    return false;
                }
            }
        } else if (value1 instanceof JAnnotationUse anno1) {
            var anno2 = (JAnnotationUse) value2;
            if (!isEqual(anno1, anno2)) {
                return false;
            }
        } else {
            throw new IllegalArgumentException("Unknown annotation value type: " + value1.getClass());
        }

        return true;
    }
}
