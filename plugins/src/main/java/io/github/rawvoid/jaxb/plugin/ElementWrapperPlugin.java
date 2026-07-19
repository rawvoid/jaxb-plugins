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
import jakarta.xml.bind.annotation.XmlElementWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import java.util.*;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CPROPERTYINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.removeClass;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

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

    private boolean isWrapperClass(CClassInfo classInfo) {
        if (classInfo.isAbstract()
            || classInfo.getBaseClass() != null
            || classInfo.getRefBaseClass() != null
            || classInfo.getProperties().size() != 1) {
            return false;
        }

        var property = classInfo.getProperties().getFirst();
        if (!property.isCollection() || property.ref().isEmpty()) {
            return false;
        }

        // Classic XSD wrappers are element collections; value lists / attributes are not.
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
     * Replaces {@code outer} with {@code replacement} <strong>in place</strong> so
     * {@code propOrder} and field declaration order stay unchanged.
     * <p>
     * {@link CClassInfo#addProperty} always appends, which would move the flattened
     * property to the end; we therefore assign parent via the same field
     * {@code setParent} uses and {@link List#set(int, Object)} at the original index.
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
        if (index < 0 || index >= properties.size() || properties.get(index) != outer) {
            index = -1;
            for (var i = 0; i < properties.size(); i++) {
                if (properties.get(i) == outer) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                log.warn("Skip flattening {}.{}: outer property not found on owner",
                    owner.fullName(), outer.getName(false));
                return false;
            }
        }

        // Mirror CPropertyInfo.setParent (package-private) without appending to the list.
        setFieldValue(CPROPERTYINFO_PARENT_FIELD, replacement, owner);
        properties.set(index, replacement);
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

    private boolean isReferenced(Model model, CClassInfo target) {
        for (var bean : model.beans().values()) {
            if (bean == target) {
                continue;
            }
            if (bean.getBaseClass() == target || bean.parent() == target) {
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
        var wrapperName = flattened.wrapperName();
        if (wrapperName == null) {
            return;
        }

        var localName = wrapperName.getLocalPart();
        // Match field name → leave name/namespace at annotation defaults (##default).
        if (localName != null && !localName.isEmpty() && !localName.equals(flattened.propertyName())) {
            annotation.param("name", localName);
            var namespace = wrapperName.getNamespaceURI();
            if (namespace != null && !namespace.isEmpty()) {
                annotation.param("namespace", namespace);
            }
        }
        if (flattened.wrapperNillable()) {
            annotation.param("nillable", true);
        }
        if (flattened.wrapperRequired()) {
            annotation.param("required", true);
        }
    }
}
