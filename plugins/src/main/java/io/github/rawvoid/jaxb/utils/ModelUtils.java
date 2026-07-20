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

import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.model.*;
import jakarta.activation.MimeType;
import org.glassfish.jaxb.core.v2.model.core.ID;

import javax.xml.namespace.QName;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.rawvoid.jaxb.utils.ReflectUtils.*;

/**
 * Utility class for working with JAXB model elements.
 * Provides helper methods to manipulate and query JAXB model structures,
 * including class hierarchies, property references, and model transformations.
 *
 * @author Rawvoid
 */
public final class ModelUtils {

    private ModelUtils() {
    }

    /**
     * Reflection field for accessing the abstract flag in CElement classes.
     */
    public static final Field ABSTRACT_FIELD = getField("com.sun.tools.xjc.model.AbstractCElement", "isAbstract");

    /**
     * Reflection field for accessing the base class of a CClassInfo.
     */
    public static final Field BASE_CLASS_FIELD = getField(CClassInfo.class, "baseClass");

    /**
     * Reflection field for accessing the first subclass of a CClassInfo.
     */
    public static final Field FIRST_SUBCLASS_FIELD = getField(CClassInfo.class, "firstSubclass");

    /**
     * Reflection field for accessing the next sibling in the subclass linked list.
     */
    public static final Field NEXT_SIBLING_FIELD = getField(CClassInfo.class, "nextSibling");

    /**
     * Reflection field for accessing the parent element of a CClassInfo.
     */
    public static final Field CCLASSINFO_PARENT_FIELD = getField(CClassInfo.class, "parent");

    /**
     * Reflection field for accessing the short name of a {@link CClassInfo}.
     */
    public static final Field CCLASSINFO_SHORTNAME_FIELD = getField(CClassInfo.class, "shortName");

    /**
     * Reflection field for accessing the parent container of a {@link CEnumLeafInfo}.
     */
    public static final Field CENUMLEAFINFO_PARENT_FIELD = getField(CEnumLeafInfo.class, "parent");

    /**
     * Reflection field for accessing the short name of a {@link CEnumLeafInfo}.
     */
    public static final Field CENUMLEAFINFO_SHORTNAME_FIELD = getField(CEnumLeafInfo.class, "shortName");

    /**
     * Reflection field for accessing the parent of CElementInfo.
     */
    public static final Field CELEMENTINFO_PARENT_FIELD = getField(CElementInfo.class, "parent");

    /**
     * Reflection field for accessing the generated class name of a {@link CElementInfo}.
     */
    public static final Field CELEMENTINFO_CLASSNAME_FIELD = getField(CElementInfo.class, "className");

    /**
     * Reflection field for accessing the element mappings in Model.
     */
    public static final Field MODEL_ELEMENT_MAPPINGS_FIELD = getField(Model.class, "elementMappings");

    /**
     * Reflection field for accessing the parent of CPropertyInfo.
     */
    public static final Field CPROPERTYINFO_PARENT_FIELD = getField(CPropertyInfo.class, "parent");

    /**
     * Reflection field for accessing the type field in CSingleTypePropertyInfo.
     */
    public static final Field TYPE_FIELD = getField("com.sun.tools.xjc.model.CSingleTypePropertyInfo", "type");

    /**
     * Reflection field for accessing the schema type field in CSingleTypePropertyInfo.
     */
    public static final Field SCHEMA_TYPE_FIELD = getField("com.sun.tools.xjc.model.CSingleTypePropertyInfo", "schemaType");

    /**
     * Reflection method for accessing the needsExplicitTypeName method in CPropertyInfo.
     */
    public static final Method NEEDS_EXPLICIT_TYPE_NAME_METHOD = getMethod(CPropertyInfo.class, "needsExplicitTypeName", TypeUse.class, QName.class);

    /**
     * Reflection field for accessing the types list in CElementPropertyInfo.
     */
    public static final Field ELEMENT_TYPES_FIELD = getField(CElementPropertyInfo.class, "types");

    /**
     * Reflection field for accessing the elements set in CReferencePropertyInfo.
     */
    public static final Field REFERENCE_ELEMENTS_FIELD = getField(CReferencePropertyInfo.class, "elements");

