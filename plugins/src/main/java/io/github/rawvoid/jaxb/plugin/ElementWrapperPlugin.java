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

import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JAnnotationUse;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.*;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import java.lang.annotation.Annotation;
import java.util.*;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CPROPERTYINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.removeClass;
import static io.github.rawvoid.jaxb.utils.OutlineUtils.fixJAXBDebugClass;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * JAXB plugin that simplifies element wrappers in the generated code.
 * <p>
 * In {@link #postProcessModel(Model, ErrorHandler)}, this plugin identifies wrapper classes
 * (classes with a single collection property) and rewrites properties that reference those
 * classes so they become collection properties of the wrapped element type.
 * </p>
 * <p>
 * XJC never emits {@link XmlElementWrapper} from the model (see
 * {@code CElementPropertyInfo#getXmlName()} / {@code AbstractField#annotateElement}), so
 * {@link #run(Outline, Options, ErrorHandler)} only adds that annotation using metadata
 * captured during model processing.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xelement-wrapper")
public class ElementWrapperPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(ElementWrapperPlugin.class);
    private static final int MAX_FLATTEN_PASSES = 16;

    @Option(name = "remove-wrapper-class", defaultValue = "true", description = "Whether to remove the wrapper class")
    Boolean removeWrapperClass;

    /**
     * Properties flattened in {@link #postProcessModel}; consumed by {@link #run}.
     */
    private final List<FlattenedField> flattenedFields = new ArrayList<>();

    /**
     * Metadata needed to emit {@link XmlElementWrapper} after code generation.
     *
     * @param ownerFullName    fully-qualified name of the owning generated class
     * @param propertyName     private property / field name
     * @param wrapperName      original outer element QName (wrapper element)
     * @param wrapperNillable  whether the outer element was nillable
     * @param wrapperRequired  whether the outer element was required
     */
    private record FlattenedField(
        String ownerFullName,
        String propertyName,
        QName wrapperName,
        boolean wrapperNillable,
        boolean wrapperRequired
    ) {
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        flattenedFields.clear();

        var allWrappers = new LinkedHashSet<CClassInfo>();
        var changed = true;
        var pass = 0;
        while (changed && pass++ < MAX_FLATTEN_PASSES) {
            changed = false;
            var wrappers = findWrapperClasses(model);
            allWrappers.addAll(wrappers.values());

            // Snapshot beans to avoid concurrent modification while we rewrite properties.
            for (var owner : List.copyOf(model.beans().values())) {
                if (flattenOwner(owner, wrappers)) {
                    changed = true;
                }
            }
        }

        if (Boolean.TRUE.equals(removeWrapperClass)) {
            removeUnusedWrappers(model, allWrappers);
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

        if (opt.debugMode) {
            fixJAXBDebugClass(outline);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // postProcessModel: discovery
    // -------------------------------------------------------------------------

    /**
     * Finds wrapper classes in the model.
     * <p>
     * A wrapper class declares exactly one collection property and does not inherit
     * additional structure from a base class.
     * </p>
     */
    private Map<String, CClassInfo> findWrapperClasses(Model model) {
        var result = new LinkedHashMap<String, CClassInfo>();
        for (var classInfo : model.beans().values()) {
            if (isWrapperClass(classInfo)) {
                result.put(classInfo.fullName(), classInfo);
            }
        }
        return result;
    }

    /**
     * Returns whether {@code classInfo} can be treated as an element wrapper.
     */
    private boolean isWrapperClass(CClassInfo classInfo) {
        if (classInfo.isAbstract()) {
            return false;
        }
        if (classInfo.getBaseClass() != null || classInfo.getRefBaseClass() != null) {
            return false;
        }
        if (classInfo.getProperties().size() != 1) {
            return false;
        }

        var property = classInfo.getProperties().getFirst();
        if (!property.isCollection()) {
            return false;
        }

        return switch (property) {
            case CElementPropertyInfo elementProperty ->
                !elementProperty.isValueList() && isSafeCollectionProperty(elementProperty);
            case CReferencePropertyInfo referenceProperty ->
                !referenceProperty.isMixed()
                    && referenceProperty.getWildcard() == null
                    && isSafeCollectionProperty(referenceProperty);
            default -> false;
        };
    }

    private boolean isSafeCollectionProperty(CPropertyInfo property) {
        // Heterogeneous or empty refs are too ambiguous for automatic flattening.
        return property.ref() != null && !property.ref().isEmpty();
    }

    // -------------------------------------------------------------------------
    // postProcessModel: transform
    // -------------------------------------------------------------------------

    /**
     * Rewrites properties on {@code owner} that reference known wrapper classes.
     *
     * @return {@code true} if any property was replaced
     */
    private boolean flattenOwner(CClassInfo owner, Map<String, CClassInfo> wrappers) {
        var properties = owner.getProperties();
        var changed = false;

        // Index-based loop: list is mutated in place.
        for (var i = 0; i < properties.size(); i++) {
            var outer = properties.get(i);
            var wrapper = resolveWrapperTarget(outer, wrappers);
            if (wrapper == null) {
                continue;
            }
            // Never flatten a wrapper class into itself while it is still a candidate.
            if (owner == wrapper) {
                continue;
            }

            var replacement = createFlattenedProperty(outer, wrapper);
            if (replacement == null) {
                continue;
            }

            setFieldValue(CPROPERTYINFO_PARENT_FIELD, replacement, owner);
            properties.set(i, replacement);
            recordFlattenedField(owner, outer, replacement);
            log.debug("Flattened {}.{} (wrapper {})",
                owner.fullName(), outer.getName(false), wrapper.fullName());
            changed = true;
        }
        return changed;
    }

    /**
     * If {@code outer} is a non-collection element property whose single type is a wrapper
     * class, returns that wrapper; otherwise {@code null}.
     */
    private CClassInfo resolveWrapperTarget(CPropertyInfo outer, Map<String, CClassInfo> wrappers) {
        if (outer.isCollection()) {
            return null;
        }
        if (!(outer instanceof CElementPropertyInfo elementProperty)) {
            // First version: only flatten CElementPropertyInfo outers.
            return null;
        }
        if (elementProperty.getAdapter() != null) {
            // Adapter is bound to the wrapper type; changing the property would break it.
            return null;
        }
        if (elementProperty.getTypes().size() != 1) {
            return null;
        }

        var target = elementProperty.getTypes().getFirst().getTarget();
        if (!(target instanceof CClassInfo classInfo)) {
            return null;
        }
        return wrappers.get(classInfo.fullName());
    }

    /**
     * Builds a new collection property that replaces {@code outer}, using the single
     * collection property of {@code wrapper} as the type/annotation source.
     */
    private CPropertyInfo createFlattenedProperty(CPropertyInfo outer, CClassInfo wrapper) {
        var inner = wrapper.getProperties().getFirst();
        return switch (inner) {
            case CElementPropertyInfo innerElement ->
                createFlattenedElementProperty((CElementPropertyInfo) outer, innerElement);
            case CReferencePropertyInfo innerReference ->
                createFlattenedReferenceProperty((CElementPropertyInfo) outer, innerReference);
            default -> null;
        };
    }

    private CElementPropertyInfo createFlattenedElementProperty(
        CElementPropertyInfo outer,
        CElementPropertyInfo inner
    ) {
        var collectionMode = inner.isValueList()
            ? CElementPropertyInfo.CollectionMode.REPEATED_VALUE
            : CElementPropertyInfo.CollectionMode.REPEATED_ELEMENT;

        var flattened = new CElementPropertyInfo(
            outer.getName(true),
            collectionMode,
            inner.id(),
            inner.getExpectedMimeType(),
            outer.getSchemaComponent(),
            outer.getCustomizations(),
            outer.getLocator(),
            false
        );
        preservePropertyNames(flattened, outer);

        for (var typeRef : inner.getTypes()) {
            flattened.getTypes().add(copyTypeRef(typeRef));
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

    private CReferencePropertyInfo createFlattenedReferenceProperty(
        CElementPropertyInfo outer,
        CReferencePropertyInfo inner
    ) {
        var flattened = new CReferencePropertyInfo(
            outer.getName(true),
            true,
            false,
            inner.isMixed(),
            outer.getSchemaComponent(),
            outer.getCustomizations(),
            outer.getLocator(),
            inner.isDummy(),
            inner.isContent(),
            inner.isMixedExtendedCust()
        );
        preservePropertyNames(flattened, outer);

        flattened.getElements().addAll(inner.getElements());
        if (inner.getWildcard() != null) {
            flattened.setWildcard(inner.getWildcard());
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

    private void preservePropertyNames(CPropertyInfo target, CPropertyInfo source) {
        target.setName(true, source.getName(true));
        target.setName(false, source.getName(false));
    }

    private CTypeRef copyTypeRef(CTypeRef source) {
        return new CTypeRef(
            source.getTarget(),
            source.getTagName(),
            source.getTypeName(),
            source.isNillable(),
            source.defaultValue
        );
    }

    private void recordFlattenedField(CClassInfo owner, CPropertyInfo outer, CPropertyInfo replacement) {
        var wrapperName = extractWrapperElementName(outer);
        var nillable = false;
        var required = false;
        if (outer instanceof CElementPropertyInfo elementProperty && !elementProperty.getTypes().isEmpty()) {
            var typeRef = elementProperty.getTypes().getFirst();
            nillable = typeRef.isNillable();
            required = elementProperty.isRequired();
        }

        flattenedFields.add(new FlattenedField(
            owner.fullName(),
            replacement.getName(false),
            wrapperName,
            nillable,
            required
        ));
    }

    private QName extractWrapperElementName(CPropertyInfo outer) {
        if (outer instanceof CElementPropertyInfo elementProperty && !elementProperty.getTypes().isEmpty()) {
            return elementProperty.getTypes().getFirst().getTagName();
        }
        return new QName(outer.getName(false));
    }

    // -------------------------------------------------------------------------
    // postProcessModel: cleanup
    // -------------------------------------------------------------------------

    private void removeUnusedWrappers(Model model, Set<CClassInfo> wrappers) {
        var removed = new ArrayList<String>();
        var kept = new ArrayList<String>();

        for (var wrapper : wrappers) {
            // Still present? Multi-pass may have removed nested wrappers already.
            if (!model.beans().containsValue(wrapper)) {
                continue;
            }
            if (hasNestedClasses(model, wrapper)) {
                kept.add(wrapper.fullName() + " (has nested classes)");
                continue;
            }
            if (isReferenced(model, wrapper)) {
                kept.add(wrapper.fullName() + " (still referenced)");
                continue;
            }
            if (removeClass(model, wrapper)) {
                removed.add(wrapper.fullName());
            } else {
                kept.add(wrapper.fullName() + " (remove failed)");
            }
        }

        if (!removed.isEmpty()) {
            log.info("Removed wrapper classes:\n    {}", String.join("\n    ", removed));
        }
        if (!kept.isEmpty()) {
            log.info("Skipped removing wrapper classes:\n    {}", String.join("\n    ", kept));
        }
    }

    private boolean hasNestedClasses(Model model, CClassInfo parent) {
        for (var bean : model.beans().values()) {
            if (bean != parent && bean.parent() == parent) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if any remaining model member still references {@code target}.
     */
    private boolean isReferenced(Model model, CClassInfo target) {
        for (var bean : model.beans().values()) {
            if (bean == target) {
                continue;
            }
            if (bean.getBaseClass() == target) {
                return true;
            }
            for (var property : bean.getProperties()) {
                for (var ref : property.ref()) {
                    if (ref == target) {
                        return true;
                    }
                }
            }
        }

        for (var elementInfo : model.getAllElements()) {
            if (elementInfo.getContentType() == target) {
                return true;
            }
            var property = elementInfo.getProperty();
            if (property != null) {
                for (var ref : property.ref()) {
                    if (ref == target) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // run: annotations
    // -------------------------------------------------------------------------

    private void annotateXmlElementWrapper(Outline outline, FlattenedField flattened) {
        var classOutline = findClassOutline(outline, flattened.ownerFullName());
        if (classOutline == null) {
            log.warn("Could not find generated class for flattened field {}.{}",
                flattened.ownerFullName(), flattened.propertyName());
            return;
        }

        var field = classOutline.implClass.fields().get(flattened.propertyName());
        if (field == null) {
            log.warn("Could not find field {} on {}",
                flattened.propertyName(), flattened.ownerFullName());
            return;
        }
        if (getAnnotation(field, XmlElementWrapper.class) != null) {
            return;
        }

        var annotation = field.annotate(XmlElementWrapper.class);
        applyWrapperParams(annotation, flattened);
    }

    private void applyWrapperParams(JAnnotationUse annotation, FlattenedField flattened) {
        var wrapperName = flattened.wrapperName();
        if (wrapperName == null) {
            return;
        }

        var localName = wrapperName.getLocalPart();
        // Omit name when it matches the field name so annotation defaults apply (##default).
        if (localName != null && !localName.isEmpty() && !localName.equals(flattened.propertyName())) {
            annotation.param("name", localName);
        }

        // Namespace is left to annotation defaults in the common elementFormDefault=qualified
        // case. Explicit namespace is only needed when the local name was customized away from
        // the field name and a non-empty namespace URI is present on the original element.
        var namespace = wrapperName.getNamespaceURI();
        if (localName != null
            && !localName.equals(flattened.propertyName())
            && namespace != null
            && !namespace.isEmpty()) {
            annotation.param("namespace", namespace);
        }

        if (flattened.wrapperNillable()) {
            annotation.param("nillable", true);
        }
        if (flattened.wrapperRequired()) {
            annotation.param("required", true);
        }
    }

    private ClassOutline findClassOutline(Outline outline, String fullName) {
        for (var classOutline : outline.getClasses()) {
            if (classOutline.target.fullName().equals(fullName)
                || classOutline.implClass.fullName().equals(fullName)) {
                return classOutline;
            }
        }
        return null;
    }

    private <T extends Annotation> JAnnotationUse getAnnotation(JAnnotatable annotatable, Class<T> clazz) {
        return annotatable.annotations().stream()
            .filter(anno -> anno.getAnnotationClass().fullName().equals(clazz.getName()))
            .findFirst()
            .orElse(null);
    }
}
