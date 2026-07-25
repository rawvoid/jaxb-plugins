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

package io.github.rawvoid.jaxb.plugin.validation;

import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSimpleType;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;

/**
 * Applies Bean Validation annotations to a single generated field from its XJC property model.
 */
public final class FieldAnnotator {

    private final JCodeModel codeModel;
    private final ValidationApi api;
    private final boolean disableValid;

    public FieldAnnotator(Outline outline, ValidationApi api, boolean disableValid) {
        this.codeModel = outline.getCodeModel();
        this.api = api;
        this.disableValid = disableValid;
    }

    public void annotate(CPropertyInfo prop, JFieldVar field) {
        applyPresenceAndCollectionSize(prop, field);
        applySimpleFacets(resolveSimpleType(prop), field);
        if (!disableValid && isComplexProperty(prop)) {
            annotateIfAbsent(field, api.validFqcn());
        }
    }

    // --- presence / multiplicity ---------------------------------------------------------------

    private void applyPresenceAndCollectionSize(CPropertyInfo prop, JFieldVar field) {
        if (prop instanceof CAttributePropertyInfo attribute) {
            if (attribute.isRequired() && isReferenceType(field.type())) {
                annotateIfAbsent(field, api.constraint("NotNull"));
            }
            return;
        }

        if (prop instanceof CValuePropertyInfo) {
            return;
        }

        var particle = particleOf(prop);
        if (particle == null) {
            // Fall back to CElementPropertyInfo flags when particle is unavailable.
            if (prop instanceof CElementPropertyInfo element
                && element.isRequired()
                && !isElementNillable(element)
                && isReferenceType(field.type())) {
                annotateIfAbsent(field, api.constraint("NotNull"));
            }
            return;
        }

        var minOccurs = particle.getMinOccurs().longValue();
        var maxOccurs = particle.getMaxOccurs().longValue();
        var nillable = isParticleNillable(particle);

        if (prop.isCollection()) {
            if (minOccurs >= 1) {
                annotateIfAbsent(field, api.constraint("NotNull"));
            }
            applyCollectionSize(field, minOccurs, maxOccurs);
            return;
        }

        if (minOccurs >= 1 && !nillable && isReferenceType(field.type())) {
            annotateIfAbsent(field, api.constraint("NotNull"));
        }
    }

    private void applyCollectionSize(JFieldVar field, long minOccurs, long maxOccurs) {
        // unbounded is -1 in XSOM; optional unbounded (min=0) needs no @Size
        var hasMin = minOccurs > 0;
        var hasMax = maxOccurs > 1; // includes finite max; excludes 0/1 and -1
        if (!hasMin && !hasMax) {
            return;
        }

        var sizeFqcn = api.constraint("Size");
        if (AnnotationUtils.hasAnnotation(field, sizeFqcn)) {
            return;
        }
        var anno = field.annotate(codeModel.ref(sizeFqcn));
        if (hasMin) {
            anno.param("min", (int) minOccurs);
        }
        if (hasMax) {
            anno.param("max", (int) maxOccurs);
        }
    }

    // --- simple type facets --------------------------------------------------------------------

    private void applySimpleFacets(XSSimpleType simpleType, JFieldVar field) {
        if (simpleType == null) {
            return;
        }
        var facets = SchemaFacets.of(simpleType);
        applySizeFacets(facets, field);
        applyPatternFacets(facets, field);
        applyNumericBounds(facets, field);
        applyDigits(facets, field);
    }

    private void applySizeFacets(SchemaFacets facets, JFieldVar field) {
        if (!isStringLike(field.type()) || field.type().isArray()) {
            // Collection multiplicity already owns @Size; item length is intentionally unsupported.
            return;
        }
        // Skip true collections (List etc.) — their @Size is multiplicity.
        if (isCollectionErasure(field.type())) {
            return;
        }

        var sizeFqcn = api.constraint("Size");
        if (AnnotationUtils.hasAnnotation(field, sizeFqcn)) {
            return;
        }

        var length = facets.intValue(XSFacet.FACET_LENGTH);
        if (length != null) {
            var anno = field.annotate(codeModel.ref(sizeFqcn));
            anno.param("min", length);
            anno.param("max", length);
            return;
        }

        var min = facets.intValue(XSFacet.FACET_MINLENGTH);
        var max = facets.intValue(XSFacet.FACET_MAXLENGTH);
        if (min == null && max == null) {
            return;
        }
        var anno = field.annotate(codeModel.ref(sizeFqcn));
        if (min != null) {
            anno.param("min", min);
        }
        if (max != null) {
            anno.param("max", max);
        }
    }

    private void applyPatternFacets(SchemaFacets facets, JFieldVar field) {
        var patterns = facets.patterns();
        if (patterns.isEmpty()) {
            return;
        }
        var patternFqcn = api.constraint("Pattern");
        for (var regex : patterns) {
            // Multiple patterns are AND-ed (XSD semantics); BV 2.0 @Pattern is repeatable.
            var anno = field.annotate(codeModel.ref(patternFqcn));
            anno.param("regexp", regex);
        }
    }