    /**
     * Reflection constructor for creating TypeUseImpl instances.
     */
    public static final Constructor<?> TYPE_USE_CONSTRUCTOR = getConstructor("com.sun.tools.xjc.model.TypeUseImpl",
        CNonElement.class, boolean.class, ID.class, MimeType.class, CAdapter.class);

    /**
     * Groups all classes in the model by their owning package.
     *
     * @param model the JAXB model containing all beans
     * @return a map where keys are packages and values are lists of classes in each package
     */
    public static Map<JPackage, List<CClassInfo>> groupClassesByPackage(Model model) {
        // Use stream API to group all beans by their owner package for easier package-level processing
        return model.beans().values().stream()
            .collect(Collectors.groupingBy((CClassInfo::getOwnerPackage)));
    }

    /**
     * Whether two non-abstract beans would emit the same ObjectFactory value-factory method.
     * Uses XJC's own {@link CClassInfo#getSqueezedName()} (package-local uniqueness).
     */
    public static boolean hasObjectFactorySqueezedCollision(Model model) {
        return !objectFactorySqueezedCollisions(model).isEmpty();
    }

    /**
     * Non-abstract bean groups that share {@code package + getSqueezedName()}.
     * Empty when ObjectFactory would not see a name clash.
     */
    public static List<List<CClassInfo>> objectFactorySqueezedCollisions(Model model) {
        Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
        for (var bean : model.beans().values()) {
            if (bean.isAbstract()) {
                continue;
            }
            // Package name + squeezed name is what ObjectFactory uses for createXxx uniqueness.
            var key = bean.getOwnerPackage().name() + '\0' + bean.getSqueezedName();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(bean);
        }
        var collisions = new ArrayList<List<CClassInfo>>();
        for (var group : byKey.values()) {
            if (group.size() > 1) {
                collisions.add(group);
            }
        }
        return collisions;
    }

    /**
     * Finds all nested classes of a given parent class from the model.
     *
     * @param model  the JAXB model containing all beans
     * @param parent the parent class to search for nested classes
     * @return a list of all classes that are nested within the specified parent
     */
    public static List<CClassInfo> getAllNestedClasses(Model model, CClassInfo parent) {
        // Delegate to the collection-based version to avoid code duplication
        return getAllNestedClasses(model.beans().values(), parent);
    }

    /**
     * Filters a collection of classes to find all nested classes of a given parent.
     *
     * @param classes the collection of classes to search through
     * @param parent  the parent class to search for nested classes
     * @return a list of all classes that are nested within the specified parent
     */
    public static List<CClassInfo> getAllNestedClasses(Collection<CClassInfo> classes, CClassInfo parent) {
        return classes.stream().filter(targetClass -> {
            // Traverse up the parent hierarchy to check if the target parent exists in the ancestry
            var classInfoParent = targetClass.parent();
            while (classInfoParent != null) {
                if (classInfoParent == parent) {
                    // Found the target parent in the hierarchy, include this class
                    return true;
                } else if (classInfoParent instanceof CClassInfo classInfo) {
                    // Continue traversing up the parent chain for CClassInfo types
                    classInfoParent = classInfo.parent();
                } else {
                    // Reached a non-CClassInfo parent, stop traversal
                    classInfoParent = null;
                }
            }
            // Target parent not found in the hierarchy, exclude this class
            return false;
        }).toList();
    }

    /**
     * Removes a class from the model and unlinks it from its base class hierarchy.
     * <p>
     * <strong>Warning:</strong> This method will re-parent all direct subclasses to the base class
     * of the class being removed. If the class has no base class, subclasses will become root classes.
     * <p>
     * Before removal, this method checks for external references to the class. If any external
     * references exist (from properties, nested classes, element declarations, etc.), an exception
     * is thrown to prevent leaving dangling references in the model.
     *
     * @param model     the JAXB model containing the class
     * @param classInfo the class to be removed from the model
     * @throws IllegalStateException if external references exist or the unlink operation fails
     */
    public static boolean removeClass(Model model, CClassInfo classInfo) {
        // Get the base class (parent in inheritance hierarchy)
        var baseClass = classInfo.getBaseClass();

        // First, handle this class's own unlinking from its base class
        if (baseClass != null) {
            unlinkSubClass(baseClass, classInfo);
        }

        // Then, handle unlinking of all subclasses of this class
        if (classInfo.hasSubClasses()) {
            unlinkSubClass(classInfo);
        }

        // Remove from model
        return model.beans().values().removeIf(c -> c == classInfo);
    }

