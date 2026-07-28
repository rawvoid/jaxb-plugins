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
import io.github.rawvoid.jaxb.utils.ModelUtils;
import org.glassfish.jaxb.core.v2.model.core.WildcardMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import javax.xml.namespace.QName;
import java.lang.reflect.Field;
import java.util.*;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CELEMENTINFO_CLASSNAME_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.CENUMLEAFINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.getField;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * Merges structurally redundant generated beans in {@link #postProcessModel(Model, ErrorHandler)}.
 * <p>
 * Candidates share an owner package and {@linkplain #nameKey(String) name key}
 * ({@code AircraftCodeType} ≡ {@code AircraftCode}). Two passes:
 * </p>
 * <ol>
 *   <li><strong>Exact</strong> — cycle-safe structural equality (property order, {@code required},
 *       {@code isOrdered}/{@code isAbstract}, external {@link CClassRef} identity, reference
 *       {@link WildcardMode}, element QNames, adapters, ID, schema types, defaults presence).</li>
 *   <li><strong>Related</strong> — empty extension of the host (always; IATA/NDC
 *       {@code class Foo extends FooType {}}), and optional property-subset merges
 *       ({@code -merge-subset}). Subset recursion uses {@code (subset, host)} pair cycle detection.</li>
 * </ol>
 * <p>
 * By default only anonymous beans ({@link CClassInfo#getTypeName()} {@code null}) are deleted;
 * named types may still be hosts. Package-level hosts may absorb any same-package victim; nested
 * hosts only absorb siblings under the same parent.
 * </p>
 * <p>
 * Element-class cleanup runs only after a successful merge and only for name keys involved in
 * those merges. Merging two element-classes with different root {@link QName}s is refused.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xdedupe-class", description = "Merge structurally redundant generated beans (exact / optional subset)")
public class DedupeClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(DedupeClassPlugin.class);

    /**
     * Final in XJC; set when collapsing an element-class into a pure type host.
     */
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

    /** Identity pairs already counted (dry-run or live) to avoid double-reporting exact+related. */
    private final Set<IdentityPair> countedMerges = new HashSet<>();

    /** Name keys of beans removed by a successful (non-dry) merge — scopes element-class cleanup. */
    private final Set<String> mergedVictimNameKeys = new HashSet<>();

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

    /**
     * Package-local name-key group (cross-package merges are never considered).
     */
    private static String groupKey(CClassInfo bean) {
        return packageName(bean) + '\0' + nameKey(bean.shortName);
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

    private boolean mayDelete(CClassInfo bean) {
        return !anonymousOnly() || isAnonymous(bean);
    }

    // ── cycle-safe structural equality (exact) ───────────────────────────────

    /**
     * Exact structural equality for generated beans (order-sensitive properties).
     * Cycles use undirected identity pairs and co-inductive true on re-entry.
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
            return true; // co-inductive: same pair re-entered under a cycle
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
            // Exact: preserve declaration order (sequence particle order).
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

    private static boolean equalBase(CClassInfo a, CClassInfo b, Set<IdentityPair> visiting) {
        var aRef = a.getRefBaseClass();
        var bRef = b.getRefBaseClass();
        if (aRef != null || bRef != null) {
            if (aRef == null || bRef == null) {
                return false;
            }
            return Objects.equals(aRef.fullName(), bRef.fullName());
        }
        var ab = a.getBaseClass();
        var bb = b.getBaseClass();
        if (ab == null && bb == null) {
            return true;
        }
        if (ab == null || bb == null) {
            return false;
        }
        return equalClasses(ab, bb, visiting);
    }

    private static boolean equalProperty(CPropertyInfo a, CPropertyInfo b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (!Objects.equals(a.getName(false), b.getName(false))) {
            return false;
        }
        if (a.isCollection() != b.isCollection()) {
            return false;
        }
        if (!Objects.equals(adapterKey(a), adapterKey(b))) {
            return false;
        }
        if (hasDefault(a) != hasDefault(b)) {
            return false;
        }
        return switch (a) {
            case CAttributePropertyInfo aa when b instanceof CAttributePropertyInfo ba ->
                Objects.equals(aa.getXmlName(), ba.getXmlName())
                    && aa.isRequired() == ba.isRequired()
                    && aa.id() == ba.id()
                    && Objects.equals(aa.getSchemaType(), ba.getSchemaType())
                    && equalNonElement(aa.getTarget(), ba.getTarget(), visiting);
            case CValuePropertyInfo av when b instanceof CValuePropertyInfo bv ->
                av.id() == bv.id()
                    && Objects.equals(av.getSchemaType(), bv.getSchemaType())
                    && equalNonElement(av.getTarget(), bv.getTarget(), visiting);
            case CElementPropertyInfo ae when b instanceof CElementPropertyInfo be ->
                ae.isRequired() == be.isRequired()
                    && ae.isValueList() == be.isValueList()
                    && ae.id() == be.id()
                    && Objects.equals(ae.getSchemaType(), be.getSchemaType())
                    && equalElementTypes(ae, be, visiting);
            case CReferencePropertyInfo ar when b instanceof CReferencePropertyInfo br ->
                ar.isRequired() == br.isRequired()
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
        // Preserve type-ref order (choice arm order as bound).
        for (var i = 0; i < at.size(); i++) {
            var ta = at.get(i);
            var tb = bt.get(i);
            if (!Objects.equals(ta.getTagName(), tb.getTagName())) {
                return false;
            }
            if (ta.isNillable() != tb.isNillable()) {
                return false;
            }
            if (!Objects.equals(ta.getDefaultValue(), tb.getDefaultValue())) {
                return false;
            }
            if (!equalNonElement(ta.getTarget(), tb.getTarget(), visiting)) {
                return false;
            }
        }
        return true;
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
        // Order-independent multiset match with structural equality.
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
            return equalClasses(ca, cb, visiting);
        }
        if (a instanceof CElementInfo ea && b instanceof CElementInfo eb) {
            if (!Objects.equals(ea.getElementName(), eb.getElementName())) {
                return false;
            }
            if (ea.hasClass() != eb.hasClass()) {
                return false;
            }
            if (ea.hasClass() && !Objects.equals(nameKey(ea.shortName()), nameKey(eb.shortName()))) {
                return false;
            }
            var sa = ea.getScope();
            var sb = eb.getScope();
            if (sa != sb) {
                if (sa instanceof CClassInfo ca && sb instanceof CClassInfo cb) {
                    if (!equalClasses(ca, cb, visiting)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return equalNonElement(ea.getContentType(), eb.getContentType(), visiting);
        }
        return false;
    }

    private static boolean equalNonElement(CNonElement a, CNonElement b, Set<IdentityPair> visiting) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof CClassInfo ca && b instanceof CClassInfo cb) {
            return equalClasses(ca, cb, visiting);
        }
        if (a instanceof CEnumLeafInfo ea && b instanceof CEnumLeafInfo eb) {
            return equalEnums(ea, eb);
        }
        if (a instanceof CBuiltinLeafInfo ba && b instanceof CBuiltinLeafInfo bb) {
            return Objects.equals(ba.getTypeName(), bb.getTypeName())
                && Objects.equals(typeNameOf(ba), typeNameOf(bb));
        }
        if (a instanceof CClassRef ra && b instanceof CClassRef rb) {
            return Objects.equals(ra.fullName(), rb.fullName());
        }
        // Distinct external / other non-elements must not collapse together.
        if (a.getClass() != b.getClass()) {
            return false;
        }
        return Objects.equals(nonElementKey(a, visiting), nonElementKey(b, visiting));
    }

    private static String typeNameOf(CBuiltinLeafInfo leaf) {
        var qn = leaf.getTypeName();
        return qn == null ? leaf.toString() : qn.toString();
    }

    private static String nonElementKey(CNonElement info, Set<IdentityPair> visiting) {
        return switch (info) {
            case null -> "null";
            case CClassInfo ci -> "bean:" + ci.fullName();
            case CEnumLeafInfo e -> "enum:" + enumKey(e);
            case CBuiltinLeafInfo b -> "leaf:" + typeNameOf(b);
            case CClassRef r -> "ref:" + r.fullName();
            default -> "other:" + info.getClass().getName() + ":" + info;
        };
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
        // Preserve declaration order for API-stable enums.
        for (var i = 0; i < ac.size(); i++) {
            var ca = ac.get(i);
            var cb = bc.get(i);
            if (!Objects.equals(ca.getName(), cb.getName())) {
                return false;
            }
            if (!Objects.equals(ca.getLexicalValue(), cb.getLexicalValue())) {
                return false;
            }
        }
        return true;
    }

    private static String enumKey(CEnumLeafInfo e) {
        var sb = new StringBuilder(nameKey(e.shortName));
        if (e.getTypeName() != null) {
            sb.append(e.getTypeName());
        }
        if (e.base instanceof CBuiltinLeafInfo b) {
            sb.append('/').append(typeNameOf(b));
        } else if (e.base != null) {
            sb.append('/').append(e.base.getClass().getSimpleName());
        }
        for (var c : e.getConstants()) {
            sb.append('|').append(c.getName()).append('=').append(c.getLexicalValue());
        }
        return sb.toString();
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

    private static String wildcardKey(CReferencePropertyInfo ref) {
        WildcardMode mode = ref.getWildcard();
        return mode == null ? "-" : mode.name();
    }

    /**
     * Default presence only: {@link CDefaultValue} has no public lexical getter before outline.
     */
    private static boolean hasDefault(CPropertyInfo prop) {
        return prop.defaultValue != null;
    }

    // ── merge eligibility (related / subset) ─────────────────────────────────

    /**
     * IATA/NDC empty element-class: no local properties, base is the host.
     * Safe without {@code -merge-subset} (no extra fields).
     */
    static boolean isEmptyExtensionOf(CClassInfo victim, CClassInfo host) {
        return victim != host
            && host != null
            && victim.getBaseClass() == host
            && victim.getProperties().isEmpty()
            && (!victim.hasAttributeWildcard() || host.hasAttributeWildcard());
    }

    /**
     * Whether {@code subset} is structurally a subset of {@code host} (or equal).
     * Requires matching name key and owner package. Cycles use directed
     * {@code (subset, host)} pairs (co-inductive true only for the same pair).
     */
    static boolean isStructuralSubset(CClassInfo subset, CClassInfo host) {
        if (subset == host) {
            return true;
        }
        if (subset == null || host == null) {
            return false;
        }
        if (!nameKey(subset.shortName).equals(nameKey(host.shortName))) {
            return false;
        }
        if (!packageName(subset).equals(packageName(host))) {
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
            return true; // same (subset, host) re-entered
        }
        try {
            if (structurallyEqual(subset, host)) {
                return true;
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

    private static boolean subsetBaseCompatible(
        CClassInfo subset,
        CClassInfo host,
        Set<DirectedPair> visiting
    ) {
        var sRef = subset.getRefBaseClass();
        var hRef = host.getRefBaseClass();
        if (sRef != null || hRef != null) {
            if (sRef == null || hRef == null) {
                // subset may extend host directly only when both are generated beans
                return false;
            }
            return Objects.equals(sRef.fullName(), hRef.fullName());
        }
        var subsetBase = subset.getBaseClass();
        var hostBase = host.getBaseClass();
        // Same base (incl. both null), or subset extends host directly.
        if (subsetBase == hostBase) {
            return true;
        }
        if (subsetBase == host) {
            return true;
        }
        if (subsetBase != null && hostBase != null) {
            return isStructuralSubset(subsetBase, hostBase, visiting);
        }
        return false;
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
        if (subset.getClass() != host.getClass()) {
            return false;
        }
        if (subset.isCollection() != host.isCollection()) {
            return false;
        }
        if (!Objects.equals(adapterKey(subset), adapterKey(host))) {
            return false;
        }
        // Required on host may be stronger; shared field must not differ for unbox/API stability.
        return switch (subset) {
            case CAttributePropertyInfo sa when host instanceof CAttributePropertyInfo ha ->
                Objects.equals(sa.getXmlName(), ha.getXmlName())
                    && sa.isRequired() == ha.isRequired()
                    && sa.id() == ha.id()
                    && Objects.equals(sa.getSchemaType(), ha.getSchemaType())
                    && hasDefault(sa) == hasDefault(ha)
                    && nonElementSubsetCompatible(sa.getTarget(), ha.getTarget(), visiting);
            case CValuePropertyInfo sv when host instanceof CValuePropertyInfo hv ->
                sv.id() == hv.id()
                    && Objects.equals(sv.getSchemaType(), hv.getSchemaType())
                    && hasDefault(sv) == hasDefault(hv)
                    && nonElementSubsetCompatible(sv.getTarget(), hv.getTarget(), visiting);
            case CElementPropertyInfo se when host instanceof CElementPropertyInfo he ->
                se.isRequired() == he.isRequired()
                    && se.isValueList() == he.isValueList()
                    && se.id() == he.id()
                    && Objects.equals(se.getSchemaType(), he.getSchemaType())
                    && elementTypesSubset(se, he, visiting);
            case CReferencePropertyInfo sr when host instanceof CReferencePropertyInfo hr ->
                sr.isRequired() == hr.isRequired()
                    && sr.isMixed() == hr.isMixed()
                    && sr.id() == hr.id()
                    && Objects.equals(wildcardKey(sr), wildcardKey(hr))
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
                if (!Objects.equals(st.getTagName(), ht.getTagName())) {
                    continue;
                }
                if (st.isNillable() && !ht.isNillable()) {
                    continue;
                }
                var sd = st.getDefaultValue();
                if (sd != null && !sd.equals(ht.getDefaultValue())) {
                    continue;
                }
                if (nonElementSubsetCompatible(st.getTarget(), ht.getTarget(), visiting)) {
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
                    && Objects.equals(sei.getElementName(), hei.getElementName())
                    && nonElementSubsetCompatible(sei.getContentType(), hei.getContentType(), visiting)) {
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
            if (!nameKey(sc.shortName).equals(nameKey(hc.shortName))) {
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

    /**
     * Non-exact relation that still allows merge: empty extension (always), or structural subset
     * when {@code -merge-subset} is on.
     */
    private boolean isRelatedMerge(CClassInfo victim, CClassInfo host) {
        return isEmptyExtensionOf(victim, host)
            || (subsetEnabled() && isStructuralSubset(victim, host));
    }

    private static String relatedReason(CClassInfo victim, CClassInfo host) {
        return isEmptyExtensionOf(victim, host) ? "empty-ext" : "subset";
    }

    // ── orchestration ────────────────────────────────────────────────────────

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        countedMerges.clear();
        mergedVictimNameKeys.clear();
        var dry = dryRun();
        var merged = mergeExact(model, dry) + mergeRelated(model, dry);
        if (!dry && merged > 0) {
            collapseRedundantElementClasses(model, mergedVictimNameKeys);
            warnObjectFactoryCollisions(model);
        }
        if (merged > 0) {
            log.info("Deduped {} bean merge(s){}", merged, dry ? " (dry-run)" : "");
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * Structurally equal beans within a package+nameKey group → one host.
     * Clusters with cycle-safe {@link #structurallyEqual} (no string fingerprint map).
     */
    private int mergeExact(Model model, boolean dry) {
        Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
        for (var bean : List.copyOf(model.beans().values())) {
            byKey.computeIfAbsent(groupKey(bean), k -> new ArrayList<>()).add(bean);
        }

        var count = 0;
        for (var members : byKey.values()) {
            if (members.size() < 2) {
                continue;
            }
            // Greedy clusters: each bean joins the first host it equals.
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
                var host = cluster.getFirst();
                for (var i = 1; i < cluster.size(); i++) {
                    var victim = cluster.get(i);
                    if (!mayDelete(victim)) {
                        if (mayDelete(host) && hostScore(victim) > hostScore(host)
                            && tryMerge(model, host, victim, "exact", dry)) {
                            count++;
                            host = victim;
                        }
                        continue;
                    }
                    if (tryMerge(model, victim, host, "exact", dry)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Fixed-point pairwise merge for empty-ext (always) and subset (optional) within each
     * package+nameKey group. Skips pairs already counted in the exact pass (dry-run safe).
     */
    private int mergeRelated(Model model, boolean dry) {
        var count = 0;
        boolean changed;
        do {
            changed = false;
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
                        if (!mayDelete(victim) || !isRelatedMerge(victim, host)) {
                            continue;
                        }
                        // Skip if this pair was already counted (e.g. exact dry-run + subset).
                        if (countedMerges.contains(IdentityPair.directed(victim, host))) {
                            continue;
                        }
                        if (tryMerge(model, victim, host, relatedReason(victim, host), dry)) {
                            count++;
                            changed = true;
                        }
                    }
                }
            }
        } while (changed && !dry);
        return count;
    }

    // ── merge execution ──────────────────────────────────────────────────────

    /**
     * Merges {@code victim} into {@code host}. Returns {@code false} if skipped.
     */
    private boolean tryMerge(
        Model model,
        CClassInfo victim,
        CClassInfo host,
        String reason,
        boolean dry
    ) {
        if (victim == host
            || !model.beans().containsValue(victim)
            || !model.beans().containsValue(host)
            || !nameKey(victim.shortName).equals(nameKey(host.shortName))
            || !packageName(victim).equals(packageName(host))
            || isNestingAncestor(victim, host)) {
            return false;
        }

        // Nested host: only same parent (no lift). Package host may absorb any same-package victim.
        if (!isPackageLevel(host) && host.parent() != victim.parent()) {
            log.debug(
                "Skip dedupe {}: cross-hierarchy nested '{}' vs '{}'",
                reason, victim.fullName(), host.fullName()
            );
            return false;
        }

        // One @XmlRootElement per bean: refuse distinct root QNames.
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

        if (!prepareNestedMerges(model, victim, host, dry)) {
            return false;
        }

        log.info(
            "Dedupe {}: '{}' -> '{}' (nameKey={})",
            reason, victim.fullName(), host.fullName(), nameKey(victim.shortName)
        );

        countedMerges.add(IdentityPair.directed(victim, host));

        if (dry) {
            return true;
        }

        mergedVictimNameKeys.add(nameKey(victim.shortName));
        if (victim.isElement()) {
            mergedVictimNameKeys.add(nameKey(localPart(victim.getElementName(), victim.shortName)));
        }

        // Preserve global element-class root binding on a pure type host.
        if (victim.isElement() && !host.isElement()) {
            setFieldValue(CCLASSINFO_ELEMENTNAME_FIELD, host, victim.getElementName());
        }
        for (var enumInfo : model.enums().values()) {
            if (enumInfo.parent == victim) {
                setFieldValue(CENUMLEAFINFO_PARENT_FIELD, enumInfo, host);
            }
        }
        ModelUtils.replaceClassReferences(model, victim, host);
        ModelUtils.removeClass(model, victim);
        return true;
    }

    private static String localPart(QName name, String fallback) {
        return name == null ? fallback : name.getLocalPart();
    }

    /**
     * Align nested children under {@code victim} with those under {@code host} (merge or free name).
     */
    private boolean prepareNestedMerges(Model model, CClassInfo victim, CClassInfo host, boolean dry) {
        for (var child : List.copyOf(directNestedBeans(model, victim))) {
            if (!model.beans().containsValue(child)) {
                continue;
            }
            var matches = new ArrayList<CClassInfo>();
            for (var hc : directNestedBeans(model, host)) {
                if (nameKey(child.shortName).equals(nameKey(hc.shortName))) {
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
                // replaceClassReferences re-parents free-named children.
                continue;
            }

            matches.sort(HOST_ORDER);
            var target = matches.getFirst();
            String reason = null;
            if (structurallyEqual(child, target)) {
                reason = "exact-nested";
            } else if (isEmptyExtensionOf(child, target)) {
                reason = "empty-ext-nested";
            } else if (subsetEnabled() && isStructuralSubset(child, target)) {
                reason = "subset-nested";
            }
            if (reason == null) {
                log.debug(
                    "Skip dedupe: nested {} incompatible with {} under {}",
                    child.fullName(), target.fullName(), host.fullName()
                );
                return false;
            }

            // Prefer keeping a non-deletable (named) nested bean as host when both qualify.
            CClassInfo from;
            CClassInfo to;
            if (!mayDelete(child) && mayDelete(target)) {
                from = target;
                to = child;
            } else {
                from = child;
                to = target;
            }
            if (!tryMerge(model, from, to, reason, dry)) {
                return false;
            }
        }
        return true;
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

    /**
     * True if {@code ancestor} appears in the nesting parent chain of {@code node}.
     */
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

    // ── cleanup ──────────────────────────────────────────────────────────────

    /**
     * Drop element class names when content is already a bean with the same name key
     * <em>and</em> that name key participated in a merge (avoids ObjectFactory clashes without
     * rewriting unrelated element-class bindings).
     */
    private static void collapseRedundantElementClasses(Model model, Set<String> mergedNameKeys) {
        if (mergedNameKeys.isEmpty()) {
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
            if (!mergedNameKeys.contains(elementKey) && !mergedNameKeys.contains(contentKey)) {
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

    // ── identity pair keys ───────────────────────────────────────────────────

    /**
     * Identity-based pair for cycle detection and dry-run de-duplication.
     */
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
                var ha = System.identityHashCode(a);
                var hb = System.identityHashCode(b);
                this.hash = ha + hb;
            }
        }

        static IdentityPair undirected(Object a, Object b) {
            // Normalize order so (a,b) and (b,a) share one slot.
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

    /**
     * Directed {@code (subset, host)} pair for subset cycle detection.
     */
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
