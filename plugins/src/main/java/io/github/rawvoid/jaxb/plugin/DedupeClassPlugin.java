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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import java.util.*;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CENUMLEAFINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * Merges structurally redundant generated beans in {@link #postProcessModel(Model, ErrorHandler)}
 * to reduce class count.
 * <p>
 * Candidates are grouped by {@linkplain #nameKey(String) name key}: the Java short name with a
 * trailing {@code Type} suffix removed ({@code AircraftCodeType} ≡ {@code AircraftCode}). Classes
 * with different name keys are never merged, even when their shapes match.
 * </p>
 * <p>
 * <strong>Exact merge.</strong> Beans with identical structural fingerprints (properties: name,
 * collection flag, attribute/element/value kind, recursive type shape) collapse to one canonical
 * host.
 * </p>
 * <p>
 * <strong>Subset merge</strong> ({@code -merge-subset}): when every property of {@code B} exists on
 * {@code A} with a compatible type, {@code B} is merged into {@code A}. Extra fields on the host
 * remain. Prefer this only when surplus fields on marshal paths are acceptable.
 * </p>
 * <p>
 * By default only <em>anonymous</em> beans ({@link CClassInfo#getTypeName()} {@code null}) are
 * deleted; named global types may still act as merge hosts. Nested children of a victim are
 * merged or re-parented onto the host before the victim is removed.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xdedupe-class", description = "Merge structurally redundant generated beans (exact / optional subset)")
public class DedupeClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(DedupeClassPlugin.class);

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

    private static int propertyCount(CClassInfo bean) {
        return bean.getProperties().size();
    }

    /**
     * Higher is better host.
     */
    private static int hostScore(CClassInfo bean) {
        var score = 0;
        if (!isAnonymous(bean)) {
            score += 1_000_000;
        }
        if (isPackageLevel(bean)) {
            score += 10_000;
        }
        score += propertyCount(bean) * 100;
        // Prefer *Type short names slightly when scores otherwise tie (named schema types).
        if (bean.shortName.endsWith("Type")) {
            score += 10;
        }
        return score;
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

    private static String nonElementFp(CNonElement info, Set<CClassInfo> stack) {
        if (info == null) {
            return "null";
        }
        if (info instanceof CClassInfo classInfo) {
            return "bean:" + classFp(classInfo, stack);
        }
        if (info instanceof CEnumLeafInfo enumInfo) {
            return "enum:" + enumFp(enumInfo);
        }
        if (info instanceof CBuiltinLeafInfo builtin) {
            var qn = builtin.getTypeName();
            return "leaf:" + (qn == null ? builtin.toString() : qn.toString());
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

    /**
     * Structural fingerprint of a bean (properties only; identity-free for isomorphic trees).
     */
    static String classFp(CClassInfo bean) {
        return classFp(bean, new HashSet<>());
    }

    private static String classFp(CClassInfo bean, Set<CClassInfo> stack) {
        if (!stack.add(bean)) {
            return "CYCLE";
        }
        try {
            var props = new ArrayList<>(bean.getProperties());
            props.sort(Comparator.comparing(p -> p.getName(false)));
            var parts = new ArrayList<String>(props.size());
            for (var prop : props) {
                parts.add(propFp(prop, stack));
            }
            return "{" + String.join(";", parts) + "}";
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
                sb.append("@A:");
                sb.append(nonElementFp(attr.getTarget(), stack));
            }
            case CValuePropertyInfo value -> {
                sb.append("@V:");
                sb.append(nonElementFp(value.getTarget(), stack));
            }
            case CElementPropertyInfo element -> {
                sb.append("@E:");
                var typeBits = new ArrayList<String>();
                for (var typeRef : element.getTypes()) {
                    typeBits.add(
                        nonElementFp(typeRef.getTarget(), stack)
                            + "/"
                            + typeRef.getTagName()
                            + (typeRef.isNillable() ? "?" : "")
                    );
                }
                Collections.sort(typeBits);
                sb.append(String.join(",", typeBits));
            }
            case CReferencePropertyInfo ref -> {
                sb.append("@R:");
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

    private static CPropertyInfo findProperty(CClassInfo bean, String name) {
        for (var prop : bean.getProperties()) {
            if (prop.getName(false).equals(name)) {
                return prop;
            }
        }
        return null;
    }

    /**
     * Whether {@code subset} is structurally a subset of {@code host} (or equal).
     * Requires matching {@link #nameKey(String)}.
     */
    static boolean isStructuralSubset(CClassInfo subset, CClassInfo host) {
        if (subset == host) {
            return true;
        }
        if (!nameKey(subset.shortName).equals(nameKey(host.shortName))) {
            return false;
        }
        return isStructuralSubset(subset, host, new HashSet<>());
    }

    private static boolean isStructuralSubset(CClassInfo subset, CClassInfo host, Set<CClassInfo> visiting) {
        if (subset == host) {
            return true;
        }
        // Avoid infinite recursion on cycles; treat as compatible once both entered.
        if (!visiting.add(subset)) {
            return true;
        }
        try {
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

    private static boolean propertySubsetCompatible(CPropertyInfo subset, CPropertyInfo host, Set<CClassInfo> visiting) {
        if (subset.isCollection() != host.isCollection()) {
            return false;
        }
        if (subset.getClass() != host.getClass()) {
            // Allow both element properties even if subclass differs.
            if (!(subset instanceof CElementPropertyInfo && host instanceof CElementPropertyInfo)
                && !(subset instanceof CAttributePropertyInfo && host instanceof CAttributePropertyInfo)
                && !(subset instanceof CValuePropertyInfo && host instanceof CValuePropertyInfo)
                && !(subset instanceof CReferencePropertyInfo && host instanceof CReferencePropertyInfo)) {
                return false;
            }
        }
        return switch (subset) {
            case CAttributePropertyInfo sa when host instanceof CAttributePropertyInfo ha ->
                nonElementSubsetCompatible(sa.getTarget(), ha.getTarget(), visiting);
            case CValuePropertyInfo sv when host instanceof CValuePropertyInfo hv ->
                nonElementSubsetCompatible(sv.getTarget(), hv.getTarget(), visiting);
            case CElementPropertyInfo se when host instanceof CElementPropertyInfo he ->
                elementTypesSubset(se, he, visiting);
            case CReferencePropertyInfo sr when host instanceof CReferencePropertyInfo hr ->
                referenceSubset(sr, hr, visiting);
            default -> false;
        };
    }

    private static boolean elementTypesSubset(
        CElementPropertyInfo subset,
        CElementPropertyInfo host,
        Set<CClassInfo> visiting
    ) {
        // Each subset type ref must match a host type ref with same tag and compatible target.
        for (var st : subset.getTypes()) {
            var matched = false;
            for (var ht : host.getTypes()) {
                if (!Objects.equals(st.getTagName(), ht.getTagName())) {
                    continue;
                }
                if (st.isNillable() && !ht.isNillable()) {
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
                if (se == he) {
                    matched = true;
                    break;
                }
                if (se instanceof CClassInfo sc && he instanceof CClassInfo hc
                    && nonElementSubsetCompatible(sc, hc, visiting)) {
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
            // Exact structural match or subset bean.
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

    private static CClassInfo pickHost(CClassInfo a, CClassInfo b) {
        var sa = hostScore(a);
        var sb = hostScore(b);
        if (sa != sb) {
            return sa >= sb ? a : b;
        }
        // Stable: fullName order.
        return a.fullName().compareTo(b.fullName()) <= 0 ? a : b;
    }

    private static boolean mayDelete(CClassInfo bean, boolean anonymousOnly) {
        return !anonymousOnly || isAnonymous(bean);
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var subset = Boolean.TRUE.equals(mergeSubset);
        var anonOnly = !Boolean.FALSE.equals(anonymousOnly); // default true
        var dry = Boolean.TRUE.equals(dryRun);

        var merged = 0;
        merged += mergeExact(model, anonOnly, dry);
        if (subset) {
            merged += mergeSubsets(model, anonOnly, dry);
        }
        if (merged > 0) {
            log.info("Deduped {} bean merge(s){}", merged, dry ? " (dry-run)" : "");
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    private int mergeExact(Model model, boolean anonOnly, boolean dry) {
        // nameKey -> fingerprint -> members
        Map<String, Map<String, List<CClassInfo>>> groups = new LinkedHashMap<>();
        for (var bean : List.copyOf(model.beans().values())) {
            var key = nameKey(bean.shortName);
            var fp = classFp(bean);
            groups
                .computeIfAbsent(key, k -> new LinkedHashMap<>())
                .computeIfAbsent(fp, k -> new ArrayList<>())
                .add(bean);
        }

        var count = 0;
        for (var byFp : groups.values()) {
            for (var members : byFp.values()) {
                if (members.size() < 2) {
                    continue;
                }
                // Pick host: best score among all; victims must be deletable.
                members.sort(Comparator
                    .comparingInt(DedupeClassPlugin::hostScore).reversed()
                    .thenComparing(CClassInfo::fullName));
                var host = members.getFirst();
                for (var i = 1; i < members.size(); i++) {
                    var victim = members.get(i);
                    if (!mayDelete(victim, anonOnly)) {
                        // Try swapping if host is deletable and victim is a better named host.
                        if (mayDelete(host, anonOnly) && hostScore(victim) > hostScore(host)) {
                            if (tryMerge(model, host, victim, "exact", dry)) {
                                count++;
                                host = victim;
                            }
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

    private int mergeSubsets(Model model, boolean anonOnly, boolean dry) {
        var count = 0;
        boolean changed;
        do {
            changed = false;
            // Group live beans by nameKey
            Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
            for (var bean : model.beans().values()) {
                byKey.computeIfAbsent(nameKey(bean.shortName), k -> new ArrayList<>()).add(bean);
            }
            for (var members : byKey.values()) {
                if (members.size() < 2) {
                    continue;
                }
                // Prefer larger hosts first.
                members.sort(Comparator
                    .comparingInt(DedupeClassPlugin::hostScore).reversed()
                    .thenComparing(CClassInfo::fullName));
                for (var host : List.copyOf(members)) {
                    if (!model.beans().containsValue(host)) {
                        continue;
                    }
                    for (var victim : List.copyOf(members)) {
                        if (victim == host || !model.beans().containsValue(victim)) {
                            continue;
                        }
                        if (!mayDelete(victim, anonOnly)) {
                            continue;
                        }
                        if (propertyCount(victim) >= propertyCount(host)
                            && classFp(victim).equals(classFp(host))) {
                            continue; // exact handled already; equal size non-exact skip
                        }
                        if (!isStructuralSubset(victim, host)) {
                            continue;
                        }
                        // Strict subset or equal with fewer-or-equal props already filtered;
                        // allow equal fingerprints only if not same class (should be gone).
                        if (tryMerge(model, victim, host, "subset", dry)) {
                            count++;
                            changed = true;
                        }
                    }
                }
            }
        } while (changed && !dry);
        return count;
    }

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
        if (victim == host) {
            return false;
        }
        if (!model.beans().containsValue(victim) || !model.beans().containsValue(host)) {
            return false;
        }
        if (!nameKey(victim.shortName).equals(nameKey(host.shortName))) {
            return false;
        }
        // Never merge a class into one of its descendants (parent cycle).
        if (isAncestor(victim, host)) {
            return false;
        }

        if (!prepareNestedMerges(model, victim, host, dry)) {
            return false;
        }

        log.info(
            "Dedupe {}: '{}' -> '{}' (nameKey={})",
            reason,
            victim.fullName(),
            host.fullName(),
            nameKey(victim.shortName)
        );

        if (dry) {
            return true;
        }

        // Re-parent enums nested under victim.
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
     * Ensures nested beans under {@code victim} can sit under {@code host} (merge or free name).
     */
    private boolean prepareNestedMerges(
        Model model,
        CClassInfo victim,
        CClassInfo host,
        boolean dry
    ) {
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
                // Name must be free under host (case-insensitive like promote).
                var childNorm = child.shortName.toLowerCase(Locale.ROOT);
                for (var hc : directNestedBeans(model, host)) {
                    if (hc.shortName.toLowerCase(Locale.ROOT).equals(childNorm)) {
                        log.debug("Skip dedupe: nested name clash {} under {}", child.shortName, host.fullName());
                        return false;
                    }
                }
                // replaceClassReferences will re-parent child when victim is rewritten.
                continue;
            }
            // Prefer structural merge into best matching host child.
            matches.sort(Comparator
                .comparingInt(DedupeClassPlugin::hostScore).reversed()
                .thenComparing(CClassInfo::fullName));
            var target = matches.getFirst();
            if (classFp(child).equals(classFp(target)) || isStructuralSubset(child, target)) {
                if (!mayDelete(child, !Boolean.FALSE.equals(anonymousOnly))
                    && mayDelete(target, !Boolean.FALSE.equals(anonymousOnly))) {
                    // Prefer deleting anonymous target into named child if needed.
                    if (!tryMerge(model, target, child, "exact-nested", dry)) {
                        return false;
                    }
                } else if (!tryMerge(model, child, target, "exact-nested", dry)) {
                    return false;
                }
            } else {
                log.debug(
                    "Skip dedupe: nested {} incompatible with {} under {}",
                    child.fullName(),
                    target.fullName(),
                    host.fullName()
                );
                return false;
            }
        }
        return true;
    }

    private static boolean isAncestor(CClassInfo ancestor, CClassInfo node) {
        CClassInfoParent p = node.parent();
        while (p instanceof CClassInfo c) {
            if (c == ancestor) {
                return true;
            }
            p = c.parent();
        }
        return false;
    }

}
