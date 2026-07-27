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

import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSimpleType;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adds Bean Validation annotations on generated JAXB fields from XSD multiplicity and facets.
 *
 * <p>Maps element/attribute requiredness, collection size, string length, pattern, numeric bounds,
 * digits, and {@code @Valid} for complex types. The validation API package is auto-detected from the
 * XJC classpath: {@code jakarta.validation} is preferred when present; otherwise
 * {@code javax.validation}. Fails if neither API is available.
 *
 * <p><b>Intentional limits:</b> no {@code enumeration}/{@code whiteSpace}/{@code fixed}; collection
 * {@code @Size} is multiplicity only (not item length); only user-declared facets (built-in XML
 * Schema facets such as {@code xs:integer} {@code fractionDigits} are ignored); field annotations only.
 *
 * @author Rawvoid
 */
@Option(name = "Xvalidation", description = "Add Bean Validation annotations (JSR-380) based on XSD schema constraints")
public class ValidationPlugin extends AbstractPlugin {

    private static final String CXF_NOISE_PATTERN = "\\c+";
    private static final String JAKARTA_VALID = "jakarta.validation.Valid";
    private static final String JAVAX_VALID = "javax.validation.Valid";

    @Option(name = "class-name", description = "Regex to match fully-qualified class names (repeatable)")
    List<Pattern> classNames;

    @Option(name = "field-name", description = "Regex to match field names (repeatable)")
    List<Pattern> fieldNames;

    @Option(name = "disable-valid", defaultValue = "false",
        description = "Disable automatic @Valid annotation on complex or collection properties")
    Boolean disableValid;

    private String constraintPkg;
    private String validFqcn;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        resolveValidationApi();
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        if (constraintPkg == null) {
            try {
                resolveValidationApi();
            } catch (BadCommandLineException ex) {
                throw new SAXException(ex.getMessage(), ex);
            }
        }
        var cm = outline.getCodeModel();
        var skipValid = Boolean.TRUE.equals(disableValid);

