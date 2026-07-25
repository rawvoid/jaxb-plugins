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

import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.*;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates Jakarta Bean Validation (JSR-380) or legacy javax validation annotations
 * on generated JAXB class fields based on XSD schema constraints and multiplicity.
 *
 * @author Rawvoid
 */
@Option(name = "Xvalidation", description = "Add Bean Validation annotations (JSR-380) based on XSD schema constraints")
public class ValidationPlugin extends AbstractPlugin {

    private static final String API_JAVAX = "javax";

    @Option(name = "api", defaultValue = "jakarta",
        description = "Validation API package mode: 'jakarta' (default) or 'javax'")
    String api;

    @Option(name = "class-name", description = "Regex to match fully-qualified class names (repeatable)")
    List<Pattern> classNames;

    @Option(name = "field-name", description = "Regex to match field names (repeatable)")
    List<Pattern> fieldNames;

    @Option(name = "disable-valid", defaultValue = "false",
        description = "Disable automatic @Valid annotation on complex or collection properties")
    Boolean disableValid;

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var isJavax = API_JAVAX.equalsIgnoreCase(api != null ? api.trim() : "");
        var constraintPkg = isJavax ? "javax.validation.constraints." : "jakarta.validation.constraints.";
        var validFqcn = isJavax ? "javax.validation.Valid" : "jakarta.validation.Valid";

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            if (!matchesClassName(implClass.fullName())) {
                continue;
            }

