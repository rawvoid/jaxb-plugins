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
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSParticle;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlNsForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.rawvoid.jaxb.utils.ModelUtils.removeClass;
import static io.github.rawvoid.jaxb.utils.ModelUtils.removeElementInfo;

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
 * Scope: a non-collection property whose type is a wrapper class (exactly one
 * non-value-list collection element property, no base class). Nested “list of wrappers” is
 * not recursively unwrapped.
 * </p>
 * <p>
 * Nillable optional complex wrappers: XJC binds them as {@link CReferencePropertyInfo} with a
 * local {@link CElementInfo} ({@code JAXBElement&lt;Wrapper&gt;}) because
 * {@code nillable + optional} yields {@code RawTypeSet.Mode.CAN_BE_TYPEREF}, and the default
 * is reference binding ({@code BIProperty#createElementOrReferenceProperty}). This plugin
 * rewrites that shape into a repeated element list with
 * {@code @XmlElementWrapper(nillable = true)} and drops the synthetic local element info.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xelement-wrapper", description = "Flatten single-collection wrapper types into @XmlElementWrapper properties")
public class ElementWrapperPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(ElementWrapperPlugin.class);
    /**
     * Captured in postProcessModel; used in run to place {@link XmlElementWrapper}.
     */
    private final List<FlattenedField> flattenedFields = new ArrayList<>();

    @Option(name = "remove-wrapper-class", defaultValue = "true", description = "Whether to remove the wrapper class")
    Boolean removeWrapperClass;

    /**
     * Reads {@code nillable} from the original XSD element particle when available.
     */
    private static boolean isSchemaNillable(CPropertyInfo property) {
        var component = property.getSchemaComponent();
        if (component instanceof XSParticle particle
            && particle.getTerm() instanceof XSElementDecl elementDecl) {
            return elementDecl.isNillable();
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

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        flattenedFields.clear();

        var wrappers = findWrapperClasses(model);
        var usedWrappers = new LinkedHashSet<CClassInfo>();

        for (var owner : List.copyOf(model.beans().values())) {
            flattenOwner(model, owner, wrappers, usedWrappers);
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

    private void flattenOwner(
        Model model,
        CClassInfo owner,
        Set<CClassInfo> wrappers,
        Set<CClassInfo> usedWrappers
    ) {
        var properties = owner.getProperties();
        for (var i = 0; i < properties.size(); i++) {
            var outer = properties.get(i);
            var resolved = resolveWrapperOuter(outer, wrappers);
            if (resolved == null || owner == resolved.wrapper()) {
                continue;
            }

            var replacement = createFlattenedElementProperty(outer, resolved.inner());
            if (!replaceProperty(owner, i, outer, replacement)) {
                continue;
            }

            usedWrappers.add(resolved.wrapper());
            flattenedFields.add(new FlattenedField(
                owner,
                replacement.getName(false),
                resolved.wrapperName(),
                resolved.wrapperNillable(),
                resolved.wrapperRequired()
            ));

            if (resolved.orphanElementInfo() != null
                && !removeElementInfo(model, resolved.orphanElementInfo())) {
                log.warn("Could not remove synthetic element info for {}.{}",
                    owner.fullName(), outer.getName(false));
            }

            log.debug("Flattened {}.{} (wrapper {})",
                owner.fullName(), outer.getName(false), resolved.wrapper().fullName());
        }
    }

    /**
     * Resolves a non-collection outer property to a pure wrapper shell.
     * <p>
     * Two XJC shapes are accepted:
     * <ul>
     *   <li>{@link CElementPropertyInfo} — ordinary element binding (non-nillable optional
     *       or required shells)</li>
     *   <li>{@link CReferencePropertyInfo} with a single local {@link CElementInfo} whose
     *       content type is the wrapper — XJC default for nillable + optional complex
     *       elements ({@code JAXBElement&lt;Wrapper&gt;})</li>
     * </ul>
     * </p>
     */
    private ResolvedWrapperOuter resolveWrapperOuter(CPropertyInfo outer, Set<CClassInfo> wrappers) {
        if (outer.isCollection()) {
            return null;
        }
        if (outer instanceof CElementPropertyInfo elementProperty) {
            return resolveFromElementProperty(elementProperty, wrappers);
        }
        if (outer instanceof CReferencePropertyInfo referenceProperty) {
            return resolveFromReferenceProperty(referenceProperty, wrappers);
        }
        return null;
    }

    private ResolvedWrapperOuter resolveFromElementProperty(
        CElementPropertyInfo elementProperty,
        Set<CClassInfo> wrappers
    ) {
        if (elementProperty.getAdapter() != null || elementProperty.getTypes().size() != 1) {
            return null;
        }
        var typeRef = elementProperty.getTypes().getFirst();
        var target = typeRef.getTarget();
        if (!(target instanceof CClassInfo classInfo) || !wrappers.contains(classInfo)) {
            return null;
        }
        var inner = classInfo.getProperties().getFirst();
        if (!(inner instanceof CElementPropertyInfo innerElement)) {
            return null;
        }
        return new ResolvedWrapperOuter(
            classInfo,
            innerElement,
            typeRef.getTagName(),
            typeRef.isNillable() || isSchemaNillable(elementProperty),
            elementProperty.isRequired(),
            null
        );
    }

    private ResolvedWrapperOuter resolveFromReferenceProperty(
        CReferencePropertyInfo referenceProperty,
        Set<CClassInfo> wrappers
    ) {
        // Mixed / wildcard / multi-element reference properties are not wrapper shells.
        if (referenceProperty.isMixed()
            || referenceProperty.getWildcard() != null
            || referenceProperty.getElements().size() != 1) {
            return null;
        }

        var element = referenceProperty.getElements().iterator().next();
        // Simple-mode may invent a local bean class; only handle JAXBElement-style CElementInfo.
        if (!(element instanceof CElementInfo elementInfo) || elementInfo.hasClass()) {
            return null;
        }

        var content = elementInfo.getContentType();
        if (!(content instanceof CClassInfo classInfo) || !wrappers.contains(classInfo)) {
            return null;
        }

        var inner = classInfo.getProperties().getFirst();
        if (!(inner instanceof CElementPropertyInfo innerElement)) {
            return null;
        }

        // XJC creates this CElementInfo only for the reference binding of this particle;
        // remove it after flatten so the wrapper is not kept alive and ObjectFactory stays clean.
        return new ResolvedWrapperOuter(
            classInfo,
            innerElement,
            elementInfo.getElementName(),
            isSchemaNillable(referenceProperty)
                || elementInfo.getProperty().getTypes().getFirst().isNillable(),
            referenceProperty.isRequired(),
            elementInfo
        );
    }

    private CElementPropertyInfo createFlattenedElementProperty(
        CPropertyInfo outer,
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
        var sizeBefore = properties.size();
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

    private void annotateXmlElementWrapper(Outline outline, FlattenedField flattened) {
        var classOutline = outline.getClazz(flattened.owner());
        var field = classOutline.implClass.fields().get(flattened.propertyName());
        if (field == null) {
            log.warn("Could not find field {} on {}",
                flattened.propertyName(), flattened.owner().fullName());
            return;
        }

        if (AnnotationUtils.hasAnnotation(field, XmlElementWrapper.class)) {
            return;
        }

        var annotation = field.annotate(XmlElementWrapper.class);
        applyXmlElementWrapperParams(annotation, flattened, classOutline);
    }

    /**
     * Maps the original outer element QName onto {@link XmlElementWrapper}.
     * <p>
     * Mirrors XJC {@code AbstractField#writeXmlElementAnnotation} defaulting so we do not
     * emit redundant members that only restate package-info / form defaults:
     * <ul>
     *   <li><b>name</b> — omit when equal to the Java field name ({@code ##default}).</li>
     *   <li><b>namespace</b> — omit when annotation defaulting already yields the correct URI.
     *       Under {@code elementFormDefault=qualified}, that means the package's most-used
     *       namespace (or the enclosing type's target namespace); under {@code unqualified},
     *       emit only when the element actually has a non-empty namespace.
     *       Schema {@code targetNamespace} alone is not an "explicit" declaration on the
     *       wrapper type — putting it on every field would duplicate {@code package-info}.
     *   </li>
     *   <li><b>nillable</b> / <b>required</b> — only when true on the original outer element.</li>
     * </ul>
     * </p>
     */
    private void applyXmlElementWrapperParams(
        JAnnotationUse annotation,
        FlattenedField flattened,
        ClassOutline classOutline
    ) {
        var wrapperName = flattened.wrapperName();
        if (wrapperName == null) {
            return;
        }

        var localName = wrapperName.getLocalPart();
        if (localName != null && !localName.isEmpty() && !localName.equals(flattened.propertyName())) {
            annotation.param("name", localName);
        }

        if (needsExplicitWrapperNamespace(wrapperName, classOutline)) {
            annotation.param("namespace", wrapperName.getNamespaceURI());
        }

        if (flattened.wrapperNillable()) {
            annotation.param("nillable", true);
        }
        if (flattened.wrapperRequired()) {
            annotation.param("required", true);
        }
    }

    /**
     * Whether {@code @XmlElementWrapper} must restate {@code namespace}.
     * Same rule as XJC {@code AbstractField#writeXmlElementAnnotation} for {@code @XmlElement}:
     * skip when package-info / form default already imply the correct URI.
     */
    private boolean needsExplicitWrapperNamespace(QName wrapperName, ClassOutline classOutline) {
        var generatedNS = wrapperName.getNamespaceURI();
        var pkg = classOutline._package();
        var formDefault = pkg.getElementFormDefault();
        var typeName = classOutline.target.getTypeName();
        var enclosingTypeNS = typeName == null
            ? pkg.getMostUsedNamespaceURI()
            : typeName.getNamespaceURI();

        return (formDefault == XmlNsForm.QUALIFIED && !generatedNS.equals(enclosingTypeNS))
            || (formDefault == XmlNsForm.UNQUALIFIED && !generatedNS.isEmpty());
    }

    private record FlattenedField(
        CClassInfo owner,
        String propertyName,
        QName wrapperName,
        boolean wrapperNillable,
        boolean wrapperRequired
    ) {
    }

    /**
     * Resolved outer property that points at a pure wrapper shell.
     *
     * @param orphanElementInfo local {@link CElementInfo} synthesized for nillable reference
     *                          binding; removed after a successful flatten, or {@code null}
     */
    private record ResolvedWrapperOuter(
        CClassInfo wrapper,
        CElementPropertyInfo inner,
        QName wrapperName,
        boolean wrapperNillable,
        boolean wrapperRequired,
        CElementInfo orphanElementInfo
    ) {
    }
}
