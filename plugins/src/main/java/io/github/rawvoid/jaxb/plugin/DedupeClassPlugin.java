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
import org.glassfish.jaxb.core.v2.model.core.ID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

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
 *   <li><strong>Exact</strong> — identical structural fingerprints.</li>
 *   <li><strong>Related</strong> — empty extension of the host (always; IATA/NDC
 *       {@code class Foo extends FooType {}}), and optional property-subset merges
 *       ({@code -merge-subset}).</li>
 * </ol>
 * <p>
 * Fingerprints cover property names, collection flags, attribute/element/value kind, XML names,
 * nillable, defaults, adapters, ID use, value-list vs repeated element, recursive type shape, and
 * attribute wildcards. Property order and {@code required}/{@code minOccurs} are omitted on purpose.
 * </p>
 * <p>
 * By default only anonymous beans ({@link CClassInfo#getTypeName()} {@code null}) are deleted;
 * named types may still be hosts. Package-level hosts may absorb any same-package victim; nested
 * hosts only absorb siblings under the same parent.
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

    // ── structural fingerprint (exact merge) ─────────────────────────────────

    static String classFp(CClassInfo bean) {
        return classFp(bean, new HashSet<>());
    }

    private static String classFp(CClassInfo bean, Set<CClassInfo> stack) {
        if (!stack.add(bean)) {
            return "CYCLE";
        }
        try {
            var base = bean.getBaseClass();
            var baseFp = base == null ? "base:none" : "base:" + classFp(base, stack);
            var flags = bean.hasAttributeWildcard() ? "W" : "";
            var props = new ArrayList<>(bean.getProperties());
            props.sort(Comparator.comparing(p -> p.getName(false)));
            var parts = new ArrayList<String>(props.size());
            for (var prop : props) {
                parts.add(propFp(prop, stack));
            }
            return baseFp + flags + "{" + String.join(";", parts) + "}";
        } finally {
            stack.remove(bean);
        }
    }

    private static String propFp(CPropertyInfo prop, Set<CClassInfo> stack) {
        var sb = new StringBuilder();
        sb.append(prop.getName(false));
        sb.append(prop.isCollection() ? "#*" : "#1");
        switch (prop) {
            case CAttributePropertyInfo attr -> {
                sb.append("@A:").append(attr.getXmlName()).append(':');
                sb.append(nonElementFp(attr.getTarget(), stack));
                sb.append(adapterBit(attr)).append(idBit(attr)).append(schemaTypeBit(attr));
            }
            case CValuePropertyInfo value -> {
                sb.append("@V:");
                sb.append(nonElementFp(value.getTarget(), stack));
                sb.append(adapterBit(value)).append(idBit(value)).append(schemaTypeBit(value));
            }
            case CElementPropertyInfo element -> {
                sb.append("@E:");
                if (element.isValueList()) {
                    sb.append('L');
                }
                sb.append(adapterBit(element)).append(idBit(element));
                var typeBits = new ArrayList<String>();
                for (var typeRef : element.getTypes()) {
                    var def = typeRef.getDefaultValue();
                    typeBits.add(
                        nonElementFp(typeRef.getTarget(), stack)
                            + "/"
                            + typeRef.getTagName()
                            + (typeRef.isNillable() ? "?" : "")
                            + (def == null ? "" : "=" + def)
                    );
                }
                Collections.sort(typeBits);
                sb.append(String.join(",", typeBits));
            }
            case CReferencePropertyInfo ref -> {
                sb.append("@R:");
                if (ref.isMixed()) {
                    sb.append('M');
                }
                sb.append(adapterBit(ref)).append(idBit(ref));
                var els = new ArrayList<String>();
                for (var el : ref.getElements()) {
                    if (el instanceof CClassInfo ci) {
                        els.add("c:" + classFp(ci, stack));
                    } else if (el instanceof CElementInfo ei) {
                        els.add("e:" + ei.getContentType());
                    } else {
                        els.add("x:" + el);
                    }
                }
                Collections.sort(els);
                sb.append(String.join(",", els));
            }
            default -> sb.append("@U:").append(prop.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String nonElementFp(CNonElement info, Set<CClassInfo> stack) {
        switch (info) {
            case null -> {
                return "null";
            }
            case CClassInfo classInfo -> {
                return "bean:" + classFp(classInfo, stack);
            }
            case CEnumLeafInfo enumInfo -> {
                return "enum:" + enumFp(enumInfo);
            }
            case CBuiltinLeafInfo builtin -> {
                var qn = builtin.getTypeName();
                return "leaf:" + (qn == null ? builtin.toString() : qn.toString());
            }
            default -> {
            }
        }
        return "other:" + info.getClass().getSimpleName();
    }

    private static String enumFp(CEnumLeafInfo enumInfo) {
        var values = new ArrayList<String>();
        for (var c : enumInfo.getConstants()) {
            values.add(c.getLexicalValue());
        }
        Collections.sort(values);
        return nameKey(enumInfo.shortName) + "{" + String.join(",", values) + "}";
    }

    private static String adapterBit(CPropertyInfo prop) {
        var adapter = prop.getAdapter();
        if (adapter == null) {
            return "";
        }
        var known = adapter.getAdapterIfKnown();
        if (known != null) {
            return "/ad:" + known.getName();
        }
        try {
            return "/ad:" + adapter.adapterType.fullName();
        } catch (UnsupportedOperationException e) {
            return "/ad:?";
        }
    }

    private static String idBit(CPropertyInfo prop) {
        var id = switch (prop) {
            case CElementPropertyInfo e -> e.id();
            case CAttributePropertyInfo a -> a.id();
            case CValuePropertyInfo v -> v.id();
            case CReferencePropertyInfo r -> r.id();
            default -> ID.NONE;
        };
        return id == null || id == ID.NONE ? "" : "/id:" + id.name();
    }

    private static String schemaTypeBit(CPropertyInfo prop) {
        var st = prop.getSchemaType();
        return st == null ? "" : "/st:" + st;
    }

    // ── merge eligibility ────────────────────────────────────────────────────

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
     * Requires matching name key and owner package.
     */
    static boolean isStructuralSubset(CClassInfo subset, CClassInfo host) {
        if (subset == host) {
            return true;
        }
        if (!nameKey(subset.shortName).equals(nameKey(host.shortName))) {
            return false;
        }
        if (!packageName(subset).equals(packageName(host))) {
            return false;
        }
        return isStructuralSubset(subset, host, new HashSet<>());
    }

    private static boolean isStructuralSubset(CClassInfo subset, CClassInfo host, Set<CClassInfo> visiting) {
        if (subset == host) {
            return true;
        }
        if (!visiting.add(subset)) {
            return true; // cycle: treat as compatible once entered
        }
        try {
            // Same base (incl. both null), or subset extends host directly.
            var subsetBase = subset.getBaseClass();
            if (subsetBase != host.getBaseClass() && subsetBase != host) {
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
            visiting.remove(subset);
        }
    }

    private static CPropertyInfo findProperty(CClassInfo bean, String name) {
        for (var prop : bean.getProperties()) {
            if (prop.getName(false).equals(name)) {
                return prop;
            }
        }
        return null;
    }

    private static boolean propertySubsetCompatible(CPropertyInfo subset, CPropertyInfo host, Set<CClassInfo> visiting) {
        if (subset.isCollection() != host.isCollection()) {
            return false;
        }
        if (!Objects.equals(adapterBit(subset), adapterBit(host))) {
            return false;
        }
        return switch (subset) {
            case CAttributePropertyInfo sa when host instanceof CAttributePropertyInfo ha ->
                Objects.equals(sa.getXmlName(), ha.getXmlName())
                    && sa.id() == ha.id()
                    && Objects.equals(sa.getSchemaType(), ha.getSchemaType())
                    && nonElementSubsetCompatible(sa.getTarget(), ha.getTarget(), visiting);
            case CValuePropertyInfo sv when host instanceof CValuePropertyInfo hv -> sv.id() == hv.id()
                && Objects.equals(sv.getSchemaType(), hv.getSchemaType())
                && nonElementSubsetCompatible(sv.getTarget(), hv.getTarget(), visiting);
            case CElementPropertyInfo se when host instanceof CElementPropertyInfo he -> se.isValueList() == he.isValueList()
                && se.id() == he.id()
                && elementTypesSubset(se, he, visiting);
            case CReferencePropertyInfo sr when host instanceof CReferencePropertyInfo hr -> sr.isMixed() == hr.isMixed()
                && sr.id() == hr.id()
                && referenceSubset(sr, hr, visiting);
            default -> false;
        };
    }

    private static boolean elementTypesSubset(
        CElementPropertyInfo subset,
        CElementPropertyInfo host,
        Set<CClassInfo> visiting
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
        Set<CClassInfo> visiting
    ) {
        for (var se : subset.getElements()) {
            var matched = false;
            for (var he : host.getElements()) {
                if (se == he
                    || (se instanceof CClassInfo sc && he instanceof CClassInfo hc
                    && nonElementSubsetCompatible(sc, hc, visiting))) {
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

    private static boolean nonElementSubsetCompatible(CNonElement subset, CNonElement host, Set<CClassInfo> visiting) {
        if (subset == host) {
            return true;
        }
        if (subset instanceof CClassInfo sc && host instanceof CClassInfo hc) {
            if (!nameKey(sc.shortName).equals(nameKey(hc.shortName))) {
                return false;
            }
            if (classFp(sc, new HashSet<>(visiting)).equals(classFp(hc, new HashSet<>(visiting)))) {
                return true;
            }
            return isStructuralSubset(sc, hc, visiting);
        }
        if (subset instanceof CEnumLeafInfo se && host instanceof CEnumLeafInfo he) {
            return enumFp(se).equals(enumFp(he));
        }
        return nonElementFp(subset, new HashSet<>(visiting)).equals(nonElementFp(host, new HashSet<>(visiting)));
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
        var dry = dryRun();
        var merged = mergeExact(model, dry) + mergeRelated(model, dry);
        if (!dry) {
            collapseRedundantElementClasses(model);
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
     * Same fingerprint within a package+nameKey group → one host.
     */
    private int mergeExact(Model model, boolean dry) {
        Map<String, Map<String, List<CClassInfo>>> groups = new LinkedHashMap<>();
        for (var bean : List.copyOf(model.beans().values())) {
            groups
                .computeIfAbsent(groupKey(bean), k -> new LinkedHashMap<>())
                .computeIfAbsent(classFp(bean), k -> new ArrayList<>())
                .add(bean);
        }

        var count = 0;
        for (var byFp : groups.values()) {
            for (var members : byFp.values()) {
                if (members.size() < 2) {
                    continue;
                }
                members.sort(HOST_ORDER);
                var host = members.getFirst();
                for (var i = 1; i < members.size(); i++) {
                    var victim = members.get(i);
                    if (!mayDelete(victim)) {
                        // Prefer a non-deletable (named) member as host when the current host can go.
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
     * package+nameKey group. Covers package and nested beans alike.
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

        if (!prepareNestedMerges(model, victim, host, dry)) {
            return false;
        }

        log.info(
            "Dedupe {}: '{}' -> '{}' (nameKey={})",
            reason, victim.fullName(), host.fullName(), nameKey(victim.shortName)
        );
        if (dry) {
            return true;
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
            if (classFp(child).equals(classFp(target))) {
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
     * (avoids ObjectFactory signature clashes after merges).
     */
    private static void collapseRedundantElementClasses(Model model) {
        var cleared = 0;
        for (var elementInfo : model.getAllElements()) {
            if (!elementInfo.hasClass()) {
                continue;
            }
            var content = elementInfo.getContentType();
            if (content instanceof CClassInfo contentClass
                && nameKey(elementInfo.shortName()).equals(nameKey(contentClass.shortName))) {
                setFieldValue(CELEMENTINFO_CLASSNAME_FIELD, elementInfo, null);
                cleared++;
            }
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
}