            for (var fieldOutline : classOutline.getDeclaredFields()) {
                var prop = fieldOutline.getPropertyInfo();
                var fieldName = prop.getName(false);
                if (!matchesFieldName(fieldName)) {
                    continue;
                }

                var fieldVar = implClass.fields().get(fieldName);
                if (fieldVar == null) {
                    continue;
                }

                processFieldValidation(outline, prop, fieldVar, constraintPkg, validFqcn);
            }
        }
        return true;
    }

    private void processFieldValidation(Outline outline,
                                        CPropertyInfo prop,
                                        JFieldVar fieldVar,
                                        String constraintPkg,
                                        String validFqcn) {
        var component = prop.getSchemaComponent();

        XSParticle particle = null;
        XSElementDecl elementDecl = null;

        if (component instanceof XSParticle p) {
            particle = p;
            if (p.getTerm() instanceof XSElementDecl e) {
                elementDecl = e;
            }
        } else if (component instanceof XSElementDecl e) {
            elementDecl = e;
        }

        var isCollection = prop.isCollection();
        var minOccurs = particle != null ? particle.getMinOccurs().longValue() : 0L;
        var maxOccurs = particle != null ? particle.getMaxOccurs().longValue() : 1L;

        // 1. Mandatory presence / collection size rules
        if (isCollection) {
            applyCollectionSizeAndNotNull(outline, fieldVar, constraintPkg, minOccurs, maxOccurs);
        } else {
            var isNillable = elementDecl != null && elementDecl.isNillable();
            if (minOccurs >= 1 && !isNillable) {
                addAnnotationIfAbsent(outline, fieldVar, constraintPkg + "NotNull");
            }
        }

        // 2. Simple type facets (String length, pattern, numeric bounds, digits)
        var simpleType = findSimpleType(component);
        if (simpleType != null) {
            applySimpleTypeFacets(outline, fieldVar, constraintPkg, simpleType);
        }

        // 3. Cascade validation (@Valid) for complex types and collections of complex types
        if (!Boolean.TRUE.equals(disableValid) && isComplexTypeProperty(prop, fieldVar, outline)) {
            addAnnotationIfAbsent(outline, fieldVar, validFqcn);
        }
    }

    private void applyCollectionSizeAndNotNull(Outline outline,
                                               JFieldVar fieldVar,
                                               String constraintPkg,
                                               long minOccurs,
                                               long maxOccurs) {
        if (minOccurs >= 1) {
            addAnnotationIfAbsent(outline, fieldVar, constraintPkg + "NotNull");
        }

        if (minOccurs > 0 || maxOccurs > 1) {
            var sizeFqcn = constraintPkg + "Size";
            if (!AnnotationUtils.hasAnnotation(fieldVar, sizeFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(sizeFqcn));
                if (minOccurs > 0) {
                    anno.param("min", (int) minOccurs);
                }
                if (maxOccurs > 0) {
                    anno.param("max", (int) maxOccurs);
                }
            }
        }
    }

    private XSSimpleType findSimpleType(XSComponent component) {
        if (component instanceof XSParticle particle) {
            component = particle.getTerm();
        }
        if (component instanceof XSElementDecl elementDecl) {
            if (elementDecl.getType() instanceof XSSimpleType simpleType) {
                return simpleType;
            }
        } else if (component instanceof XSAttributeDecl attributeDecl) {
            return attributeDecl.getType();
        } else if (component instanceof XSSimpleType simpleType) {
            return simpleType;
        }
        return null;
    }

    private void applySimpleTypeFacets(Outline outline,
                                       JFieldVar fieldVar,
                                       String constraintPkg,
                                       XSSimpleType simpleType) {
        // String length / @Size
        var lenFacet = simpleType.getFacet(XSFacet.FACET_LENGTH);
        var minLenFacet = simpleType.getFacet(XSFacet.FACET_MINLENGTH);
        var maxLenFacet = simpleType.getFacet(XSFacet.FACET_MAXLENGTH);

        var sizeFqcn = constraintPkg + "Size";
        if (!AnnotationUtils.hasAnnotation(fieldVar, sizeFqcn)) {
            if (lenFacet != null && lenFacet.getValue() != null) {
                var len = parseIntSafely(lenFacet.getValue().value);
                if (len != null) {
                    var anno = fieldVar.annotate(outline.getCodeModel().ref(sizeFqcn));
                    anno.param("min", len);
                    anno.param("max", len);
                }
            } else if (minLenFacet != null || maxLenFacet != null) {
                var minVal = minLenFacet != null && minLenFacet.getValue() != null ? parseIntSafely(minLenFacet.getValue().value) : null;
                var maxVal = maxLenFacet != null && maxLenFacet.getValue() != null ? parseIntSafely(maxLenFacet.getValue().value) : null;
                if (minVal != null || maxVal != null) {
                    var anno = fieldVar.annotate(outline.getCodeModel().ref(sizeFqcn));
                    if (minVal != null) {
                        anno.param("min", minVal);
                    }
                    if (maxVal != null) {
                        anno.param("max", maxVal);
                    }
                }
            }
        }

        // Pattern / @Pattern
        var patternFacets = simpleType.getFacets(XSFacet.FACET_PATTERN);
        if (patternFacets != null && !patternFacets.isEmpty()) {
            var patternFqcn = constraintPkg + "Pattern";
            for (var facet : patternFacets) {
                if (facet.getValue() != null) {
                    var regex = facet.getValue().value;
                    if (regex != null && !regex.isBlank()) {
                        var anno = fieldVar.annotate(outline.getCodeModel().ref(patternFqcn));
                        anno.param("regexp", regex);
                    }
                }
            }
        }

        // Numeric min/max inclusive/exclusive
        var minInc = simpleType.getFacet(XSFacet.FACET_MININCLUSIVE);
        if (minInc != null && minInc.getValue() != null) {
            applyMinConstraint(outline, fieldVar, constraintPkg, minInc.getValue().value, true);
        }
        var maxInc = simpleType.getFacet(XSFacet.FACET_MAXINCLUSIVE);
        if (maxInc != null && maxInc.getValue() != null) {
            applyMaxConstraint(outline, fieldVar, constraintPkg, maxInc.getValue().value, true);
        }
        var minExc = simpleType.getFacet(XSFacet.FACET_MINEXCLUSIVE);
        if (minExc != null && minExc.getValue() != null) {
            applyMinConstraint(outline, fieldVar, constraintPkg, minExc.getValue().value, false);
        }
        var maxExc = simpleType.getFacet(XSFacet.FACET_MAXEXCLUSIVE);
        if (maxExc != null && maxExc.getValue() != null) {
            applyMaxConstraint(outline, fieldVar, constraintPkg, maxExc.getValue().value, false);
        }

        // Digits / @Digits
        var totalDigits = simpleType.getFacet(XSFacet.FACET_TOTALDIGITS);
        var fractionDigits = simpleType.getFacet(XSFacet.FACET_FRACTIONDIGITS);
        if ((totalDigits != null && totalDigits.getValue() != null) ||
            (fractionDigits != null && fractionDigits.getValue() != null)) {
            var digitsFqcn = constraintPkg + "Digits";
            if (!AnnotationUtils.hasAnnotation(fieldVar, digitsFqcn)) {
                var total = totalDigits != null && totalDigits.getValue() != null ? parseIntSafely(totalDigits.getValue().value) : null;
                var fraction = fractionDigits != null && fractionDigits.getValue() != null ? parseIntSafely(fractionDigits.getValue().value) : null;
                var totalInt = total != null ? total : 0;
                var fractionInt = fraction != null ? fraction : 0;
                var integerPart = Math.max(0, totalInt - fractionInt);
                var anno = fieldVar.annotate(outline.getCodeModel().ref(digitsFqcn));
                anno.param("integer", integerPart);
                anno.param("fraction", fractionInt);
            }
        }
    }

    private void applyMinConstraint(Outline outline, JFieldVar fieldVar, String constraintPkg, String rawVal, boolean inclusive) {
        if (!inclusive) {
            var decMinFqcn = constraintPkg + "DecimalMin";
            if (!AnnotationUtils.hasAnnotation(fieldVar, decMinFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(decMinFqcn));
                anno.param("value", rawVal);
                anno.param("inclusive", false);
            }
            return;
        }

        var val = parseLongSafely(rawVal);
        if (val != null) {
            var minFqcn = constraintPkg + "Min";
            if (!AnnotationUtils.hasAnnotation(fieldVar, minFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(minFqcn));
                anno.param("value", val);
            }
        } else {
            var decMinFqcn = constraintPkg + "DecimalMin";
            if (!AnnotationUtils.hasAnnotation(fieldVar, decMinFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(decMinFqcn));
                anno.param("value", rawVal);
            }
        }
    }

    private void applyMaxConstraint(Outline outline, JFieldVar fieldVar, String constraintPkg, String rawVal, boolean inclusive) {
        if (!inclusive) {
            var decMaxFqcn = constraintPkg + "DecimalMax";
            if (!AnnotationUtils.hasAnnotation(fieldVar, decMaxFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(decMaxFqcn));
                anno.param("value", rawVal);
                anno.param("inclusive", false);
            }
            return;
        }

        var val = parseLongSafely(rawVal);
        if (val != null) {
            var maxFqcn = constraintPkg + "Max";
            if (!AnnotationUtils.hasAnnotation(fieldVar, maxFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(maxFqcn));
                anno.param("value", val);
            }
        } else {
            var decMaxFqcn = constraintPkg + "DecimalMax";
            if (!AnnotationUtils.hasAnnotation(fieldVar, decMaxFqcn)) {
                var anno = fieldVar.annotate(outline.getCodeModel().ref(decMaxFqcn));
                anno.param("value", rawVal);
            }
        }
    }

    private boolean isComplexTypeProperty(CPropertyInfo prop, JFieldVar fieldVar, Outline outline) {
        for (var ref : prop.ref()) {
            if (ref instanceof CClassInfo) {
                return true;
            }
        }
        var fieldType = fieldVar.type();
        if (fieldType instanceof JDefinedClass defClass) {
            return outline.getClasses().stream().anyMatch(co -> co.implClass.equals(defClass));
        }
        if (fieldType.isReference()) {
            var typeArgs = fieldType.boxify().getTypeParameters();
            if (!typeArgs.isEmpty()) {
                for (var arg : typeArgs) {
                    if (arg instanceof JDefinedClass defClass) {
                        if (outline.getClasses().stream().anyMatch(co -> co.implClass.equals(defClass))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Integer parseIntSafely(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLongSafely(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void addAnnotationIfAbsent(Outline outline, JFieldVar fieldVar, String fqcn) {
        if (!AnnotationUtils.hasAnnotation(fieldVar, fqcn)) {
            fieldVar.annotate(outline.getCodeModel().ref(fqcn));
        }
    }

    private boolean matchesClassName(String className) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        return classNames.stream().anyMatch(p -> p.matcher(className).matches());
    }

    private boolean matchesFieldName(String fieldName) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return true;
        }
        return fieldNames.stream().anyMatch(p -> p.matcher(fieldName).matches());
    }
}
