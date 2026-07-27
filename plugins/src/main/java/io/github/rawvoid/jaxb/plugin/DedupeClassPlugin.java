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
 * Merges structurally redundant generated beans in {@link #postProcessModel(Model, ErrorHandler)}
 * to reduce class count.
 * <p>
 * Candidates are grouped by <em>owner package</em> and {@linkplain #nameKey(String) name key}: the
 * Java short name with a trailing {@code Type} suffix removed ({@code AircraftCodeType} ≡
 * {@code AircraftCode}). Classes in different packages or with different name keys are never
 * merged, even when their shapes match.
 * </p>
 * <p>
 * <strong>Exact merge.</strong> Beans with identical structural fingerprints (properties: name,
 * collection flag, attribute/element/value kind, XML names, nillable, defaults, adapters, ID use,
 * value-list vs repeated element, recursive type shape, attribute wildcard) collapse to one
 * canonical host. Property declaration order and {@code required}/{@code minOccurs} are
 * intentionally <em>not</em> part of the fingerprint — they rarely differ among copy-pasted
 * isomorphic types and would block useful merges.
 * </p>
 * <p>
 * <strong>Empty extension</strong> (always on). Common IATA/NDC pattern — a global element with an
 * anonymous complex type that only {@code extends} the named {@code *Type}:
 * {@code class AircraftCode extends AircraftCodeType {}}. The empty subclass is merged into the
 * named host (no extra fields; safe without {@code -merge-subset}).
 * </p>
 * <p>
 * <strong>Subset merge</strong> ({@code -merge-subset}): when every property of {@code B} exists on
 * {@code A} with a compatible type, {@code B} is merged into {@code A}. Extra fields on the host
 * remain. Prefer this only when surplus fields on marshal paths are acceptable. Empty-shell and
 * nested-child subset merges also require this flag. Subset also allows a bean that
 * <em>extends</em> the host when its local properties are covered by the host.
 * </p>
 * <p>
 * By default only <em>anonymous</em> beans ({@link CClassInfo#getTypeName()} {@code null}) are
 * deleted; named global types may still act as merge hosts. Nested children of a victim are
 * merged or re-parented onto the host before the victim is removed.
 * </p>
 * <p>
 * Cross-message merges are allowed only when the host is already package-level (typically a
 * named global type, or a type previously promoted). Nested-to-nested merges across different
 * outers are skipped — lifting a nested host to package during dedupe corrupts element-class
 * and ObjectFactory wiring.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xdedupe-class", description = "Merge structurally redundant generated beans (exact / optional subset)")
public class DedupeClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(DedupeClassPlugin.class);

    /**
     * {@link CClassInfo} element name (global element-class binding). Final in XJC; set via
     * reflection when collapsing an empty element class into a named type host.
     */
    private static final Field CCLASSINFO_ELEMENTNAME_FIELD = getField(CClassInfo.class, "elementName");

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

    private static String packageName(CClassInfo bean) {
        return bean.getOwnerPackage().name();
    }

    /** Package-local name-key group (cross-package merges are never considered). */
    private static String groupKey(CClassInfo bean) {
        return packageName(bean) + '\0' + nameKey(bean.shortName);
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

    private static String idBit(ID id) {
        if (id == null || id == ID.NONE) {
            return "";
        }
        return "/id:" + id.name();
    }

    private static String schemaTypeBit(CPropertyInfo prop) {
        var st = prop.getSchemaType();
        return st == null ? "" : "/st:" + st;
    }

    /**
     * Structural fingerprint of a bean (properties only; identity-free for isomorphic trees).
     * <p>
     * Omits property declaration order and {@code required} on purpose (see class Javadoc).
     * </p>
     */
    static String classFp(CClassInfo bean) {
        return classFp(bean, new HashSet<>());
    }

    private static String classFp(CClassInfo bean, Set<CClassInfo> stack) {
        if (!stack.add(bean)) {
            return "CYCLE";
        }
        try {
            // Inheritance is part of identity — e.g. BagDisclosure vs BagDisclosureType
            // share local props but extend different bases and must not merge.
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
                sb.append("@A:");
                sb.append(attr.getXmlName());
                sb.append(':');
                sb.append(nonElementFp(attr.getTarget(), stack));
                sb.append(adapterBit(attr));
                sb.append(idBit(attr.id()));
                sb.append(schemaTypeBit(attr));
            }
            case CValuePropertyInfo value -> {
                sb.append("@V:");
                sb.append(nonElementFp(value.getTarget(), stack));
                sb.append(adapterBit(value));
                sb.append(idBit(value.id()));
                sb.append(schemaTypeBit(value));
            }
            case CElementPropertyInfo element -> {
                sb.append("@E:");
                if (element.isValueList()) {
                    sb.append("L");
                }
                sb.append(adapterBit(element));
                sb.append(idBit(element.id()));
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
                    sb.append("M");
                }
                sb.append(adapterBit(ref));
                sb.append(idBit(ref.id()));
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
     * Requires matching {@link #nameKey(String)} and the same owner package.
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

    /**
     * IATA/NDC empty element-class: {@code Foo} with no local properties that only extends
     * {@code FooType}. Safe to collapse without {@code -merge-subset} (no extra fields).
     */
    static boolean isEmptyExtensionOf(CClassInfo victim, CClassInfo host) {
        if (victim == host || host == null) {
            return false;
        }
        if (victim.getBaseClass() != host) {
            return false;
        }
        if (propertyCount(victim) != 0) {
            return false;
        }
        // Declaring a new attribute wildcard on the subclass is extra structure.
        return !victim.hasAttributeWildcard() || host.hasAttributeWildcard();
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
            // Compatible inheritance:
            //  - same base identity (including both null), or
            //  - subset directly extends host (empty / property-subset extension of host itself).
            // Do not treat "no base" as subset of "has base" for unrelated trees.
            var subsetBase = subset.getBaseClass();
            var hostBase = host.getBaseClass();
            if (subsetBase != hostBase && subsetBase != host) {
                return false;
            }
            // Host must cover wildcard if subset declares one.
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
        // Shared binding flags must not diverge (host may only be "wider" where subset allows).
        if (!Objects.equals(adapterBit(subset), adapterBit(host))) {
            return false;
        }
        return switch (subset) {
            case CAttributePropertyInfo sa when host instanceof CAttributePropertyInfo ha ->
                Objects.equals(sa.getXmlName(), ha.getXmlName())
                    && sa.id() == ha.id()
                    && Objects.equals(sa.getSchemaType(), ha.getSchemaType())
                    && nonElementSubsetCompatible(sa.getTarget(), ha.getTarget(), visiting);
            case CValuePropertyInfo sv when host instanceof CValuePropertyInfo hv ->
                sv.id() == hv.id()
                    && Objects.equals(sv.getSchemaType(), hv.getSchemaType())
                    && nonElementSubsetCompatible(sv.getTarget(), hv.getTarget(), visiting);
            case CElementPropertyInfo se when host instanceof CElementPropertyInfo he ->
                se.isValueList() == he.isValueList()
                    && se.id() == he.id()
                    && elementTypesSubset(se, he, visiting);
            case CReferencePropertyInfo sr when host instanceof CReferencePropertyInfo hr ->
                sr.isMixed() == hr.isMixed()
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
                // Host default must match when subset declares one (host may add a default).
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

    private static boolean mayDelete(CClassInfo bean, boolean anonymousOnly) {
        return !anonymousOnly || isAnonymous(bean);
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var subset = Boolean.TRUE.equals(mergeSubset);
        var anonOnly = !Boolean.FALSE.equals(anonymousOnly); // default true
        var dry = Boolean.TRUE.equals(dryRun);

        var merged = 0;
        merged += mergeExact(model, anonOnly, dry, subset);
        // Always: empty element-class extends named *Type (IATA/NDC), independent of -merge-subset.
        merged += mergeEmptyExtensions(model, anonOnly, dry, subset);
        if (subset) {
            merged += mergeSubsets(model, anonOnly, dry, subset);
        }
        // Final sweep: nested anonymous leftovers with same nameKey as a package-level host.
        merged += mergeNestedIntoPackageHosts(model, anonOnly, dry, subset);
        if (!dry) {
            collapseRedundantElementClasses(model);
            warnObjectFactoryCollisions(model);
        }
        if (merged > 0) {
            log.info("Deduped {} bean merge(s){}", merged, dry ? " (dry-run)" : "");
        }
    }

    /**
     * Collapses empty subclasses that only extend a same-{@link #nameKey(String)} host
     * ({@code class Foo extends FooType {}}).
     */
    private int mergeEmptyExtensions(Model model, boolean anonOnly, boolean dry, boolean subset) {
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
                        if (!isEmptyExtensionOf(victim, host)) {
                            continue;
                        }
                        if (tryMerge(model, victim, host, "empty-ext", dry, subset)) {
                            count++;
                            changed = true;
                        }
                    }
                }
            }
        } while (changed && !dry);
        return count;
    }

    private static void warnObjectFactoryCollisions(Model model) {
        var collisions = ModelUtils.objectFactorySqueezedCollisions(model);
        if (collisions.isEmpty()) {
            return;
        }
        for (var group : collisions) {
            var names = group.stream().map(CClassInfo::fullName).toList();
            log.warn(
                "ObjectFactory squeezed-name collision after dedupe (package-local createXxx): {}",
                names
            );
        }
    }

    /**
     * When a local/global element's content type is already a bean whose name key matches the
     * element short name, drop the element class. Otherwise ObjectFactory emits methods with
     * conflicting type parameters (e.g. {@code JAXBElement<Outer.Terminal>} vs package
     * {@code Terminal}).
     * <p>
     * Only the content-type path is used — clearing an element class merely because some
     * unrelated package bean shares a name key is unsafe.
     * </p>
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

    /**
     * Merges remaining nested anonymous beans into package-level hosts with the same name key
     * when structures match (exact, or subset / empty-shell if {@code -merge-subset}).
     */
    private int mergeNestedIntoPackageHosts(Model model, boolean anonOnly, boolean dry, boolean subset) {
        var count = 0;
        boolean changed;
        do {
            changed = false;
            Map<String, List<CClassInfo>> packageHosts = new LinkedHashMap<>();
            for (var bean : model.beans().values()) {
                if (isPackageLevel(bean)) {
                    packageHosts.computeIfAbsent(groupKey(bean), k -> new ArrayList<>()).add(bean);
                }
            }
            for (var victim : List.copyOf(model.beans().values())) {
                if (isPackageLevel(victim) || !mayDelete(victim, anonOnly)) {
                    continue;
                }
                var hosts = packageHosts.get(groupKey(victim));
                if (hosts == null || hosts.isEmpty()) {
                    continue;
                }
                hosts.sort(Comparator
                    .comparingInt(DedupeClassPlugin::hostScore).reversed()
                    .thenComparing(CClassInfo::fullName));
                for (var host : hosts) {
                    if (victim == host || !model.beans().containsValue(host)) {
                        continue;
                    }
                    var exact = classFp(victim).equals(classFp(host));
                    var emptyExt = isEmptyExtensionOf(victim, host);
                    var sub = subset && isStructuralSubset(victim, host);
                    // Empty shell only under -merge-subset (same base, including both null).
                    var emptyShell = subset
                        && propertyCount(victim) == 0
                        && victim.getBaseClass() == host.getBaseClass();
                    if (!exact && !emptyExt && !sub && !emptyShell) {
                        continue;
                    }
                    var reason = emptyExt && !exact
                        ? "empty-ext-pkg"
                        : emptyShell && !exact
                            ? "empty-shell"
                            : exact ? "exact-pkg" : "subset-pkg";
                    if (tryMerge(model, victim, host, reason, dry, subset)) {
                        count++;
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed && !dry);
        return count;
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    private int mergeExact(Model model, boolean anonOnly, boolean dry, boolean subset) {
        // package+nameKey -> fingerprint -> members
        Map<String, Map<String, List<CClassInfo>>> groups = new LinkedHashMap<>();
        for (var bean : List.copyOf(model.beans().values())) {
            var fp = classFp(bean);
            groups
                .computeIfAbsent(groupKey(bean), k -> new LinkedHashMap<>())
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
                            if (tryMerge(model, host, victim, "exact", dry, subset)) {
                                count++;
                                host = victim;
                            }
                        }
                        continue;
                    }
                    if (tryMerge(model, victim, host, "exact", dry, subset)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int mergeSubsets(Model model, boolean anonOnly, boolean dry, boolean subset) {
        var count = 0;
        boolean changed;
        do {
            changed = false;
            // Group live beans by package + nameKey
            Map<String, List<CClassInfo>> byKey = new LinkedHashMap<>();
            for (var bean : model.beans().values()) {
                byKey.computeIfAbsent(groupKey(bean), k -> new ArrayList<>()).add(bean);
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
                        if (tryMerge(model, victim, host, "subset", dry, subset)) {
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
        boolean dry,
        boolean mergeSubset
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
        if (!packageName(victim).equals(packageName(host))) {
            return false;
        }
        // Never merge a class into one of its descendants (parent cycle).
        if (isAncestor(victim, host)) {
            return false;
        }

        // Package-level host may absorb any same-package victim; nested host only same parent.
        if (!isPackageLevel(host) && host.parent() != victim.parent()) {
            log.debug(
                "Skip dedupe {}: cross-hierarchy nested '{}' vs '{}'",
                reason,
                victim.fullName(),
                host.fullName()
            );
            return false;
        }

        if (!prepareNestedMerges(model, victim, host, dry, mergeSubset)) {
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

        // Preserve global element-class root binding when collapsing into a pure type host
        // (e.g. AircraftCode @XmlRootElement → AircraftCodeType).
        if (victim.isElement() && !host.isElement()) {
            setFieldValue(CCLASSINFO_ELEMENTNAME_FIELD, host, victim.getElementName());
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
     * Nested subset merges require {@code mergeSubset}; exact structural matches always qualify.
     */
    private boolean prepareNestedMerges(
        Model model,
        CClassInfo victim,
        CClassInfo host,
        boolean dry,
        boolean mergeSubset
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
            var exact = classFp(child).equals(classFp(target));
            var sub = mergeSubset && isStructuralSubset(child, target);
            if (!exact && !sub) {
                log.debug(
                    "Skip dedupe: nested {} incompatible with {} under {}",
                    child.fullName(),
                    target.fullName(),
                    host.fullName()
                );
                return false;
            }
            var reason = exact ? "exact-nested" : "subset-nested";
            if (!mayDelete(child, !Boolean.FALSE.equals(anonymousOnly))
                && mayDelete(target, !Boolean.FALSE.equals(anonymousOnly))) {
                // Prefer deleting anonymous target into named child if needed.
                if (!tryMerge(model, target, child, reason, dry, mergeSubset)) {
                    return false;
                }
            } else if (!tryMerge(model, child, target, reason, dry, mergeSubset)) {
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
