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

import com.sun.codemodel.JType;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.generator.bean.field.FieldRenderer;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CBuiltinLeafInfo;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CClassRef;
import com.sun.tools.xjc.model.CDefaultValue;
import com.sun.tools.xjc.model.CElement;
import com.sun.tools.xjc.model.CElementInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CEnumConstant;
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.CNonElement;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CReferencePropertyInfo;
import com.sun.tools.xjc.model.CTypeRef;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.model.TypeUse;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XmlString;
import io.github.rawvoid.jaxb.utils.ModelUtils;
import jakarta.activation.MimeType;
import org.glassfish.jaxb.core.v2.model.core.WildcardMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import javax.xml.namespace.QName;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CELEMENTINFO_CLASSNAME_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.CENUMLEAFINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.TYPE_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.TYPE_USE_CONSTRUCTOR;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.getField;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.getFieldValue;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.newInstance;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * Merges structurally redundant generated beans in {@link #postProcessModel(Model, ErrorHandler)}.
 * <p>
 * Candidates share an owner package and {@linkplain #nameKey(String) name key}
 * ({@code AircraftCodeType} ≡ {@code AircraftCode}). Passes alternate until a fixed point:
 * </p>
 * <ol>
 *   <li><strong>Exact</strong> — cycle-safe structural equality. Nested bean/enum targets must share
 *       package + name key (isomorphic but differently named types are not interchangeable).</li>
 *   <li><strong>Related</strong> — empty extension of the host (always), and optional property-subset
 *       merges ({@code -merge-subset}).</li>
 * </ol>
 * <p>
 * By default only anonymous beans ({@link CClassInfo#getTypeName()} {@code null}) are deleted.
 * Nested beans under a merge pair are aligned before the outer merge; nested enums are merged or
 * re-parented without short-name collisions. Element-class cleanup is scoped to
 * {@code package + nameKey} pairs involved in merges.
 * </p>
 * <p>
 * {@code -preserve-wrapper-shells} (tri-state): pure collection shells
 * ({@link ModelUtils#isPureCollectionShell(CClassInfo)}) are not merged into non-shell hosts,
 * so later {@link ElementWrapperPlugin} flatten opportunities remain when Dedupe runs first.
 * Shell-to-shell exact merges remain allowed. Default is <strong>auto</strong>: on when
 * {@code -Xelement-wrapper} is also active; force with {@code true}/{@code false}.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xdedupe-class", description = "Merge structurally redundant generated beans (exact / optional subset)")
public class DedupeClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(DedupeClassPlugin.class);

    private static final Field CCLASSINFO_ELEMENTNAME_FIELD = getField(CClassInfo.class, "elementName");

    private static final Comparator<CClassInfo> HOST_ORDER = Comparator
        .comparingInt(DedupeClassPlugin::hostScore).reversed()
        .thenComparing(CClassInfo::fullName);

    @Option(name = "merge-subset", defaultValue = "false",
        description = "Merge subset beans into superset hosts (default: false)")
    Boolean mergeSubset;

    @Option(name = "anonymous-only", defaultValue = "true",
        description = "Only delete anonymous beans; named types may still be hosts (default: true)")
    Boolean anonymousOnly;

    @Option(name = "dry-run", defaultValue = "false",
        description = "Log planned merges without changing the model (default: false)")
    Boolean dryRun;

    /**
     * {@code null} = auto (on when {@link ElementWrapperPlugin} is active), {@code true}/{@code false} force.
     */
    @Option(name = "preserve-wrapper-shells",
        description = "Do not merge pure collection-wrapper shells into non-shell hosts "
            + "(default: auto — on when -Xelement-wrapper is also active; true/false to force)")
    Boolean preserveWrapperShells;

    // ── options ──────────────────────────────────────────────────────────────

    private boolean subsetEnabled() {
        return Boolean.TRUE.equals(mergeSubset);
    }

    private boolean anonymousOnly() {
        return !Boolean.FALSE.equals(anonymousOnly);
    }

    private boolean dryRun() {
        return Boolean.TRUE.equals(dryRun);
    }

    /**
     * Resolves tri-state {@link #preserveWrapperShells}: explicit value wins; otherwise on when
     * {@link ElementWrapperPlugin} is in {@link Options#activePlugins} (via {@link Model#options}).
     */
    private boolean resolvePreserveWrapperShells(Model model) {
        if (preserveWrapperShells != null) {
            return preserveWrapperShells;
        }
        var options = model == null ? null : model.options;
        if (options == null) {
            return false;
        }
        for (var plugin : options.activePlugins) {
            if (plugin instanceof ElementWrapperPlugin) {
                return true;
            }
        }
        return false;
    }

    // ── naming / host preference ─────────────────────────────────────────────

    /**
     * Name equivalence key: strip a trailing {@code Type} when the remainder is non-empty.
     * Case-sensitive ({@code AnyType}→{@code Any}; {@code Prototype} is unchanged).
     */
    static String nameKey(String shortName) {
        if (shortName != null && shortName.length() > 4 && shortName.endsWith("Type")) {
            return shortName.substring(0, shortName.length() - 4);
        }
        return shortName == null ? "" : shortName;
    }

    private static boolean isAnonymous(CClassInfo bean) {
        return bean.getTypeName() == null;
    }

    private static boolean isPackageLevel(CClassInfo bean) {
        return !(bean.parent() instanceof CClassInfo);
    }

    private static String packageName(CClassInfo bean) {
        return bean.getOwnerPackage().name();
    }

    private static String groupKey(CClassInfo bean) {
        return packageName(bean) + '\0' + nameKey(bean.shortName);
    }

    /**
     * Same merge identity as top-level candidates: package + nameKey.
     */
    private static boolean sameTypeIdentity(CClassInfo a, CClassInfo b) {
        return nameKey(a.shortName).equals(nameKey(b.shortName))
            && packageName(a).equals(packageName(b));
    }

    private static int hostScore(CClassInfo bean) {
        var score = 0;
        if (!isAnonymous(bean)) {
            score += 1_000_000;
        }
        if (isPackageLevel(bean)) {
            score += 10_000;
        }
        score += bean.getProperties().size() * 100;
        if (bean.shortName.endsWith("Type")) {
            score += 10;
        }
        return score;
    }

    /**
     * When {@code anonymousOnly} is on, named beans must be kept (only anonymous victims merge away).
     */
    private static boolean mustKeep(boolean anonymousOnly, CClassInfo bean) {
        return anonymousOnly && !isAnonymous(bean);
    }

    // ── structural equality ──────────────────────────────────────────────────

    /**
     * Exact structural equality for generated beans (order-sensitive properties).
     * Nested {@link CClassInfo} targets must also share package + nameKey.
     */
    static boolean structurallyEqual(CClassInfo a, CClassInfo b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return equalClasses(a, b, new HashSet<>());
    }

    private static boolean equalClasses(CClassInfo a, CClassInfo b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        var pair = IdentityPair.undirected(a, b);
        if (!visiting.add(pair)) {
            return true;
        }
        try {
            if (a.isAbstract() != b.isAbstract()) {
                return false;
            }
            if (a.isOrdered() != b.isOrdered()) {
                return false;
            }
            if (a.hasAttributeWildcard() != b.hasAttributeWildcard()) {
                return false;
            }
            if (!equalBase(a, b, visiting)) {
                return false;
            }
            var ap = a.getProperties();
            var bp = b.getProperties();
            if (ap.size() != bp.size()) {
                return false;
            }
            for (var i = 0; i < ap.size(); i++) {
                if (!equalProperty(ap.get(i), bp.get(i), visiting)) {
                    return false;
                }
            }
            return true;
        } finally {
            visiting.remove(pair);
        }
    }

    /**
     * Exact base compatibility: external {@link CClassRef} by FQCN, else structural equality
     * of generated bases (same package + nameKey).
     */
    private static boolean equalBase(CClassInfo a, CClassInfo b, Set<IdentityPair> visiting) {
        return switch (compareRefBases(a, b)) {
            case UNEQUAL -> false;
            case EQUAL -> true;
            case BOTH_GENERATED -> {
                var ab = a.getBaseClass();
                var bb = b.getBaseClass();
                if (ab == bb) {
                    yield true;
                }
                if (ab == null || bb == null || !sameTypeIdentity(ab, bb)) {
                    yield false;
                }
                yield equalClasses(ab, bb, visiting);
            }
        };
    }

    private static boolean equalProperty(CPropertyInfo a, CPropertyInfo b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (!commonPropertyEqual(a, b)) {
            return false;
        }
        return switch (a) {
            case CAttributePropertyInfo aa when b instanceof CAttributePropertyInfo ba ->
                Objects.equals(aa.getXmlName(), ba.getXmlName())
                    && aa.isRequired() == ba.isRequired()
                    && aa.id() == ba.id()
                    && Objects.equals(aa.getSchemaType(), ba.getSchemaType())
                    && equalNonElement(aa.getTarget(), ba.getTarget(), visiting);
            case CValuePropertyInfo av when b instanceof CValuePropertyInfo bv -> av.id() == bv.id()
                && Objects.equals(av.getSchemaType(), bv.getSchemaType())
                && equalNonElement(av.getTarget(), bv.getTarget(), visiting);
            case CElementPropertyInfo ae when b instanceof CElementPropertyInfo be -> ae.isRequired() == be.isRequired()
                && ae.isValueList() == be.isValueList()
                && ae.id() == be.id()
                && Objects.equals(ae.getSchemaType(), be.getSchemaType())
                && equalElementTypes(ae, be, visiting);
            case CReferencePropertyInfo ar when b instanceof CReferencePropertyInfo br -> ar.isRequired() == br.isRequired()
                && ar.isMixed() == br.isMixed()
                && ar.id() == br.id()
                && Objects.equals(wildcardKey(ar), wildcardKey(br))
                && ar.isDummy() == br.isDummy()
                && ar.isContent() == br.isContent()
                && ar.isMixedExtendedCust() == br.isMixedExtendedCust()
                && equalReferenceElements(ar, br, visiting);
            default -> false;
        };
    }

    /**
     * Shared property surface for exact equality and subset compatibility.
     */
    private static boolean commonPropertyEqual(CPropertyInfo a, CPropertyInfo b) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (!Objects.equals(a.getName(false), b.getName(false))) {
            return false;
        }
        if (!Objects.equals(a.getName(true), b.getName(true))) {
            return false;
        }
        if (a.isCollection() != b.isCollection()) {
            return false;
        }
        if (!Objects.equals(adapterKey(a), adapterKey(b))) {
            return false;
        }
        if (!Objects.equals(mimeKey(a), mimeKey(b))) {
            return false;
        }
        if (a.inlineBinaryData != b.inlineBinaryData) {
            return false;
        }
        if (!Objects.equals(baseTypeKey(a.baseType), baseTypeKey(b.baseType))) {
            return false;
        }
        if (!equalRealization(a.realization, b.realization)) {
            return false;
        }
        return equalDefaults(a.defaultValue, b.defaultValue);
    }

    private static boolean equalElementTypes(
        CElementPropertyInfo a,
        CElementPropertyInfo b,
        Set<IdentityPair> visiting
    ) {
        var at = a.getTypes();
        var bt = b.getTypes();
        if (at.size() != bt.size()) {
            return false;
        }
        for (var i = 0; i < at.size(); i++) {
            if (!equalTypeRef(at.get(i), bt.get(i), visiting)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalTypeRef(CTypeRef a, CTypeRef b, Set<IdentityPair> visiting) {
        if (!Objects.equals(a.getTagName(), b.getTagName())) {
            return false;
        }
        if (a.isNillable() != b.isNillable()) {
            return false;
        }
        if (!Objects.equals(a.getDefaultValue(), b.getDefaultValue())) {
            return false;
        }
        return equalNonElement(a.getTarget(), b.getTarget(), visiting);
    }

    private static boolean equalReferenceElements(
        CReferencePropertyInfo a,
        CReferencePropertyInfo b,
        Set<IdentityPair> visiting
    ) {
        var ae = new ArrayList<>(a.getElements());
        var be = new ArrayList<>(b.getElements());
        if (ae.size() != be.size()) {
            return false;
        }
        var used = new boolean[be.size()];
        for (var elA : ae) {
            var matched = false;
            for (var i = 0; i < be.size(); i++) {
                if (used[i]) {
                    continue;
                }
                if (equalElement(elA, be.get(i), visiting)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalElement(CElement a, CElement b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (a instanceof CClassInfo ca && b instanceof CClassInfo cb) {
            if (!sameTypeIdentity(ca, cb)) {
                return false;
            }
            return equalClasses(ca, cb, visiting);
        }
        if (a instanceof CElementInfo ea && b instanceof CElementInfo eb) {
            return equalElementInfo(ea, eb, visiting);
        }
        return false;
    }

    private static boolean equalElementInfo(CElementInfo a, CElementInfo b, Set<IdentityPair> visiting) {
        if (!Objects.equals(a.getElementName(), b.getElementName())) {
            return false;
        }
        if (a.hasClass() != b.hasClass()) {
            return false;
        }
        if (a.hasClass() && !Objects.equals(nameKey(a.shortName()), nameKey(b.shortName()))) {
            return false;
        }
        var sa = a.getScope();
        var sb = b.getScope();
        if (sa != sb) {
            if (sa instanceof CClassInfo ca && sb instanceof CClassInfo cb) {
                if (!sameTypeIdentity(ca, cb) || !equalClasses(ca, cb, visiting)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        if (!equalNonElement(a.getContentType(), b.getContentType(), visiting)) {
            return false;
        }
        // Element-class property carries adapter / mime / defaults for the binding.
        var pa = a.getProperty();
        var pb = b.getProperty();
        if (pa == null && pb == null) {
            return true;
        }
        if (pa == null || pb == null) {
            return false;
        }
        return commonPropertyEqual(pa, pb)
            && pa.id() == pb.id()
            && Objects.equals(pa.getSchemaType(), pb.getSchemaType());
    }

    private static boolean equalNonElement(CNonElement a, CNonElement b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return switch (a) {
            // Do not treat isomorphic but differently named beans as interchangeable.
            case CClassInfo ca when b instanceof CClassInfo cb ->
                sameTypeIdentity(ca, cb) && equalClasses(ca, cb, visiting);
            case CEnumLeafInfo ea when b instanceof CEnumLeafInfo eb -> equalEnums(ea, eb);
            case CBuiltinLeafInfo ba when b instanceof CBuiltinLeafInfo bb ->
                Objects.equals(typeNameOf(ba), typeNameOf(bb));
            case CClassRef ra when b instanceof CClassRef rb -> Objects.equals(ra.fullName(), rb.fullName());
            default -> a.getClass() == b.getClass() && Objects.equals(String.valueOf(a), String.valueOf(b));
        };
    }

    private static String typeNameOf(CBuiltinLeafInfo leaf) {
        var qn = leaf.getTypeName();
        return qn == null ? leaf.toString() : qn.toString();
    }

    static boolean equalEnums(CEnumLeafInfo a, CEnumLeafInfo b) {
        if (a == b) {
            return true;
        }
        if (!Objects.equals(nameKey(a.shortName), nameKey(b.shortName))) {
            return false;
        }
        if (!Objects.equals(a.getTypeName(), b.getTypeName())) {
            return false;
        }
        if (!equalNonElement(a.base, b.base, new HashSet<>())) {
            return false;
        }
        var ac = List.copyOf(a.getConstants());
        var bc = List.copyOf(b.getConstants());
        if (ac.size() != bc.size()) {
            return false;
        }
        for (var i = 0; i < ac.size(); i++) {
            CEnumConstant ca = ac.get(i);
            CEnumConstant cb = bc.get(i);
            if (!Objects.equals(ca.getName(), cb.getName())
                || !Objects.equals(ca.getLexicalValue(), cb.getLexicalValue())) {
                return false;
            }
        }
        return true;
    }

    private static String adapterKey(CPropertyInfo prop) {
        var adapter = prop.getAdapter();
        if (adapter == null) {
            return "";
        }
        var known = adapter.getAdapterIfKnown();
        if (known != null) {
            return known.getName();
        }
        try {
            return adapter.adapterType.fullName();
        } catch (UnsupportedOperationException e) {
            return "?";
        }
    }

    private static String mimeKey(CPropertyInfo prop) {
        MimeType mime = prop.getExpectedMimeType();
        return mime == null ? "" : mime.toString();
    }

    private static String baseTypeKey(JType type) {
        return type == null ? "" : type.fullName();
    }

    private static boolean equalRealization(FieldRenderer a, FieldRenderer b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.getClass() == b.getClass();
    }

    private static String wildcardKey(CReferencePropertyInfo ref) {
        WildcardMode mode = ref.getWildcard();
        return mode == null ? "-" : mode.name();
    }

    /**
     * Compare defaults by identity, then by reflected lexical {@link XmlString} when available.
     * Different non-null defaults without extractable lexical are treated as unequal.
     */
    private static boolean equalDefaults(CDefaultValue a, CDefaultValue b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        var la = defaultLexical(a);
        var lb = defaultLexical(b);
        if (la == null || lb == null) {
            // Cannot prove equality of opaque defaults.
            return false;
        }
        return la.equals(lb);
    }

    private static String defaultLexical(CDefaultValue value) {
        for (var field : value.getClass().getDeclaredFields()) {
            if (XmlString.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    var xs = (XmlString) field.get(value);
                    return xs == null ? null : xs.value;
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ── subset ───────────────────────────────────────────────────────────────

    static boolean isEmptyExtensionOf(CClassInfo victim, CClassInfo host) {
        return victim != host
            && host != null
            && victim.getBaseClass() == host
            && victim.getProperties().isEmpty()
            && (!victim.hasAttributeWildcard() || host.hasAttributeWildcard());
    }

    static boolean isStructuralSubset(CClassInfo subset, CClassInfo host) {
        if (subset == host) {
            return true;
        }
        if (subset == null || host == null) {
            return false;
        }
        if (!sameTypeIdentity(subset, host)) {
            return false;
        }
        return isStructuralSubset(subset, host, new HashSet<>());
    }

    private static boolean isStructuralSubset(
        CClassInfo subset,
        CClassInfo host,
        Set<DirectedPair> visiting
    ) {
        if (subset == host) {
            return true;
        }
        var pair = new DirectedPair(subset, host);
        if (!visiting.add(pair)) {
            return true;
        }
        try {
            if (structurallyEqual(subset, host)) {
                return true;
            }
            // Align class-level flags with exact (API / propOrder stability).
            if (subset.isAbstract() != host.isAbstract()) {
                return false;
            }
            if (subset.isOrdered() != host.isOrdered()) {
                return false;
            }
            if (!subsetBaseCompatible(subset, host, visiting)) {
                return false;
            }
            if (subset.hasAttributeWildcard() && !host.hasAttributeWildcard()) {
                return false;
            }
            for (var sp : subset.getProperties()) {
                var hp = findProperty(host, sp.getName(false));
                if (hp == null || !propertySubsetCompatible(sp, hp, visiting)) {
                    return false;
                }
            }
            return true;
        } finally {
            visiting.remove(pair);
        }
    }

    /**
     * Subset base compatibility: same external ref, same generated base, subset extends host,
     * or recursive structural subset of bases.
     */
    private static boolean subsetBaseCompatible(
        CClassInfo subset,
        CClassInfo host,
        Set<DirectedPair> visiting
    ) {
        return switch (compareRefBases(subset, host)) {
            case UNEQUAL -> false;
            case EQUAL -> true;
            case BOTH_GENERATED -> {
                var subsetBase = subset.getBaseClass();
                var hostBase = host.getBaseClass();
                if (subsetBase == hostBase || subsetBase == host) {
                    yield true;
                }
                if (subsetBase == null || hostBase == null || !sameTypeIdentity(subsetBase, hostBase)) {
                    yield false;
                }
                yield isStructuralSubset(subsetBase, hostBase, visiting);
            }
        };
    }

    private enum RefBaseRelation {
        /** Neither side uses {@link CClassRef}; compare generated bases next. */
        BOTH_GENERATED,
        /** Both use the same external class. */
        EQUAL,
        /** External refs missing on one side or FQCNs differ. */
        UNEQUAL
    }

    /** Shared {@link CClassRef} base check used by exact and subset base compatibility. */
    private static RefBaseRelation compareRefBases(CClassInfo a, CClassInfo b) {
        var aRef = a.getRefBaseClass();
        var bRef = b.getRefBaseClass();
        if (aRef == null && bRef == null) {
            return RefBaseRelation.BOTH_GENERATED;
        }
        if (aRef == null || bRef == null) {
            return RefBaseRelation.UNEQUAL;
        }
        return Objects.equals(aRef.fullName(), bRef.fullName())
            ? RefBaseRelation.EQUAL
            : RefBaseRelation.UNEQUAL;
    }

    private static CPropertyInfo findProperty(CClassInfo bean, String name) {
        for (var prop : bean.getProperties()) {
            if (prop.getName(false).equals(name)) {
                return prop;
            }
        }
        return null;
    }

    private static boolean propertySubsetCompatible(
        CPropertyInfo subset,
        CPropertyInfo host,
        Set<DirectedPair> visiting
    ) {
        // Shared surface must match exactly (names, mime, defaults, etc.).
        if (!commonPropertyEqual(subset, host)) {
            return false;
        }
        return switch (subset) {
            case CAttributePropertyInfo sa when host instanceof CAttributePropertyInfo ha ->
                Objects.equals(sa.getXmlName(), ha.getXmlName())
                    && sa.isRequired() == ha.isRequired()
                    && sa.id() == ha.id()
                    && Objects.equals(sa.getSchemaType(), ha.getSchemaType())
                    && nonElementSubsetCompatible(sa.getTarget(), ha.getTarget(), visiting);
            case CValuePropertyInfo sv when host instanceof CValuePropertyInfo hv -> sv.id() == hv.id()
                && Objects.equals(sv.getSchemaType(), hv.getSchemaType())
                && nonElementSubsetCompatible(sv.getTarget(), hv.getTarget(), visiting);
            case CElementPropertyInfo se when host instanceof CElementPropertyInfo he -> se.isRequired() == he.isRequired()
                && se.isValueList() == he.isValueList()
                && se.id() == he.id()
                && Objects.equals(se.getSchemaType(), he.getSchemaType())
                && elementTypesSubset(se, he, visiting);
            case CReferencePropertyInfo sr when host instanceof CReferencePropertyInfo hr -> sr.isRequired() == hr.isRequired()
                && sr.isMixed() == hr.isMixed()
                && sr.id() == hr.id()
                && Objects.equals(wildcardKey(sr), wildcardKey(hr))
                && sr.isDummy() == hr.isDummy()
                && sr.isContent() == hr.isContent()
                && sr.isMixedExtendedCust() == hr.isMixedExtendedCust()
                && referenceSubset(sr, hr, visiting);
            default -> false;
        };
    }

    private static boolean elementTypesSubset(
        CElementPropertyInfo subset,
        CElementPropertyInfo host,
        Set<DirectedPair> visiting
    ) {
        for (var st : subset.getTypes()) {
            var matched = false;
            for (var ht : host.getTypes()) {
                // Defaults must match exactly (host default must not silently appear).
                if (Objects.equals(st.getTagName(), ht.getTagName())
                    && st.isNillable() == ht.isNillable()
                    && Objects.equals(st.getDefaultValue(), ht.getDefaultValue())
                    && nonElementSubsetCompatible(st.getTarget(), ht.getTarget(), visiting)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean referenceSubset(
        CReferencePropertyInfo subset,
        CReferencePropertyInfo host,
        Set<DirectedPair> visiting
    ) {
        for (var se : subset.getElements()) {
            var matched = false;
            for (var he : host.getElements()) {
                if (se == he) {
                    matched = true;
                    break;
                }
                if (se instanceof CClassInfo sc && he instanceof CClassInfo hc
                    && nonElementSubsetCompatible(sc, hc, visiting)) {
                    matched = true;
                    break;
                }
                if (se instanceof CElementInfo sei && he instanceof CElementInfo hei
                    && equalElementInfo(sei, hei, new HashSet<>())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean nonElementSubsetCompatible(
        CNonElement subset,
        CNonElement host,
        Set<DirectedPair> visiting
    ) {
        if (subset == host) {
            return true;
        }
        if (subset instanceof CClassInfo sc && host instanceof CClassInfo hc) {
            if (!sameTypeIdentity(sc, hc)) {
                return false;
            }
            if (structurallyEqual(sc, hc)) {
                return true;
            }
            return isStructuralSubset(sc, hc, visiting);
        }
        if (subset instanceof CEnumLeafInfo se && host instanceof CEnumLeafInfo he) {
            return equalEnums(se, he);
        }
        if (subset instanceof CClassRef sr && host instanceof CClassRef hr) {
            return Objects.equals(sr.fullName(), hr.fullName());
        }
        return equalNonElement(subset, host, new HashSet<>());
    }

    // ── orchestration ────────────────────────────────────────────────────────

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var preserveShells = resolvePreserveWrapperShells(model);
        if (preserveShells && preserveWrapperShells == null) {
            log.debug("Auto-enabled preserve-wrapper-shells (ElementWrapperPlugin is active)");
        }
        var session = new Session(dryRun(), subsetEnabled(), anonymousOnly(), preserveShells);
        var merged = mergeFixedPoint(model, session);
        if (!session.dry && merged > 0) {
            collapseRedundantElementClasses(model, session.mergedPackageNameKeys);
            warnObjectFactoryCollisions(model);
        }
        if (merged > 0) {
            log.info("Deduped {} bean merge(s){}", merged, session.dry ? " (dry-run)" : "");
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * Alternate exact and related until no further merges (related can unlock new exact pairs).
     */
    private int mergeFixedPoint(Model model, Session session) {
        var total = 0;
        boolean progress;
        do {
            progress = false;
            var exact = mergeExact(model, session);
            if (exact > 0) {
                total += exact;
                progress = true;
            }
            var related = mergeRelatedSweep(model, session);
            if (related > 0) {
                total += related;
                progress = true;
            }
        } while (progress && !session.dry);
        return total;
    }

    private int mergeExact(Model model, Session session) {
        Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
        for (var bean : List.copyOf(model.beans().values())) {
            byKey.computeIfAbsent(groupKey(bean), k -> new ArrayList<>()).add(bean);
        }

        var count = 0;
        for (var members : byKey.values()) {
            if (members.size() < 2) {
                continue;
            }
            var clusters = new ArrayList<List<CClassInfo>>();
            for (var bean : members) {
                List<CClassInfo> joined = null;
                for (var cluster : clusters) {
                    if (structurallyEqual(bean, cluster.getFirst())) {
                        joined = cluster;
                        break;
                    }
                }
                if (joined == null) {
                    joined = new ArrayList<>();
                    clusters.add(joined);
                }
                joined.add(bean);
            }

            for (var cluster : clusters) {
                if (cluster.size() < 2) {
                    continue;
                }
                cluster.sort(HOST_ORDER);
                // Highest hostScore is first; only delete lower-priority victims into it.
                var host = cluster.getFirst();
                for (var i = 1; i < cluster.size(); i++) {
                    var victim = cluster.get(i);
                    if (mustKeep(session.anonymousOnly, victim)) {
                        continue;
                    }
                    if (tryMerge(model, session, victim, host, "exact", false)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Single related sweep (empty-ext / subset); fixed-point is the outer loop.
     */
    private int mergeRelatedSweep(Model model, Session session) {
        var count = 0;
        Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
        for (var bean : model.beans().values()) {
            byKey.computeIfAbsent(groupKey(bean), k -> new ArrayList<>()).add(bean);
        }
        for (var members : byKey.values()) {
            if (members.size() < 2) {
                continue;
            }
            members.sort(HOST_ORDER);
            for (var host : List.copyOf(members)) {
                if (!model.beans().containsValue(host)) {
                    continue;
                }
                for (var victim : List.copyOf(members)) {
                    if (victim == host || !model.beans().containsValue(victim)) {
                        continue;
                    }
                    if (mustKeep(session.anonymousOnly, victim)) {
                        continue;
                    }
                    String reason = null;
                    if (isEmptyExtensionOf(victim, host)) {
                        reason = "empty-ext";
                    } else if (session.subset && isStructuralSubset(victim, host)) {
                        reason = "subset";
                    }
                    if (reason == null) {
                        continue;
                    }
                    if (session.countedMerges.contains(IdentityPair.directed(victim, host))) {
                        continue;
                    }
                    if (tryMerge(model, session, victim, host, reason, false)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ── merge execution ──────────────────────────────────────────────────────

    /**
     * @param allowCrossNestedParent when true, nested beans under different parents may merge
     *                               (used by {@link #prepareNestedMerges} while aligning children
     *                               of an outer victim/host pair).
     */
    private boolean tryMerge(
        Model model,
        Session session,
        CClassInfo victim,
        CClassInfo host,
        String reason,
        boolean allowCrossNestedParent
    ) {
        if (victim == host
            || !model.beans().containsValue(victim)
            || !model.beans().containsValue(host)
            || !sameTypeIdentity(victim, host)
            || isNestingAncestor(victim, host)) {
            return false;
        }

        if (!isPackageLevel(host) && host.parent() != victim.parent() && !allowCrossNestedParent) {
            log.debug(
                "Skip dedupe {}: cross-hierarchy nested '{}' vs '{}'",
                reason, victim.fullName(), host.fullName()
            );
            return false;
        }

        if (victim.isElement() && host.isElement()
            && !Objects.equals(victim.getElementName(), host.getElementName())) {
            log.warn(
                "Skip dedupe {}: both '{}' and '{}' are element-classes with different roots ({} vs {})",
                reason,
                victim.fullName(),
                host.fullName(),
                victim.getElementName(),
                host.getElementName()
            );
            return false;
        }

        // Keep pure shells available for -Xelement-wrapper when this flag is on: never replace
        // a shell type with a non-shell host (typical -merge-subset path). Shell→shell is fine.
        if (session.preserveWrapperShells
            && ModelUtils.isPureCollectionShell(victim)
            && !ModelUtils.isPureCollectionShell(host)) {
            log.debug(
                "Skip dedupe {}: pure collection shell '{}' must not merge into non-shell '{}'",
                reason, victim.fullName(), host.fullName()
            );
            return false;
        }

        if (!prepareNestedMerges(model, session, victim, host)) {
            return false;
        }
        if (!prepareNestedEnums(model, session, victim, host)) {
            return false;
        }

        log.info(
            "Dedupe {}: '{}' -> '{}' (nameKey={})",
            reason, victim.fullName(), host.fullName(), nameKey(victim.shortName)
        );

        session.countedMerges.add(IdentityPair.directed(victim, host));

        if (session.dry) {
            return true;
        }

        session.mergedPackageNameKeys.add(packageName(victim) + '\0' + nameKey(victim.shortName));
        if (victim.isElement()) {
            session.mergedPackageNameKeys.add(
                packageName(victim) + '\0' + nameKey(localPart(victim.getElementName(), victim.shortName))
            );
        }

        if (victim.isElement() && !host.isElement()) {
            setFieldValue(CCLASSINFO_ELEMENTNAME_FIELD, host, victim.getElementName());
        }
        ModelUtils.replaceClassReferences(model, victim, host);
        ModelUtils.removeClass(model, victim);
        return true;
    }

    private static String localPart(QName name, String fallback) {
        return name == null ? fallback : name.getLocalPart();
    }

    private boolean prepareNestedMerges(Model model, Session session, CClassInfo victim, CClassInfo host) {
        for (var child : List.copyOf(directNestedBeans(model, victim))) {
            if (!model.beans().containsValue(child)) {
                continue;
            }
            var matches = new ArrayList<CClassInfo>();
            for (var hc : directNestedBeans(model, host)) {
                if (sameTypeIdentity(child, hc)) {
                    matches.add(hc);
                }
            }
            if (matches.isEmpty()) {
                var childNorm = child.shortName.toLowerCase(Locale.ROOT);
                for (var hc : directNestedBeans(model, host)) {
                    if (hc.shortName.toLowerCase(Locale.ROOT).equals(childNorm)) {
                        log.debug("Skip dedupe: nested name clash {} under {}", child.shortName, host.fullName());
                        return false;
                    }
                }
                continue;
            }

            matches.sort(HOST_ORDER);
            var target = matches.getFirst();
            String reason = null;
            if (structurallyEqual(child, target)) {
                reason = "exact-nested";
            } else if (isEmptyExtensionOf(child, target)) {
                reason = "empty-ext-nested";
            } else if (session.subset && isStructuralSubset(child, target)) {
                reason = "subset-nested";
            }
            if (reason == null) {
                log.debug(
                    "Skip dedupe: nested {} incompatible with {} under {}",
                    child.fullName(), target.fullName(), host.fullName()
                );
                return false;
            }

            // Prefer keeping non-deletable (named when -anonymous-only) or higher hostScore.
            // Not dead: default anonymous-only=true still applies when a nested bean is named
            // (typeName != null); with -anonymous-only=false both are deletable and HOST_ORDER wins.
            var to = preferredNestedHost(child, target, session.anonymousOnly);
            var from = to == child ? target : child;
            // Cross-parent is intentional: children of victim/host outer pair.
            if (!tryMerge(model, session, from, to, reason, true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Choose which of two nested beans to keep when aligning children of an outer merge pair.
     */
    private static CClassInfo preferredNestedHost(CClassInfo a, CClassInfo b, boolean anonymousOnly) {
        var aKeep = mustKeep(anonymousOnly, a);
        var bKeep = mustKeep(anonymousOnly, b);
        if (aKeep != bKeep) {
            return aKeep ? a : b;
        }
        return hostScore(a) >= hostScore(b) ? a : b;
    }

    /**
     * Merge equal nested enums into the host, or re-parent unique ones; refuse short-name clashes.
     */
    private boolean prepareNestedEnums(Model model, Session session, CClassInfo victim, CClassInfo host) {
        for (var victimEnum : List.copyOf(directNestedEnums(model, victim))) {
            CEnumLeafInfo match = null;
            for (var hostEnum : directNestedEnums(model, host)) {
                if (equalEnums(victimEnum, hostEnum)) {
                    match = hostEnum;
                    break;
                }
            }
            if (match != null) {
                log.info(
                    "Dedupe exact-enum: '{}' -> '{}'",
                    fullEnumName(victimEnum), fullEnumName(match)
                );
                session.countedMerges.add(IdentityPair.directed(victimEnum, match));
                if (!session.dry) {
                    replaceEnumReferences(model, victimEnum, match);
                    model.enums().remove(victimEnum.getClazz());
                }
                continue;
            }
            for (var hostEnum : directNestedEnums(model, host)) {
                if (nameKey(victimEnum.shortName).equals(nameKey(hostEnum.shortName))) {
                    log.debug(
                        "Skip dedupe: nested enum clash {} vs {} under {}",
                        fullEnumName(victimEnum), fullEnumName(hostEnum), host.fullName()
                    );
                    return false;
                }
            }
            if (!session.dry) {
                setFieldValue(CENUMLEAFINFO_PARENT_FIELD, victimEnum, host);
            }
        }
        return true;
    }

    private static String fullEnumName(CEnumLeafInfo e) {
        var parent = e.parent.fullName();
        return parent.isEmpty() ? e.shortName : parent + '.' + e.shortName;
    }

    private static List<CClassInfo> directNestedBeans(Model model, CClassInfo parent) {
        var nested = new ArrayList<CClassInfo>();
        for (var bean : model.beans().values()) {
            if (bean.parent() == parent) {
                nested.add(bean);
            }
        }
        return nested;
    }

    private static List<CEnumLeafInfo> directNestedEnums(Model model, CClassInfo parent) {
        var nested = new ArrayList<CEnumLeafInfo>();
        for (var enumInfo : model.enums().values()) {
            if (enumInfo.parent == parent) {
                nested.add(enumInfo);
            }
        }
        return nested;
    }

    private static boolean isNestingAncestor(CClassInfo ancestor, CClassInfo node) {
        var p = node.parent();
        while (p instanceof CClassInfo c) {
            if (c == ancestor) {
                return true;
            }
            p = c.parent();
        }
        return false;
    }

    /**
     * Retarget properties / element infos that reference {@code from} enum to {@code to}.
     */
    private static void replaceEnumReferences(Model model, CEnumLeafInfo from, CEnumLeafInfo to) {
        if (from == to) {
            return;
        }
        for (var bean : model.beans().values()) {
            for (var prop : bean.getProperties()) {
                replaceEnumInProperty(prop, from, to);
            }
        }
        for (var elementInfo : model.getAllElements()) {
            var prop = elementInfo.getProperty();
            if (prop != null) {
                replaceEnumInProperty(prop, from, to);
            }
        }
    }

    private static void replaceEnumInProperty(CPropertyInfo property, CEnumLeafInfo from, CEnumLeafInfo to) {
        switch (property) {
            case CElementPropertyInfo element -> {
                var types = element.getTypes();
                for (var i = 0; i < types.size(); i++) {
                    var typeRef = types.get(i);
                    if (typeRef.getTarget() == from) {
                        types.set(i, new CTypeRef(
                            to,
                            typeRef.getTagName(),
                            typeRef.getTypeName(),
                            typeRef.isNillable(),
                            typeRef.defaultValue
                        ));
                    }
                }
            }
            case CAttributePropertyInfo attr -> replaceSingleTypeEnum(attr, from, to);
            case CValuePropertyInfo value -> replaceSingleTypeEnum(value, from, to);
            default -> {
                // Reference properties do not carry enum leaf targets as CNonElement typically.
            }
        }
    }

    private static void replaceSingleTypeEnum(CPropertyInfo property, CEnumLeafInfo from, CEnumLeafInfo to) {
        var typeUse = (TypeUse) getFieldValue(TYPE_FIELD, property);
        if (typeUse == null || typeUse.getInfo() != from) {
            return;
        }
        var newTypeUse = newInstance(
            TYPE_USE_CONSTRUCTOR,
            to,
            typeUse.isCollection(),
            typeUse.idUse(),
            typeUse.getExpectedMimeType(),
            typeUse.getAdapterUse()
        );
        setFieldValue(TYPE_FIELD, property, newTypeUse);
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    /**
     * Clear element class names only for package+nameKey pairs that participated in a merge.
     */
    private static void collapseRedundantElementClasses(Model model, Set<String> mergedPackageNameKeys) {
        if (mergedPackageNameKeys.isEmpty()) {
            return;
        }
        var cleared = 0;
        for (var elementInfo : model.getAllElements()) {
            if (!elementInfo.hasClass()) {
                continue;
            }
            var content = elementInfo.getContentType();
            if (!(content instanceof CClassInfo contentClass)) {
                continue;
            }
            var elementKey = nameKey(elementInfo.shortName());
            var contentKey = nameKey(contentClass.shortName);
            if (!elementKey.equals(contentKey)) {
                continue;
            }
            var pkg = contentClass.getOwnerPackage().name();
            var scoped = pkg + '\0' + elementKey;
            if (!mergedPackageNameKeys.contains(scoped)
                && !mergedPackageNameKeys.contains(pkg + '\0' + contentKey)) {
                continue;
            }
            setFieldValue(CELEMENTINFO_CLASSNAME_FIELD, elementInfo, null);
            cleared++;
        }
        if (cleared > 0) {
            log.info("Cleared {} redundant element class name(s) after dedupe", cleared);
        }
    }

    private static void warnObjectFactoryCollisions(Model model) {
        for (var group : ModelUtils.objectFactorySqueezedCollisions(model)) {
            log.warn(
                "ObjectFactory squeezed-name collision after dedupe (package-local createXxx): {}",
                group.stream().map(CClassInfo::fullName).toList()
            );
        }
    }

    // ── session / identity ───────────────────────────────────────────────────

    private static final class Session {
        final boolean dry;
        final boolean subset;
        final boolean anonymousOnly;
        final boolean preserveWrapperShells;
        final Set<IdentityPair> countedMerges = new HashSet<>();
        /**
         * {@code package + '\0' + nameKey} of merge victims (scopes element-class cleanup).
         */
        final Set<String> mergedPackageNameKeys = new HashSet<>();

        Session(boolean dry, boolean subset, boolean anonymousOnly, boolean preserveWrapperShells) {
            this.dry = dry;
            this.subset = subset;
            this.anonymousOnly = anonymousOnly;
            this.preserveWrapperShells = preserveWrapperShells;
        }
    }

    private static final class IdentityPair {
        private final Object a;
        private final Object b;
        private final boolean directed;
        private final int hash;

        private IdentityPair(Object a, Object b, boolean directed) {
            this.a = a;
            this.b = b;
            this.directed = directed;
            if (directed) {
                this.hash = 31 * System.identityHashCode(a) + System.identityHashCode(b);
            } else {
                this.hash = System.identityHashCode(a) + System.identityHashCode(b);
            }
        }

        static IdentityPair undirected(Object a, Object b) {
            if (System.identityHashCode(a) <= System.identityHashCode(b)) {
                return new IdentityPair(a, b, false);
            }
            return new IdentityPair(b, a, false);
        }

        static IdentityPair directed(Object from, Object to) {
            return new IdentityPair(from, to, true);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IdentityPair other) || directed != other.directed) {
                return false;
            }
            if (directed) {
                return a == other.a && b == other.b;
            }
            return (a == other.a && b == other.b) || (a == other.b && b == other.a);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class DirectedPair {
        private final CClassInfo subset;
        private final CClassInfo host;
        private final int hash;

        DirectedPair(CClassInfo subset, CClassInfo host) {
            this.subset = subset;
            this.host = host;
            this.hash = 31 * System.identityHashCode(subset) + System.identityHashCode(host);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DirectedPair other)) {
                return false;
            }
            return subset == other.subset && host == other.host;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