    private void applyNumericBounds(SchemaFacets facets, JFieldVar field) {
        applyMin(field, facets.get(XSFacet.FACET_MININCLUSIVE), true);
        applyMax(field, facets.get(XSFacet.FACET_MAXINCLUSIVE), true);
        applyMin(field, facets.get(XSFacet.FACET_MINEXCLUSIVE), false);
        applyMax(field, facets.get(XSFacet.FACET_MAXEXCLUSIVE), false);
    }

    private void applyMin(JFieldVar field, String raw, boolean inclusive) {
        if (raw == null) {
            return;
        }
        if (!inclusive) {
            annotateDecimalBound(field, "DecimalMin", raw, false);
            return;
        }
        var asLong = SchemaFacets.parseLong(raw);
        if (asLong != null) {
            var minFqcn = api.constraint("Min");
            if (!AnnotationUtils.hasAnnotation(field, minFqcn)) {
                field.annotate(codeModel.ref(minFqcn)).param("value", asLong);
            }
        } else {
            annotateDecimalBound(field, "DecimalMin", raw, true);
        }
    }

    private void applyMax(JFieldVar field, String raw, boolean inclusive) {
        if (raw == null) {
            return;
        }
        if (!inclusive) {
            annotateDecimalBound(field, "DecimalMax", raw, false);
            return;
        }
        var asLong = SchemaFacets.parseLong(raw);
        if (asLong != null) {
            var maxFqcn = api.constraint("Max");
            if (!AnnotationUtils.hasAnnotation(field, maxFqcn)) {
                field.annotate(codeModel.ref(maxFqcn)).param("value", asLong);
            }
        } else {
            annotateDecimalBound(field, "DecimalMax", raw, true);
        }
    }

    private void annotateDecimalBound(JFieldVar field, String simpleName, String value, boolean inclusive) {
        var fqcn = api.constraint(simpleName);
        if (AnnotationUtils.hasAnnotation(field, fqcn)) {
            return;
        }
        var anno = field.annotate(codeModel.ref(fqcn));
        anno.param("value", value);
        if (!inclusive) {
            anno.param("inclusive", false);
        }
    }

    private void applyDigits(SchemaFacets facets, JFieldVar field) {
        // Only emit when the user restriction chain declares totalDigits (never built-in alone).
        var total = facets.intValue(XSFacet.FACET_TOTALDIGITS);
        if (total == null) {
            return;
        }
        var digitsFqcn = api.constraint("Digits");
        if (AnnotationUtils.hasAnnotation(field, digitsFqcn)) {
            return;
        }
        var fraction = facets.intValue(XSFacet.FACET_FRACTIONDIGITS);
        var fractionInt = fraction != null ? fraction : 0;
        var integerPart = Math.max(0, total - fractionInt);
        var anno = field.annotate(codeModel.ref(digitsFqcn));
        anno.param("integer", integerPart);
        anno.param("fraction", fractionInt);
    }

    // --- cascade -------------------------------------------------------------------------------

    private static boolean isComplexProperty(CPropertyInfo prop) {
        // CClassInfo = generated bean; enums are CEnumLeafInfo and must not get @Valid.
        for (var ref : prop.ref()) {
            if (ref instanceof CClassInfo) {
                return true;
            }
        }
        return false;
    }

    // --- schema resolution ---------------------------------------------------------------------

    private static XSSimpleType resolveSimpleType(CPropertyInfo prop) {
        var component = prop.getSchemaComponent();
        if (component == null) {
            return null;
        }
        if (component instanceof XSParticle particle) {
            if (particle.getTerm() instanceof XSElementDecl element
                && element.getType() instanceof XSSimpleType simpleType) {
                return simpleType;
            }
            return null;
        }
        if (component instanceof XSElementDecl element
            && element.getType() instanceof XSSimpleType simpleType) {
            return simpleType;
        }
        if (component instanceof XSAttributeUse attributeUse) {
            return attributeUse.getDecl().getType();
        }
        if (component instanceof com.sun.xml.xsom.XSAttributeDecl attributeDecl) {
            return attributeDecl.getType();
        }
        if (component instanceof XSSimpleType simpleType) {
            return simpleType;
        }
        return null;
    }

    private static XSParticle particleOf(CPropertyInfo prop) {
        var component = prop.getSchemaComponent();
        return component instanceof XSParticle particle ? particle : null;
    }

    private static boolean isParticleNillable(XSParticle particle) {
        return particle.getTerm() instanceof XSElementDecl element && element.isNillable();
    }

    private static boolean isElementNillable(CElementPropertyInfo element) {
        for (var typeRef : element.getTypes()) {
            if (typeRef.isNillable()) {
                return true;
            }
        }
        return false;
    }

    // --- type helpers --------------------------------------------------------------------------

    private static boolean isReferenceType(JType type) {
        return !type.isPrimitive();
    }

    private static boolean isStringLike(JType type) {
        return "java.lang.String".equals(type.fullName());
    }

    private static boolean isCollectionErasure(JType type) {
        if (!type.isReference()) {
            return false;
        }
        var erasure = type.erasure().fullName();
        return "java.util.List".equals(erasure)
            || "java.util.Set".equals(erasure)
            || "java.util.Collection".equals(erasure)
            || "java.util.Map".equals(erasure);
    }

    private void annotateIfAbsent(JFieldVar field, String fqcn) {
        if (!AnnotationUtils.hasAnnotation(field, fqcn)) {
            field.annotate(codeModel.ref(fqcn));
        }
    }
}