    /**
     * Replaces all references to one class with references to another class throughout the model.
     * Updates base class references, property types, and element references.
     * <p>
     * @param model the JAXB model to update
     * @param from  the class to be replaced
     * @param to    the class to replace with
     * @throws IllegalArgumentException if from or to is null
     */
    public static void replaceClassReferences(Model model, CClassInfo from, CClassInfo to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Neither 'from' nor 'to' can be null");
        }

        if (from == to) {
            return; // Nothing to do
        }

        // 1. Update base class references
        model.beans().values().forEach(bean -> {
            if (bean.getBaseClass() == from) {
                replaceBaseClass(bean, from, to);
            }
        });

        // 2. Update CClassInfo.parent for nested classes
        model.beans().values().forEach(bean -> {
            if (bean.parent() == from) {
                setFieldValue(CCLASSINFO_PARENT_FIELD, bean, to);
            }
        });

        // 3. Update property references
        model.beans().values().forEach(bean ->
            bean.getProperties().forEach(property ->
                replacePropertyReference(property, from, to)));

        // 4. Update element declarations
        var elementParentChanged = false;
        for (var elementInfo : model.getAllElements()) {
            if (getFieldValue(CELEMENTINFO_PARENT_FIELD, elementInfo) == from) {
                setFieldValue(CELEMENTINFO_PARENT_FIELD, elementInfo, to);
                elementParentChanged = true;
            }

            CPropertyInfo property = elementInfo.getProperty();
            if (property != null) {
                replacePropertyReference(property, from, to);
            }
        }