        for (var classOutline : outline.getClasses()) {
            if (!matches(classNames, classOutline.implClass.fullName())) {
                continue;
            }
            for (var fieldOutline : classOutline.getDeclaredFields()) {
                if (fieldOutline == null) {
                    continue;
                }
                var prop = fieldOutline.getPropertyInfo();
                var name = prop.getName(false);
                if (!matches(fieldNames, name)) {
                    continue;
                }
                var field = classOutline.implClass.fields().get(name);
                if (field == null) {
                    continue;
                }
                applyPresence(cm, prop, field);
                applyFacets(cm, resolveSimpleType(prop), field);
                if (!skipValid && isComplex(prop)) {
                    annotateIfAbsent(cm, field, validFqcn);
                }
            }
        }
        return true;
    }

    private void resolveValidationApi() throws BadCommandLineException {
        var mode = chooseValidationApi(
            isPresent(JAKARTA_VALID),
            isPresent(JAVAX_VALID));
        if ("jakarta".equals(mode)) {
            constraintPkg = "jakarta.validation.constraints.";
            validFqcn = JAKARTA_VALID;
        } else {
            constraintPkg = "javax.validation.constraints.";
            validFqcn = JAVAX_VALID;
        }
    }

    /**
     * Prefers Jakarta when present; falls back to javax; fails when neither is available.
     *
     * @return {@code "jakarta"} or {@code "javax"}
     */
    static String chooseValidationApi(boolean jakartaPresent, boolean javaxPresent)
        throws BadCommandLineException {
        if (jakartaPresent) {
            return "jakarta";
        }
        if (javaxPresent) {
            return "javax";
        }
        throw new BadCommandLineException(
            "Bean Validation API not found on the XJC classpath. "
                + "Add jakarta.validation:jakarta.validation-api (preferred) or "
                + "javax.validation:validation-api to the XJC plugin dependencies.");
    }

    private static boolean isPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, ValidationPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    // --- presence / collection size ---

    private void applyPresence(JCodeModel cm, CPropertyInfo prop, JFieldVar field) {
        if (prop instanceof CAttributePropertyInfo attribute) {
            if (attribute.isRequired() && !field.type().isPrimitive()) {
                annotateIfAbsent(cm, field, constraintPkg + "NotNull");
            }
            return;
        }
        if (prop instanceof CValuePropertyInfo) {
            return;
        }

        var particle = prop.getSchemaComponent() instanceof XSParticle p ? p : null;
        if (particle == null) {
            if (prop instanceof CElementPropertyInfo element
                && element.isRequired()
                && !isNillable(element)
                && !field.type().isPrimitive()) {
                annotateIfAbsent(cm, field, constraintPkg + "NotNull");
            }
            return;
        }

        var min = particle.getMinOccurs().longValue();
        var max = particle.getMaxOccurs().longValue();
        var nillable = particle.getTerm() instanceof XSElementDecl e && e.isNillable();

        if (prop.isCollection()) {
            if (min >= 1) {
                annotateIfAbsent(cm, field, constraintPkg + "NotNull");
            }
            // unbounded max is -1 in XSOM
            if (min > 0 || max > 1) {
                var sizeFqcn = constraintPkg + "Size";
                if (!AnnotationUtils.hasAnnotation(field, sizeFqcn)) {
                    var anno = field.annotate(cm.ref(sizeFqcn));
                    if (min > 0) {
                        anno.param("min", (int) min);
                    }
                    if (max > 1) {
                        anno.param("max", (int) max);
                    }
                }
            }
            return;
        }

        if (min >= 1 && !nillable && !field.type().isPrimitive()) {
            annotateIfAbsent(cm, field, constraintPkg + "NotNull");
        }
    }

    // --- simple type facets ---

    private void applyFacets(JCodeModel cm, XSSimpleType simpleType, JFieldVar field) {
        if (simpleType == null) {
            return;
        }
        var singles = new LinkedHashMap<String, String>();
        var patterns = new ArrayList<String>();
        collectUserFacets(simpleType, singles, patterns);

        applyStringSize(cm, field, singles);
        for (var regex : patterns) {
            field.annotate(cm.ref(constraintPkg + "Pattern")).param("regexp", regex);
        }
        applyBound(cm, field, singles.get(XSFacet.FACET_MININCLUSIVE), true, true);
        applyBound(cm, field, singles.get(XSFacet.FACET_MAXINCLUSIVE), false, true);
        applyBound(cm, field, singles.get(XSFacet.FACET_MINEXCLUSIVE), true, false);
        applyBound(cm, field, singles.get(XSFacet.FACET_MAXEXCLUSIVE), false, false);
        applyDigits(cm, field, singles);
    }

    /**
     * Collects declared facets along the user restriction chain. Stops at built-in XML Schema types
     * so inherited facets like {@code xs:integer}/{@code fractionDigits=0} are not applied.
     */
    private static void collectUserFacets(XSSimpleType type,
                                          Map<String, String> singles,
                                          List<String> patterns) {
        for (var t = type; t != null; t = nextUserBase(t)) {
            if (!t.isRestriction()) {
                break;
            }
            for (var facet : t.asRestriction().getDeclaredFacets()) {
                if (facet == null || facet.getValue() == null) {
                    continue;
                }
                var value = facet.getValue().value;
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (XSFacet.FACET_PATTERN.equals(facet.getName())) {
                    if (!CXF_NOISE_PATTERN.equals(value) && !patterns.contains(value)) {
                        patterns.add(value);
                    }
                } else {
                    singles.putIfAbsent(facet.getName(), value);
                }
            }
        }
    }

    private static XSSimpleType nextUserBase(XSSimpleType type) {
        var base = type.getSimpleBaseType();
        if (base == null || XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(base.getTargetNamespace())) {
            return null;
        }
        return base;
    }

    private void applyStringSize(JCodeModel cm, JFieldVar field, Map<String, String> singles) {
        if (!"java.lang.String".equals(field.type().fullName())) {
            return;
        }
        var sizeFqcn = constraintPkg + "Size";
        if (AnnotationUtils.hasAnnotation(field, sizeFqcn)) {
            return;
        }
        var length = parseInt(singles.get(XSFacet.FACET_LENGTH));
        if (length != null) {
            field.annotate(cm.ref(sizeFqcn)).param("min", length).param("max", length);
            return;
        }
        var min = parseInt(singles.get(XSFacet.FACET_MINLENGTH));
        var max = parseInt(singles.get(XSFacet.FACET_MAXLENGTH));
        if (min == null && max == null) {
            return;
        }
        var anno = field.annotate(cm.ref(sizeFqcn));
        if (min != null) {
            anno.param("min", min);
        }
        if (max != null) {
            anno.param("max", max);
        }
    }

    private void applyBound(JCodeModel cm, JFieldVar field, String raw, boolean min, boolean inclusive) {
        if (raw == null) {
            return;
        }
        if (!inclusive) {
            var fqcn = constraintPkg + (min ? "DecimalMin" : "DecimalMax");
            if (!AnnotationUtils.hasAnnotation(field, fqcn)) {
                field.annotate(cm.ref(fqcn)).param("value", raw).param("inclusive", false);
            }
            return;
        }
        var asLong = parseLong(raw);
        if (asLong != null) {
            var fqcn = constraintPkg + (min ? "Min" : "Max");
            if (!AnnotationUtils.hasAnnotation(field, fqcn)) {
                field.annotate(cm.ref(fqcn)).param("value", asLong);
            }
        } else {
            var fqcn = constraintPkg + (min ? "DecimalMin" : "DecimalMax");
            if (!AnnotationUtils.hasAnnotation(field, fqcn)) {
                field.annotate(cm.ref(fqcn)).param("value", raw);
            }
        }
    }

    private void applyDigits(JCodeModel cm, JFieldVar field, Map<String, String> singles) {
        var total = parseInt(singles.get(XSFacet.FACET_TOTALDIGITS));
        if (total == null) {
            return;
        }
        var digitsFqcn = constraintPkg + "Digits";
        if (AnnotationUtils.hasAnnotation(field, digitsFqcn)) {
            return;
        }
        var fraction = parseInt(singles.get(XSFacet.FACET_FRACTIONDIGITS));
        var fractionInt = fraction != null ? fraction : 0;
        field.annotate(cm.ref(digitsFqcn))
            .param("integer", Math.max(0, total - fractionInt))
            .param("fraction", fractionInt);
    }

    // --- helpers ---

    private static XSSimpleType resolveSimpleType(CPropertyInfo prop) {
        var component = prop.getSchemaComponent();
        if (component == null) {
            return null;
        }
        return switch (component) {
            case XSParticle particle when particle.getTerm() instanceof XSElementDecl element
                && element.getType() instanceof XSSimpleType st -> st;
            case XSElementDecl element when element.getType() instanceof XSSimpleType st -> st;
            case XSAttributeUse use -> use.getDecl().getType();
            case XSAttributeDecl decl -> decl.getType();
            case XSSimpleType st -> st;
            default -> null;
        };
    }

    private static boolean isComplex(CPropertyInfo prop) {
        for (var ref : prop.ref()) {
            if (ref instanceof CClassInfo) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNillable(CElementPropertyInfo element) {
        for (var typeRef : element.getTypes()) {
            if (typeRef.isNillable()) {
                return true;
            }
        }
        return false;
    }

    private void annotateIfAbsent(JCodeModel cm, JFieldVar field, String fqcn) {
        if (!AnnotationUtils.hasAnnotation(field, fqcn)) {
            field.annotate(cm.ref(fqcn));
        }
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        return patterns.stream().anyMatch(p -> p.matcher(value).matches());
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
