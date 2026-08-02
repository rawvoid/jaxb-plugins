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
import com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BindInfo;
import com.sun.xml.xsom.XSElementDecl;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.glassfish.jaxb.core.v2.model.core.ID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import javax.xml.namespace.QName;
import java.util.*;


/**
 * Flattens multi-element properties into individual single-element properties.
 * <p>
 * XJC merges multiple alternative elements (from {@code xs:choice} or inheritance
 * collisions) into a single list property annotated with {@code @XmlElements} or
 * {@code @XmlElementRefs}. This plugin splits such properties back into individual
 * fields — one per bound element — preserving the original position in the
 * property order.
 * </p>
 * <p>
 * Two kinds of multi-element property are handled:
 * </p>
 * <ul>
 *   <li>{@link CElementPropertyInfo} with {@code getTypes().size() > 1}
 *       ({@code @XmlElements}) — each {@link CTypeRef} becomes its own
 *       {@code CElementPropertyInfo}.</li>
 *   <li>{@link CReferencePropertyInfo} with {@code getElements().size() > 1}
 *       ({@code @XmlElementRefs}) — each {@link CElement} is converted to a
 *       {@code CElementPropertyInfo} when possible (avoiding {@code JAXBElement}
 *       wrappers), falling back to a single-element {@code CReferencePropertyInfo}
 *       otherwise.</li>
 * </ul>
 * <p>
 * <strong>Mutual exclusion:</strong> do not enable this plugin together with
 * {@link RenameMultiElementPropPlugin} — one renames while the other splits.
 * </p>
 * <p>
 * Logging: each successful split is {@code DEBUG}; the property count is {@code INFO}.
 * Failed {@code addProperty} / empty replacement cases are single-line {@code WARN}.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xflatten-multi-element-prop",
    description = "Flatten multi-element properties into individual single-element fields")
public class FlattenMultiElementPropPlugin extends OptionPlugin {

    private static final Logger log = LoggerFactory.getLogger(FlattenMultiElementPropPlugin.class);

    private static final NameConverter NAMES = NameConverter.standard;

    private static boolean isRequired(CPropertyInfo prop) {
        if (prop instanceof CElementPropertyInfo ep) {
            return ep.isRequired();
        }
        if (prop instanceof CReferencePropertyInfo rp) {
            return rp.isRequired();
        }
        return false;
    }

    private static boolean isNillable(CElement element, CTypeRef sourceTypeRef) {
        var schemaComponent = element.getSchemaComponent();
        if (schemaComponent instanceof XSElementDecl decl) {
            return decl.isNillable();
        }
        if (element instanceof CClassInfo classInfo) {
            var elementName = classInfo.getElementName();
            if (elementName != null && classInfo.model != null && classInfo.model.schemaComponent != null) {
                var elementDecl = classInfo.model.schemaComponent.getElementDecl(
                    elementName.getNamespaceURI(),
                    elementName.getLocalPart()
                );
                if (elementDecl != null) {
                    return elementDecl.isNillable();
                }
            }
        }
        return sourceTypeRef != null && sourceTypeRef.isNillable();
    }

    private static String normalize(String n) {
        return n.toLowerCase(Locale.ROOT);
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var flattened = 0;
        for (var bean : model.beans().values()) {
            flattened += handleClass(bean);
        }
        if (flattened > 0) {
            log.info("Flattened {} multi-element property(ies)", flattened);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    private int handleClass(CClassInfo bean) {
        var properties = bean.getProperties();
        var count = 0;

        // Forward iteration with manual index tracking; the list is mutated in-place.
        for (var i = 0; i < properties.size(); i++) {
            var prop = properties.get(i);
            if (!RenameMultiElementPropPlugin.isMultiElementProperty(prop)) {
                continue;
            }

            var replacements = buildReplacements(prop, bean);
            if (replacements.isEmpty()) {
                continue;
            }

            var originalName = prop.getName(false);
            // Replace the original property at position i with all the new ones.
            if (replaceProperty(bean, i, prop, replacements)) {
                // After replacement, i now points at the first new property.
                // Advance past all inserted properties so the loop continues correctly.
                i += replacements.size() - 1;
                count++;
                log.debug(
                    "Flattened {}.{} → {}",
                    bean.fullName(),
                    originalName,
                    replacements.stream().map(r -> r.getName(false)).toList()
                );
            }
        }
        return count;
    }

    /**
     * Builds individual single-element properties from a multi-element property.
     */
    private List<CPropertyInfo> buildReplacements(CPropertyInfo original, CClassInfo bean) {
        var preExisting = getPreExistingOccupied(original, bean);

        Set<String> occupied = new HashSet<>(preExisting);
        var replacements = new ArrayList<CPropertyInfo>();

        if (original instanceof CElementPropertyInfo elementProp) {
            for (var typeRef : elementProp.getTypes()) {
                var xmlName = typeRef.getTagName().getLocalPart();
                var privateName = allocateName(xmlName, occupied);
                var publicName = NAMES.toPropertyName(privateName);

                var newProp = new CElementPropertyInfo(
                    publicName,
                    original.isCollection() ? CollectionMode.REPEATED_ELEMENT : CollectionMode.NOT_REPEATED,
                    elementProp.id(),
                    elementProp.getExpectedMimeType(),
                    elementProp.getSchemaComponent(),
                    new CCustomizations(elementProp.getCustomizations()),
                    elementProp.getLocator(),
                    isRequired(original)
                );
                newProp.setName(false, privateName);
                newProp.setName(true, publicName);
                newProp.getTypes().add(typeRef);

                if (elementProp.getAdapter() != null) {
                    newProp.setAdapter(elementProp.getAdapter());
                }
                newProp.javadoc = resolveJavadoc(typeRef.getTagName(), null, bean, original.javadoc);
                newProp.inlineBinaryData = original.inlineBinaryData;
                newProp.realization = original.realization;
                newProp.baseType = original.baseType;
                replacements.add(newProp);
            }
        } else if (original instanceof CReferencePropertyInfo refProp) {
            for (var element : refProp.getElements()) {
                if (element.getElementName() == null) {
                    // Abort flattening of this property if any element cannot be named
                    return List.of();
                }
                var xmlName = element.getElementName().getLocalPart();
                var privateName = allocateName(xmlName, occupied);
                var publicName = NAMES.toPropertyName(privateName);

                var newProp = convertToElementProperty(element, privateName, publicName, refProp, bean);
                if (newProp != null) {
                    replacements.add(newProp);
                } else {
                    // Fallback: keep as single-element CReferencePropertyInfo.
                    var fallback = createSingleRefProperty(element, privateName, publicName, refProp, bean);
                    replacements.add(fallback);
                }
            }
        }

        return replacements;
    }

    /**
     * Converts a {@link CElement} from a reference property into a
     * {@link CElementPropertyInfo} to avoid {@code JAXBElement} wrappers.
     *
     * @return the converted property, or {@code null} if conversion is not possible
     */
    private CElementPropertyInfo convertToElementProperty(
        CElement element, String privateName, String publicName,
        CReferencePropertyInfo original, CClassInfo bean
    ) {
        if (element instanceof CElementInfo elementInfo) {
            var innerProp = elementInfo.getProperty();
            if (innerProp == null || innerProp.getTypes().isEmpty()) {
                return null;
            }

            // Extract the CTypeRef from the element info — it carries the
            // content type and XML element name.
            var sourceTypeRef = innerProp.getTypes().getFirst();

            var newProp = new CElementPropertyInfo(
                publicName,
                original.isCollection() ? CollectionMode.REPEATED_ELEMENT : CollectionMode.NOT_REPEATED,
                innerProp.id(),
                innerProp.getExpectedMimeType(),
                original.getSchemaComponent(),
                new CCustomizations(original.getCustomizations()),
                original.getLocator(),
                isRequired(original)
            );
            newProp.setName(false, privateName);
            newProp.setName(true, publicName);
            newProp.getTypes().add(new CTypeRef(
                sourceTypeRef.getTarget(),
                element.getElementName(),
                sourceTypeRef.getTypeName(),
                isNillable(element, sourceTypeRef),
                sourceTypeRef.defaultValue
            ));

            if (innerProp.getAdapter() != null) {
                newProp.setAdapter(innerProp.getAdapter());
            }
            newProp.javadoc = resolveJavadoc(element.getElementName(), element, bean, original.javadoc);
            newProp.inlineBinaryData = original.inlineBinaryData;
            newProp.realization = original.realization;
            newProp.baseType = original.baseType;
            return newProp;

        } else if (element instanceof CClassInfo classInfo) {
            // CClassInfo is itself a CNonElement — can serve as a CTypeRef target.
            // ID.NONE: class-bound elements are never ID/IDREF; null would NPE in BeanGenerator.
            var newProp = new CElementPropertyInfo(
                publicName,
                original.isCollection() ? CollectionMode.REPEATED_ELEMENT : CollectionMode.NOT_REPEATED,
                ID.NONE,
                null,
                original.getSchemaComponent(),
                new CCustomizations(original.getCustomizations()),
                original.getLocator(),
                isRequired(original)
            );
            newProp.setName(false, privateName);
            newProp.setName(true, publicName);
            newProp.getTypes().add(new CTypeRef(
                classInfo,
                element.getElementName(),
                null,
                isNillable(element, null),
                null
            ));
            newProp.javadoc = resolveJavadoc(element.getElementName(), element, bean, original.javadoc);
            newProp.inlineBinaryData = original.inlineBinaryData;
            newProp.realization = original.realization;
            newProp.baseType = original.baseType;
            return newProp;
        }

        return null;
    }

    /**
     * Creates a single-element {@link CReferencePropertyInfo} as a fallback
     * when conversion to {@link CElementPropertyInfo} is not possible.
     */
    private CReferencePropertyInfo createSingleRefProperty(
        CElement element, String privateName, String publicName,
        CReferencePropertyInfo original, CClassInfo bean
    ) {
        var newProp = new CReferencePropertyInfo(
            publicName,
            original.isCollection(),
            isRequired(original),
            original.isMixed(),
            original.getSchemaComponent(),
            new CCustomizations(original.getCustomizations()),
            original.getLocator(),
            original.isDummy(),
            original.isContent(),
            original.isMixedExtendedCust()
        );
        newProp.setName(false, privateName);
        newProp.setName(true, publicName);
        newProp.getElements().add(element);
        newProp.javadoc = resolveJavadoc(element.getElementName(), element, bean, original.javadoc);
        newProp.inlineBinaryData = original.inlineBinaryData;
        newProp.realization = original.realization;
        newProp.baseType = original.baseType;
        return newProp;
    }

    private static String resolveJavadoc(QName elementName, CElement element, CClassInfo bean, String fallbackJavadoc) {
        if (element instanceof CElementInfo elementInfo) {
            var innerProp = elementInfo.getProperty();
            if (innerProp != null && innerProp.javadoc != null && !innerProp.javadoc.isBlank()) {
                return innerProp.javadoc;
            }
        }
        if (element instanceof CClassInfo classInfo && classInfo.javadoc != null && !classInfo.javadoc.isBlank()) {
            return classInfo.javadoc;
        }

        XSElementDecl elementDecl = null;
        if (element != null && element.getSchemaComponent() instanceof XSElementDecl decl) {
            elementDecl = decl;
        } else if (elementName != null && bean != null && bean.model != null && bean.model.schemaComponent != null) {
            elementDecl = bean.model.schemaComponent.getElementDecl(elementName.getNamespaceURI(), elementName.getLocalPart());
        }

        if (elementDecl != null && elementDecl.getAnnotation() != null) {
            if (elementDecl.getAnnotation().getAnnotation() instanceof BindInfo bi) {
                var doc = bi.getDocumentation();
                if (doc != null && !doc.isBlank()) {
                    return doc;
                }
            }
        }

        return fallbackJavadoc;
    }


    /**
     * Replaces the property at {@code index} with all {@code replacements},
     * preserving position in the property list.
     * <p>
     * Uses {@link CClassInfo#addProperty} so that {@code setParent} runs for each
     * new property. The appended properties are then moved from the tail back to
     * the original position.
     * </p>
     */
    private boolean replaceProperty(
        CClassInfo owner, int index, CPropertyInfo original,
        List<CPropertyInfo> replacements
    ) {
        var properties = owner.getProperties();
        var sizeBefore = properties.size();

        // 1. Append all replacements via addProperty (triggers setParent).
        var added = 0;
        for (var replacement : replacements) {
            owner.addProperty(replacement);
            if (properties.size() == sizeBefore + added + 1) {
                added++;
            } else {
                log.warn(
                    "Skip adding {}.{}: addProperty did not append",
                    owner.fullName(),
                    replacement.getName(false)
                );
            }
        }

        if (added == 0) {
            log.warn(
                "Skip flattening {}.{}: no replacements were added",
                owner.fullName(),
                original.getName(false)
            );
            return false;
        }

        // 2. Remove original at index.
        properties.remove(index);

        // 3. Move the `added` properties from the tail to index.
        //    Since removeLast() retrieves the last added property first, inserting
        //    them successively at `index` naturally restores their original order.
        for (var j = 0; j < added; j++) {
            properties.add(index, properties.removeLast());
        }

        return true;
    }

    /**
     * Allocates a unique Java variable name derived from the XML element name,
     * adding numeric suffixes when conflicts arise.
     */
    private String allocateName(String xmlName, Set<String> occupied) {
        var base = NAMES.toVariableName(xmlName);
        var candidate = base;
        var suffix = 2;
        while (occupied.contains(normalize(candidate))) {
            candidate = base + suffix;
            suffix++;
        }
        occupied.add(normalize(candidate));
        return candidate;
    }

    private Set<String> getPreExistingOccupied(CPropertyInfo original, CClassInfo bean) {
        Set<String> occupied = new HashSet<>();
        for (var prop : bean.getProperties()) {
            if (prop != original) {
                occupied.add(normalize(prop.getName(false)));
            }
        }
        var parent = bean.getBaseClass();
        while (parent instanceof CClassInfo parentClass) {
            for (var prop : parentClass.getProperties()) {
                occupied.add(normalize(prop.getName(false)));
            }
            parent = parentClass.getBaseClass();
        }
        return occupied;
    }
}
