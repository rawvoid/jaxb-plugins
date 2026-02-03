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
 * @author Rawvoid
 */
@Option(name = "Xnormalize-class", description = "Normalize generated classes")
public class NormalizeClassPlugin extends AbstractPlugin {

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        outline.getAllPackageContexts().forEach(packageOutline -> {
            removeDuplicateClasses(packageOutline);
        });
        return true;
    }

    private void removeDuplicateClasses(PackageOutline packageOutline) {
        var classOutlines = packageOutline.getClasses();

        var groupedClasses = groupingEqualClasses(classOutlines);
        for (var groupedClass : groupedClasses) {
            if (groupedClass.size() <= 1) {
                continue;
            }
            groupedClass.sort(Comparator.comparing(this::innerDepth));
            var savingClass = groupedClass.getFirst();

            var removeClasses = groupedClass.subList(1, groupedClass.size());
            removeAndReplaceClass(removeClasses, savingClass);
        }
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

    private List<List<ClassOutline>> groupingEqualClasses(Set<? extends ClassOutline> classes) {
        List<List<ClassOutline>> groupedClasses = new ArrayList<>();
        for (var classOutline : classes) {
            groupedClasses.stream()
                .filter(group -> isEqual(group.getFirst().implClass, classOutline.implClass))
                .findFirst()
                .ifPresentOrElse(group -> group.add(classOutline),
                    () -> groupedClasses.add(new ArrayList<>(List.of(classOutline))));
        }
        return groupedClasses;
    }

    private void removeAndReplaceClass(List<ClassOutline> removeClasses, ClassOutline replaceClassOutline) {
        var replaceClass = replaceClassOutline.implClass;
        var packageOutline = replaceClassOutline._package();
        var outline = replaceClassOutline.parent();

        removeClasses.forEach(classOutline -> {
            var removeClass = classOutline.implClass;
            removeFromParentContainer(removeClass);
            var jPackage = removeClass.getPackage();

            outline.getClasses().forEach(c -> {
                var definedClass = c.implClass;

                definedClass.fields().forEach((fieldName, fieldVar) -> {
                    replaceType(fieldVar.type(), removeClass, replaceClass, () -> fieldVar.type(replaceClass));
                });

                definedClass.methods().forEach(method -> {
                    replaceType(method.type(), removeClass, replaceClass, () -> method.type(replaceClass));

                    method.params().forEach(param -> {
                        replaceType(param.type(), removeClass, replaceClass, () -> param.type(replaceClass));
                    });
                });
            });
        });
    }

    private void removeFromParentContainer(JDefinedClass definedClass) {
        var parentContainer = definedClass.parentContainer();

        var classes = parentContainer.classes();
        while (classes.hasNext()) {
            var nextClass = classes.next();
            if (nextClass == definedClass) {
                classes.remove();
                break;
            }
        }
    }

    private void replaceType(JType currentType, JDefinedClass targetType, JDefinedClass newTargetType, Runnable typeSetter) {
        try {
            var clazz = currentType.getClass();
            if (currentType instanceof JDefinedClass) {
                if (currentType == targetType) {
                    typeSetter.run();
                }
            } else if (clazz.getSimpleName().equals("JNarrowedClass")) {
                var argsField = clazz.getField("args");
                argsField.setAccessible(true);
                var args = (List<JClass>) argsField.get(currentType);

                args.replaceAll(arg -> arg == targetType ? newTargetType : arg);
            } else if (clazz.getSimpleName().equals("JArrayClass")) {
                var componentTypeField = clazz.getField("componentType");
                componentTypeField.setAccessible(true);
                var componentType = (JType) componentTypeField.get(currentType);

                if (componentType == targetType) {
                    componentTypeField.set(currentType, newTargetType);
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to replace type for " + currentType.fullName(), e);
        }
    }

    private boolean isEqual(JDefinedClass class1, JDefinedClass class2) {
        if (!class1.name().equals(class2.name())) {
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

        if (!implements1.equals(implements2)) {
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

            if (!field1.type().fullName().equals(field2.type().fullName())) {
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

        if (!map1.keySet().equals(map2.keySet())) {
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
            if (!stringValue1.equals(stringValue2)) {
                return false;
            }
        } else if (value1 instanceof JAnnotationClassValue) {
            var type1 = ((JAnnotationClassValue) value1).type();
            var type2 = ((JAnnotationClassValue) value2).type();
            if (!type1.fullName().equals(type2.fullName())) {
                return false;
            }

            var value1Value = ((JAnnotationClassValue) value1).value();
            var value2Value = ((JAnnotationClassValue) value2).value();
            if (!value1Value.equals(value2Value)) {
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
