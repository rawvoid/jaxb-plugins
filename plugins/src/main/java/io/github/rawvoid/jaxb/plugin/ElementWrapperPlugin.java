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

import com.sun.codemodel.JAnnotationUse;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.*;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import java.util.*;

import static io.github.rawvoid.jaxb.utils.ModelUtils.removeClass;

/**
 * Flattens single-collection wrapper types into {@code List} properties with
 * {@link XmlElementWrapper}.
 * <p>
 * Structural rewrites happen in {@link #postProcessModel(Model, ErrorHandler)} so BeanGenerator
 * emits collection fields and item {@code @XmlElement} annotations. {@link #run} only adds
 * {@link XmlElementWrapper}, which XJC never generates ({@code CElementPropertyInfo#getXmlName()}
 * always returns null).
 * </p>
 * <p>
 * Scope: a non-collection element property whose type is a wrapper class (exactly one
 * non-value-list collection element property, no base class). Nested “list of wrappers” is
 * not recursively unwrapped.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xelement-wrapper")
public class ElementWrapperPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(ElementWrapperPlugin.class);

    @Option(name = "remove-wrapper-class", defaultValue = "true", description = "Whether to remove the wrapper class")
    Boolean removeWrapperClass;

    /** Captured in postProcessModel; used in run to place {@link XmlElementWrapper}. */
    private final List<FlattenedField> flattenedFields = new ArrayList<>();

    private record FlattenedField(
        CClassInfo owner,
        String propertyName,
        QName wrapperName,
        boolean wrapperNillable,
        boolean wrapperRequired
    ) {
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        flattenedFields.clear();

        var wrappers = findWrapperClasses(model);
        var usedWrappers = new LinkedHashSet<CClassInfo>();

        for (var owner : List.copyOf(model.beans().values())) {
            flattenOwner(owner, wrappers, usedWrappers);
        }

        if (Boolean.TRUE.equals(removeWrapperClass)) {
            removeUnusedWrappers(model, usedWrappers);
        }

        if (!flattenedFields.isEmpty()) {
            log.info("Flattened {} wrapper field(s)", flattenedFields.size());
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        for (var flattened : flattenedFields) {
            annotateXmlElementWrapper(outline, flattened);
        }
        return true;
    }

    private Set<CClassInfo> findWrapperClasses(Model model) {
        var result = new LinkedHashSet<CClassInfo>();
        for (var classInfo : model.beans().values()) {
            if (isWrapperClass(classInfo)) {
                result.add(classInfo);
            }
        }
        return result;
    }

    /**
     * A wrapper is a pure collection shell: one element-collection property, no base class,
     * no attribute wildcard. Broader shapes must not be flattened — they are not equivalent
     * to {@code @XmlElementWrapper}.
     * <p>
     * Having subclasses does <strong>not</strong> disqualify the type: the shell shape is
     * about this class's own properties. Subclasses only affect whether we may
     * <em>delete</em> the class after flattening (see {@link #isReferenced}).
     * </p>
     */
    private boolean isWrapperClass(CClassInfo classInfo) {
        // Attribute wildcard is not represented as a CPropertyInfo entry but still contributes
        // structure (see CClassInfo#hasAttributeWildcard / BeanGenerator attribute wildcard field).
        if (classInfo.isAbstract()
            || classInfo.hasAttributeWildcard()
            || classInfo.getBaseClass() != null
            || classInfo.getRefBaseClass() != null
            || classInfo.getProperties().size() != 1) {
            return false;
        }

        var property = classInfo.getProperties().getFirst();
        if (!property.isCollection() || property.ref().isEmpty()) {
            return false;
        }

        // Classic XSD wrappers are element collections; value lists map to @XmlList, not wrapper.
        return property instanceof CElementPropertyInfo elementProperty
            && !elementProperty.isValueList();
    }

    private void flattenOwner(CClassInfo owner, Set<CClassInfo> wrappers, Set<CClassInfo> usedWrappers) {
        var properties = owner.getProperties();
        for (var i = 0; i < properties.size(); i++) {
            var outer = properties.get(i);
            var wrapper = resolveWrapperTarget(outer, wrappers);
            if (wrapper == null || owner == wrapper) {
                continue;
            }

            var inner = wrapper.getProperties().getFirst();
            if (!(inner instanceof CElementPropertyInfo innerElement)) {
                continue;
            }

            var replacement = createFlattenedElementProperty((CElementPropertyInfo) outer, innerElement);
            if (!replaceProperty(owner, i, outer, replacement)) {
                continue;
            }

            usedWrappers.add(wrapper);
            recordFlattenedField(owner, (CElementPropertyInfo) outer, replacement);
            log.debug("Flattened {}.{} (wrapper {})",
                owner.fullName(), outer.getName(false), wrapper.fullName());
        }
    }

    /**
     * Non-collection element property whose single type is a known wrapper class.
     */
    private CClassInfo resolveWrapperTarget(CPropertyInfo outer, Set<CClassInfo> wrappers) {
        if (outer.isCollection() || !(outer instanceof CElementPropertyInfo elementProperty)) {
            return null;
        }
        if (elementProperty.getAdapter() != null || elementProperty.getTypes().size() != 1) {
            return null;
        }
        var target = elementProperty.getTypes().getFirst().getTarget();
        if (!(target instanceof CClassInfo classInfo) || !wrappers.contains(classInfo)) {
            return null;
        }
        return classInfo;
    }

    private CElementPropertyInfo createFlattenedElementProperty(
        CElementPropertyInfo outer,
        CElementPropertyInfo inner
    ) {
        var flattened = new CElementPropertyInfo(
            outer.getName(true),
            CElementPropertyInfo.CollectionMode.REPEATED_ELEMENT,
            inner.id(),
            inner.getExpectedMimeType(),
            outer.getSchemaComponent(),
            new CCustomizations(outer.getCustomizations()),
            outer.getLocator(),
            false
        );
        flattened.setName(true, outer.getName(true));
        flattened.setName(false, outer.getName(false));

        for (var typeRef : inner.getTypes()) {
            flattened.getTypes().add(new CTypeRef(
                typeRef.getTarget(),
                typeRef.getTagName(),
                typeRef.getTypeName(),
                typeRef.isNillable(),
                typeRef.defaultValue
            ));
        }

        if (inner.getAdapter() != null) {
            flattened.setAdapter(inner.getAdapter());
        }
        if (outer.realization != null) {
            flattened.realization = outer.realization;
        } else if (inner.realization != null) {
            flattened.realization = inner.realization;
        }
        flattened.javadoc = outer.javadoc;
        flattened.inlineBinaryData = outer.inlineBinaryData || inner.inlineBinaryData;
        return flattened;
    }

    /**
     * Replaces {@code outer} with {@code replacement} while preserving propOrder.
     * <p>
     * Uses {@link CClassInfo#addProperty} so official {@code setParent} (including
     * customization linkage) runs, then moves the appended property back to
     * {@code index}. Existing siblings are left untouched — they already have a parent
     * and must not go through {@code addProperty} again.
     * </p>
     * <p>
     * {@link CClassInfo#addProperty} silently no-ops when {@code ref()} is empty. We therefore
     * append first and verify the tail is {@code replacement} <em>before</em> removing
     * {@code outer}, so a failed append never drops the original property.
     * </p>
     */
    private boolean replaceProperty(
        CClassInfo owner,
        int index,
        CPropertyInfo outer,
        CElementPropertyInfo replacement
    ) {
        if (replacement.ref().isEmpty()) {
            log.warn("Skip flattening {}.{}: replacement has no type refs",
                owner.fullName(), outer.getName(false));
            return false;
        }

        var properties = owner.getProperties();
        // 1) Append + setParent. Outer remains at `index` until this is confirmed.
        int sizeBefore = properties.size();
        owner.addProperty(replacement);
        if (properties.size() != sizeBefore + 1 || properties.getLast() != replacement) {
            // addProperty no-op'd — model unchanged.
            log.warn("Skip flattening {}.{}: addProperty did not append the replacement",
                owner.fullName(), outer.getName(false));
            return false;
        }

        // 2) Drop outer, then move replacement from the tail into its slot.
        //    Example: [name, items, count, replacement]
        //      remove(1) → [name, count, replacement]
        //      removeLast + add(1, …) → [name, replacement, count]
        //    Because we only remove at `index` (< sizeBefore), the tail stays `replacement`.
        properties.remove(index);
        properties.add(index, properties.removeLast());
        return true;
    }

    private void recordFlattenedField(
        CClassInfo owner,
        CElementPropertyInfo outer,
        CElementPropertyInfo replacement
    ) {
        var typeRef = outer.getTypes().getFirst();
        flattenedFields.add(new FlattenedField(
            owner,
            replacement.getName(false),
            typeRef.getTagName(),
            typeRef.isNillable(),
            outer.isRequired()
        ));
    }

    private void removeUnusedWrappers(Model model, Set<CClassInfo> wrappers) {
        var removed = new ArrayList<String>();
        var kept = new ArrayList<String>();

        for (var wrapper : wrappers) {
            if (!model.beans().containsValue(wrapper)) {
                continue;
            }
            if (isReferenced(model, wrapper)) {
                kept.add(wrapper.fullName());
                continue;
            }
            if (removeClass(model, wrapper)) {
                removed.add(wrapper.fullName());
            } else {
                kept.add(wrapper.fullName());
            }
        }

        if (!removed.isEmpty()) {
            log.info("Removed wrapper classes:\n    {}", String.join("\n    ", removed));
        }
        if (!kept.isEmpty()) {
            log.info("Skipped removing wrapper classes:\n    {}", String.join("\n    ", kept));
        }
    }

    /**
     * Returns true if {@code target} is still reachable from other model members after
     * flattening. Covers inheritance, nesting, property type refs, and element decls.
     * <p>
     * Intentionally does <strong>not</strong> consult {@link Model#typeUses()}: that map is a
     * registry of named schema types (the type's own entry always points at itself), not a
     * reverse-reference index. Using it would prevent every named wrapper from ever being
     * removed.
     * </p>
     */
    private boolean isReferenced(Model model, CClassInfo target) {
        // Subclasses still need the base bean to be generated; never remove a type that is extended.
        if (target.hasSubClasses()) {
            return true;
        }

        for (var bean : model.beans().values()) {
            if (bean == target) {
                continue;
            }
            // Inheritance (subclass → this) and nested class parent link.
            if (bean.getBaseClass() == target || bean.parent() == target) {
                return true;
            }
            if (propertyRefsTarget(bean.getProperties(), target)) {
                return true;
            }
        }

        for (var elementInfo : model.getAllElements()) {
            // Global/local element whose content type is this class, or whose property refs it.
            if (elementInfo.getContentType() == target) {
                return true;
            }
            var property = elementInfo.getProperty();
            if (property != null && propertyRefsTarget(List.of(property), target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean propertyRefsTarget(Iterable<CPropertyInfo> properties, CClassInfo target) {
        for (var property : properties) {
            for (var ref : property.ref()) {
                if (ref == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private void annotateXmlElementWrapper(Outline outline, FlattenedField flattened) {
        var classOutline = outline.getClazz(flattened.owner());
        var field = classOutline.implClass.fields().get(flattened.propertyName());
        if (field == null) {
            log.warn("Could not find field {} on {}",
                flattened.propertyName(), flattened.owner().fullName());
            return;
        }

        var alreadyPresent = field.annotations().stream()
            .anyMatch(a -> a.getAnnotationClass().fullName().equals(XmlElementWrapper.class.getName()));
        if (alreadyPresent) {
            return;
        }

        var annotation = field.annotate(XmlElementWrapper.class);
        applyXmlElementWrapperParams(annotation, flattened);
    }

    /**
     * Maps the original outer element QName onto {@link XmlElementWrapper}.
     * <p>
     * Strategy (aligned with JAXB annotation defaulting):
     * <ul>
     *   <li><b>name</b> — omit when equal to the Java field name so the runtime uses
     *       {@code ##default} (property name). Set only when the schema local name differs.</li>
     *   <li><b>namespace</b> — set whenever the outer element has a non-empty namespace URI.
     *       This is independent of the name check: the local name may match the field name
     *       while the element still lives in a namespace that package-info defaulting would
     *       not recover (e.g. a different NS than {@code @XmlSchema.namespace}).</li>
     *   <li><b>nillable</b> / <b>required</b> — only when true on the original outer element.</li>
     * </ul>
     * Empty namespace URI is left defaulted (unqualified / form-default behaviour).
     * </p>
     */
    private void applyXmlElementWrapperParams(JAnnotationUse annotation, FlattenedField flattened) {
        var wrapperName = flattened.wrapperName();
        if (wrapperName == null) {
            return;
        }

        var localName = wrapperName.getLocalPart();
        if (localName != null && !localName.isEmpty() && !localName.equals(flattened.propertyName())) {
            annotation.param("name", localName);
        }

        var namespace = wrapperName.getNamespaceURI();
        if (namespace != null && !namespace.isEmpty()) {
            annotation.param("namespace", namespace);
        }

        if (flattened.wrapperNillable()) {
            annotation.param("nillable", true);
        }
        if (flattened.wrapperRequired()) {
            annotation.param("required", true);
        }
    }
}