        // CElementInfo.parent changes scope, so elementMappings keys must be rebuilt.
        if (elementParentChanged) {
            rebuildElementMappings(model);
        }
    }

    /**
     * Rebuilds Model.elementMappings to keep keys in sync with CElementInfo.getScope().
     */
    @SuppressWarnings("unchecked")
    public static void rebuildElementMappings(Model model) {
        var allElements = new ArrayList<CElementInfo>();
        model.getAllElements().forEach(allElements::add);

        var elementMappings = (Map<Object, Map<QName, CElementInfo>>) getFieldValue(MODEL_ELEMENT_MAPPINGS_FIELD, model);
        elementMappings.clear();

        for (var elementInfo : allElements) {
            var scope = elementInfo.getScope();
            var scopeClass = scope != null ? scope.getClazz() : null;
            var mapping = elementMappings.computeIfAbsent(scopeClass, k -> new LinkedHashMap<>());
            mapping.put(elementInfo.getElementName(), elementInfo);
        }
    }

    /**
     * Removes a {@link CElementInfo} from {@link Model#getAllElements()} / element mappings.
     * Used when a synthetic local element (e.g. nillable reference binding) is rewritten away.
     *
     * @return {@code true} if the element was present and removed
     */
    @SuppressWarnings("unchecked")
    public static boolean removeElementInfo(Model model, CElementInfo elementInfo) {
        if (model == null || elementInfo == null) {
            return false;
        }
        var elementMappings =
            (Map<Object, Map<QName, CElementInfo>>) getFieldValue(MODEL_ELEMENT_MAPPINGS_FIELD, model);
        var scope = elementInfo.getScope();
        var scopeClass = scope != null ? scope.getClazz() : null;
        var mapping = elementMappings.get(scopeClass);
        if (mapping == null) {
            return false;
        }
        return mapping.remove(elementInfo.getElementName()) != null;
    }

    /**
     * Replaces the base class of a subclass with a new base class.
     * Unlinks from the old base class and links to the new one.
     *
     * @param subclass the class whose base class should be changed
     * @param oldBase  the current base class to be replaced
     * @param newBase  the new base class to use
     * @throws IllegalStateException if the replacement operation fails
     */
    public static void replaceBaseClass(CClassInfo subclass, CClassInfo oldBase, CClassInfo newBase) {
        // Validate inputs
        if (oldBase == null || newBase == null) {
            throw new IllegalArgumentException("Neither 'oldBase' nor 'newBase' can be null");
        }

        // Validate subclass relationship using reflection to handle both CClassInfo and CClassRef
        var actualOldBase = (CClassInfo) getFieldValue(BASE_CLASS_FIELD, subclass);
        if (actualOldBase != oldBase) {
            throw new IllegalStateException("subclass " + subclass.shortName + " is not a child of " + oldBase.shortName);
        }

        // Step 1: Unlink from old base
        unlinkSubClass(oldBase, subclass);

        // Step 2: Set new base class
        setFieldValue(BASE_CLASS_FIELD, subclass, newBase);

        // Step 3: Link to new base
        linkSubClass(newBase, subclass);
    }

    /**
     * @param baseClass base class to unlink subclasses from
     * @return true if unlinking was successful
     * @see #unlinkSubClass(CClassInfo, CClassInfo)
     */
    @SuppressWarnings("UnusedReturnValue")
    public static boolean unlinkSubClass(CClassInfo baseClass) {
        var set = new HashSet<Boolean>();
        baseClass.listSubclasses().forEachRemaining(subClass -> set.add(unlinkSubClass(baseClass, subClass)));
        return set.size() == 1 && set.contains(Boolean.TRUE);
    }

    /**
     * Unlinks a subclass from its base class in the subclass linked list.
     *
     * @param baseClass the base class containing the subclass
     * @param subClass  the subclass to unlink
     */
    public static boolean unlinkSubClass(CClassInfo baseClass, CClassInfo subClass) {
        var actualBase = (CClassInfo) getFieldValue(BASE_CLASS_FIELD, subClass);
        if (baseClass != actualBase) {
            throw new IllegalArgumentException("subClass '%s' (base=%s) is not a child of '%s'".formatted(
                subClass.shortName,
                actualBase != null ? actualBase.shortName : "null",
                baseClass.shortName)
            );
        }

        var current = (CClassInfo) getFieldValue(FIRST_SUBCLASS_FIELD, baseClass);
        CClassInfo previous = null;

        var unlinked = false;
        while (current != null) {
            if (current == subClass) {
                var nextSibling = (CClassInfo) getFieldValue(NEXT_SIBLING_FIELD, current);
                if (previous == null) {
                    // Target is the first child
                    setFieldValue(FIRST_SUBCLASS_FIELD, baseClass, nextSibling);
                } else {
                    // Target is in the middle or end
                    setFieldValue(NEXT_SIBLING_FIELD, previous, nextSibling);
                }
                unlinked = true;
                break;
            }
            previous = current;
            current = getFieldValue(NEXT_SIBLING_FIELD, current);
        }

        // Clear nextSibling of the unlinked subclass
        setFieldValue(NEXT_SIBLING_FIELD, subClass, null);
        setFieldValue(BASE_CLASS_FIELD, subClass, null);

        return unlinked;
    }

    /**
     * Links a subclass to a base class by adding it to the head of the subclass linked list.
     *
     * @param baseClass the base class to link the subclass to
     * @param subClass  the subclass to be linked
     * @throws IllegalStateException if subClass is already in the list or has non-null nextSibling
     */
    public static void linkSubClass(CClassInfo baseClass, CClassInfo subClass) {
        // Check for duplicates
        var current = (CClassInfo) getFieldValue(FIRST_SUBCLASS_FIELD, baseClass);
        while (current != null) {
            if (current == subClass) {
                throw new IllegalStateException("subclass " + subClass.shortName + " is already in the list of " + baseClass.shortName);
            }
            current = getFieldValue(NEXT_SIBLING_FIELD, current);
        }

        // Verify subclass.nextSibling is null (should be after unlinking)
        var existingNext = (CClassInfo) getFieldValue(NEXT_SIBLING_FIELD, subClass);
        if (existingNext != null) {
            throw new IllegalStateException("subclass.nextSibling must be null before linking. " +
                "subclass " + subClass.shortName + " has nextSibling " + existingNext.shortName);
        }

        // Prepend to the list
        var first = (CClassInfo) getFieldValue(FIRST_SUBCLASS_FIELD, baseClass);
        setFieldValue(NEXT_SIBLING_FIELD, subClass, first);
        setFieldValue(FIRST_SUBCLASS_FIELD, baseClass, subClass);
    }

    /**
     * Replaces references to a class within a property with references to another class.
     * Handles element properties, reference properties, and single type properties.
     *
     * @param property the property to update
     * @param from     the class reference to be replaced
     * @param to       the new class reference to use
     * @throws IllegalStateException if the property type replacement fails
     */
    public static void replacePropertyReference(CPropertyInfo property, CClassInfo from, CClassInfo to) {
        switch (property) {
            // Handle CElementPropertyInfo: element declarations with type references
            case CElementPropertyInfo elementProperty -> replaceElementPropertyReference(elementProperty, from, to);
            // Handle CReferencePropertyInfo: IDREF/ID type references to elements
            case CReferencePropertyInfo referenceProperty ->
                replaceReferencePropertyReference(referenceProperty, from, to);
            // Handle CSingleTypePropertyInfo subclasses (CValuePropertyInfo, CAttributePropertyInfo)
            // CSingleTypePropertyInfo is package-private, so we check by concrete types
            case CValuePropertyInfo valueProperty -> replaceSingleTypePropertyReference(valueProperty, from, to);
            case CAttributePropertyInfo attributeProperty ->
                replaceSingleTypePropertyReference(attributeProperty, from, to);
            default ->
                throw new IllegalStateException("Unsupported property type: " + property.getClass().getSimpleName());
        }
    }

    /**
     * Replaces references to a class within an element property with references to another class.
     *
     * @param property the element property to update
     * @param from     the class reference to be replaced
     * @param to       the new class reference to use
     */
    public static void replaceElementPropertyReference(CElementPropertyInfo property, CClassInfo from, CClassInfo to) {
        @SuppressWarnings("unchecked")
        var types = (List<CTypeRef>) getFieldValue(ELEMENT_TYPES_FIELD, property);

        for (var i = 0; i < types.size(); i++) {
            var typeRef = types.get(i);
            if (typeRef.getTarget() == from) {
                // Create new CTypeRef pointing to 'to'
                var newTypeRef = new CTypeRef(
                    to,
                    typeRef.getTagName(),
                    typeRef.getTypeName(),
                    typeRef.isNillable(),
                    typeRef.defaultValue
                );
                types.set(i, newTypeRef);
            }
        }
    }

    /**
     * Replaces references to a class within a reference property with references to another class.
     *
     * @param property the reference property to update
     * @param from     the class reference to be replaced
     * @param to       the new class reference to use
     */
    public static void replaceReferencePropertyReference(CReferencePropertyInfo property, CClassInfo from, CClassInfo to) {
        var elements = property.getElements();
        if (!elements.contains(from)) {
            return;
        }

        var replaced = LinkedHashSet
            .<CElement>newLinkedHashSet(elements.size());
        var changed = false;
        for (var element : elements) {
            if (element == from) {
                replaced.add(to);
                changed = true;
            } else {
                replaced.add(element);
            }
        }

        if (changed) {
            elements.clear();
            elements.addAll(replaced);
        }
    }

    /**
     * Replaces references to a class within a single type property with references to another class.
     *
     * @param property the reference property to update
     * @param from     the class reference to be replaced
     * @param to       the new class reference to use
     */
    public static void replaceSingleTypePropertyReference(CPropertyInfo property, CClassInfo from, CClassInfo to) {
        var typeUse = (TypeUse) getFieldValue(TYPE_FIELD, property);
        if (typeUse != null && typeUse.getInfo() == from) {
            var newTypeUse = newInstance(TYPE_USE_CONSTRUCTOR,
                to,
                typeUse.isCollection(),
                typeUse.idUse(),
                typeUse.getExpectedMimeType(),
                typeUse.getAdapterUse()
            );
            setFieldValue(TYPE_FIELD, property, newTypeUse);

            var schemeType = property.getSchemaType();
            if (!(boolean) invokeMethod(NEEDS_EXPLICIT_TYPE_NAME_METHOD, property, newTypeUse, schemeType)) {
                setFieldValue(SCHEMA_TYPE_FIELD, property, null);
            }
        }
    }
}
