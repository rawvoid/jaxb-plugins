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

import com.sun.codemodel.JJavaName;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.*;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Compact;
import io.github.rawvoid.jaxb.plugin.option.Option;
import io.github.rawvoid.jaxb.plugin.xjc.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import javax.xml.namespace.QName;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.github.rawvoid.jaxb.plugin.xjc.ModelUtils.*;
import static io.github.rawvoid.jaxb.plugin.xjc.ReflectUtils.setFieldValue;

/**
 * Renames generated class-like types in {@link #postProcessModel(Model, ErrorHandler)}.
 * <p>
 * Unlike {@link ConvertNamePlugin}, which installs a {@code NameConverter} during schema
 * binding, this plugin rewrites model short names after the model is complete. That lets
 * every rename target be simulated first so class-name conflicts can be listed together
 * instead of aborting on the first collision in {@code CodeModelClassFactory}.
 * </p>
 * <p>
 * Scope: {@link CClassInfo} beans, {@link CEnumLeafInfo} enums, and {@link CElementInfo}
 * instances with {@link CElementInfo#hasClass()}. Beans, enums, and element classes share
 * one simple-name namespace under each parent (package or outer class), matching XJC code
 * generation. Name checks are case-insensitive for short names.
 * </p>
 * <p>
 * <strong>Mapping pipeline.</strong> Explicit {@code -mapping} entries run first, in
 * declaration order, as a single forward pass. Each rule matches against the
 * <em>current intermediate</em> short name (not only the original), so multi-step renames
 * work, for example {@code IATAFooType} → strip {@code Type} → strip {@code IATA} →
 * {@code Foo}. A rule that would produce a non-identifier is skipped with a warning; later
 * rules still run. Optional package filters always use the type's owner package. Mappings
 * apply to every candidate (including anonymous / element-derived classes).
 * </p>
 * <p>
 * <strong>Named-type {@code Type} suffix strip.</strong> Optional {@code -strip-type-suffix}
 * (default off) removes a trailing {@code Type} from the short name of <em>named</em> schema
 * types only ({@link CClassInfo#getTypeName()} / enum type name non-null and local part ends
 * with {@code Type}, including {@code Foo_Type}). Anonymous types and element classes are
 * skipped so element names like {@code ActionType} are not mis-renamed. Eligibility uses the
 * schema type name; the replacement is applied to the current short name (after mappings),
 * so XJC name conversion such as {@code OrderID_Type} → {@code OrderIDType} → {@code OrderID}
 * still works. Prefer this flag over a bare {@code -mapping=^(.+)Type$->$1} when schemas mix
 * type-suffix conventions with element names that end in {@code Type}.
 * </p>
 * <p>
 * <strong>Conflict policy.</strong> Conflicting types keep their original names; non-conflicting
 * renames still apply. Conflicts are reported as warnings (build does not fail). Checks cover:
 * </p>
 * <ul>
 *   <li>Same simple name under one parent (beans / enums / element classes)</li>
 *   <li>An ancestor and a nested type sharing the same simple name after rename
 *       (Java forbids a nested type simple name equal to any enclosing class; BeanGenerator
 *       also rejects the immediate parent-child case)</li>
 *   <li>Duplicate {@link CClassInfo#getSqueezedName()} values in a package — ObjectFactory
 *       value-factory methods use that name. A parent rename can change nested types' squeezed
 *       names; those renames are rolled back when they collide. Detection uses XJC's own
 *       {@code getSqueezedName()} after writing provisional short names onto the model</li>
 * </ul>
 * <p>
 * When both this plugin and {@link PromoteNestedClassPlugin} are active, running rename
 * <em>before</em> promote lets named global types claim short names first; promote-first is
 * safer for some ObjectFactory edge cases but can leave dual names ({@code Foo} + {@code FooType}).
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xrename-class", description = "Rename generated class names in postProcessModel")
public class RenameClassPlugin extends OptionPlugin {

    private static final Logger log = LoggerFactory.getLogger(RenameClassPlugin.class);

    /**
     * Matches short names that end with a non-empty prefix plus {@code Type}
     * (bare {@code Type} does not match).
     */
    private static final Pattern TYPE_SUFFIX = Pattern.compile("^(.+)Type$");

    @Option(name = "mapping", description = "Class name mapping (package filter + from pattern + to)")
    List<MappingConfig> mappings;

    /**
     * When true, strip a trailing {@code Type} from named schema types only (see class Javadoc).
     * Flag form: {@code -strip-type-suffix}. Default off.
     */
    @Option(name = "strip-type-suffix", description = "Strip Type suffix from named schema types only (default: false)")
    Boolean stripTypeSuffix;

    /**
     * Collects this bean and its {@link CClassInfo} ancestors that still have a pending rename.
     */
    private static void collectPendingAncestors(
        CClassInfo bean,
        Map<CClassInfo, Candidate> beans,
        Map<Candidate, String> names,
        Set<Candidate> out
    ) {
        CClassInfoParent current = bean;
        while (current instanceof CClassInfo classInfo) {
            var candidate = beans.get(classInfo);
            if (candidate != null && isPending(candidate, names)) {
                out.add(candidate);
            }
            current = classInfo.parent();
        }
    }

    private static List<Candidate> collect(Model model) {
        var result = new ArrayList<Candidate>();
        for (var bean : model.beans().values()) {
            result.add(new Candidate(
                "bean",
                bean.shortName,
                bean.fullName(),
                bean.parent(),
                bean.getOwnerPackage().name(),
                name -> setFieldValue(CCLASSINFO_SHORTNAME_FIELD, bean, name),
                bean,
                localPart(bean.getTypeName())
            ));
        }
        for (var enumInfo : model.enums().values()) {
            result.add(new Candidate(
                "enum",
                enumInfo.shortName,
                enumInfo.fullName(),
                enumInfo.parent,
                enumInfo.parent.getOwnerPackage().name(),
                name -> setFieldValue(CENUMLEAFINFO_SHORTNAME_FIELD, enumInfo, name),
                null,
                localPart(enumInfo.getTypeName())
            ));
        }
        for (var element : model.getAllElements()) {
            if (!element.hasClass()) {
                continue;
            }
            result.add(new Candidate(
                "element",
                element.shortName(),
                element.fullName(),
                element.parent,
                element.getOwnerPackage().name(),
                name -> setFieldValue(CELEMENTINFO_CLASSNAME_FIELD, element, name),
                null,
                null
            ));
        }
        return result;
    }

    private static String localPart(QName typeName) {
        return typeName == null ? null : typeName.getLocalPart();
    }

    private static Map<CClassInfo, Candidate> beansByClass(List<Candidate> candidates) {
        var map = new LinkedHashMap<CClassInfo, Candidate>();
        for (var candidate : candidates) {
            if (candidate.bean != null) {
                map.put(candidate.bean, candidate);
            }
        }
        return map;
    }

    /**
     * XJC may attach the same {@link com.sun.codemodel.JPackage} through distinct
     * {@link CClassInfoParent.Package} instances. Normalize to the model cache.
     */
    private static CClassInfoParent canonicalParent(Model model, CClassInfoParent parent) {
        if (parent instanceof CClassInfoParent.Package pkgParent) {
            return model.getPackage(pkgParent.pkg);
        }
        return parent;
    }

    /**
     * Case-insensitive key so collisions match CodeModel / case-folding filesystems.
     */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isPending(Candidate candidate, Map<Candidate, String> names) {
        return !names.get(candidate).equals(candidate.shortName);
    }

    private static boolean anyPending(List<Candidate> group, Map<Candidate, String> names) {
        for (var candidate : group) {
            if (isPending(candidate, names)) {
                return true;
            }
        }
        return false;
    }

    private static String parentLabel(CClassInfoParent parent) {
        var label = parent.fullName();
        return label == null || label.isEmpty() ? "(default package)" : label;
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var strip = Boolean.TRUE.equals(stripTypeSuffix);
        if ((mappings == null || mappings.isEmpty()) && !strip) {
            return;
        }

        var candidates = collect(model);
        // Map starts as the mapped "desired" name; conflict steps may put entries back to
        // candidate.shortName (the original). Only this map is authoritative until final apply.
        Map<Candidate, String> names = new LinkedHashMap<>();
        for (var candidate : candidates) {
            names.put(candidate, mapName(candidate));
        }

        blockDuplicateSimpleNames(model, candidates, names);
        blockParentChildClashes(candidates, names);
        blockObjectFactoryClashes(model, candidates, names);

        var renamed = 0;
        for (var candidate : candidates) {
            var next = names.get(candidate);
            if (!next.equals(candidate.shortName)) {
                candidate.apply.accept(next);
                renamed++;
            }
        }
        if (renamed > 0) {
            log.info("Renamed {} type(s)", renamed);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * Two or more types would share the same simple name under one parent → keep originals
     * for the whole group (including types that already held that name).
     */
    private void blockDuplicateSimpleNames(
        Model model,
        List<Candidate> candidates,
        Map<Candidate, String> names
    ) {
        // Slot: (parent, case-insensitive desired name). size > 1 → conflict group.
        Map<Slot, List<Candidate>> bySlot = new LinkedHashMap<>();
        for (var candidate : candidates) {
            var slot = new Slot(canonicalParent(model, candidate.parent), normalize(names.get(candidate)));
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(candidate);
        }

        for (var entry : bySlot.entrySet()) {
            var group = entry.getValue();
            // Skip groups with no pending rename (e.g. two types that both already use the name).
            if (group.size() <= 1 || !anyPending(group, names)) {
                continue;
            }
            // Prefer a desired name that was actually produced by a rename (original case).
            var desired = group.stream()
                .filter(c -> isPending(c, names))
                .map(names::get)
                .findFirst()
                .orElseGet(() -> names.get(group.getFirst()));
            var involved = group.stream().map(c -> c.fullName).toList();
            for (var candidate : group) {
                names.put(candidate, candidate.shortName);
            }
            log.warn(
                "Class name conflict after rename under '{}': desired '{}' for {}; keeping original names",
                parentLabel(group.getFirst().parent),
                desired,
                involved
            );
        }
    }

    /**
     * A type and any of its enclosing beans must not share a simple name after rename.
     * Walks the full ancestor chain (not only the immediate parent): Java rejects nested
     * {@code TaxCouponInfo} under {@code TaxCouponInfo.TicketDocument} as well as an
     * immediate parent-child clash. Prefer undoing the ancestor's rename; if the ancestor
     * was not renamed, undo the nested type.
     */
    private void blockParentChildClashes(
        List<Candidate> candidates,
        Map<Candidate, String> names
    ) {
        var beans = beansByClass(candidates);
        boolean changed;
        do {
            changed = false;
            for (var child : candidates) {
                if (revertAncestorNameClash(child, beans, names)) {
                    changed = true;
                }
            }
        } while (changed);
    }

    /**
     * When {@code child} and any enclosing bean would share a simple name after rename,
     * reverts one of them (prefer the ancestor) and reports a warning.
     *
     * @return {@code true} if a rename was reverted
     */
    private boolean revertAncestorNameClash(
        Candidate child,
        Map<CClassInfo, Candidate> beans,
        Map<Candidate, String> names
    ) {
        var childName = normalize(names.get(child));
        CClassInfoParent current = child.parent;
        while (current instanceof CClassInfo parentBean) {
            var ancestor = beans.get(parentBean);
            if (ancestor != null && childName.equals(normalize(names.get(ancestor)))) {
                // Prefer undoing the ancestor's rename; otherwise undo the nested type.
                var undo = isPending(ancestor, names) ? ancestor : isPending(child, names) ? child : null;
                if (undo == null) {
                    return false;
                }
                names.put(undo, undo.shortName);
                log.warn(
                    "Ancestor-nested name conflict after rename ('{}' / '{}'); keeping original name for {}",
                    ancestor.fullName,
                    child.fullName,
                    undo.fullName
                );
                return true;
            }
            current = parentBean.parent();
        }
        return false;
    }

    /**
     * ObjectFactory value factories use {@link CClassInfo#getSqueezedName()} (parent-chain
     * concatenation of short names). A parent rename can make a nested type's squeezed name
     * collide with an unrelated top-level type even when simple-name checks under each parent
     * looked fine.
     * <p>
     * Approach: write provisional short names onto bean model fields, ask XJC via
     * {@link ModelUtils#objectFactorySqueezedCollisions(Model)}, then revert renames on the
     * colliding beans and their bean ancestors (the parent rename is usually the real cause).
     * Repeat until no rename-induced clash remains.
     * </p>
     */
    private void blockObjectFactoryClashes(
        Model model,
        List<Candidate> candidates,
        Map<Candidate, String> names
    ) {
        var beans = beansByClass(candidates);
        if (beans.isEmpty()) {
            return;
        }

        // Put provisional names on the model so getSqueezedName() sees the post-rename world.
        for (var entry : beans.entrySet()) {
            setFieldValue(CCLASSINFO_SHORTNAME_FIELD, entry.getKey(), names.get(entry.getValue()));
        }

        boolean changed;
        do {
            changed = false;
            for (var group : ModelUtils.objectFactorySqueezedCollisions(model)) {
                var toRevert = new LinkedHashSet<Candidate>();
                for (var bean : group) {
                    // Undo renames on the bean and ancestors — parent rename is the usual cause.
                    collectPendingAncestors(bean, beans, names, toRevert);
                }
                if (toRevert.isEmpty()) {
                    // Clash already present with original names — XJC will report it; nothing we can undo.
                    continue;
                }
                // Capture squeezed name before reverts change short names on the model.
                var squeezed = group.getFirst().getSqueezedName();
                for (var candidate : toRevert) {
                    names.put(candidate, candidate.shortName);
                    setFieldValue(CCLASSINFO_SHORTNAME_FIELD, candidate.bean, candidate.shortName);
                }
                log.warn(
                    "ObjectFactory name conflict '{}'; keeping original name(s) for {}",
                    squeezed,
                    toRevert.stream().map(c -> c.fullName).toList()
                );
                changed = true;
            }
        } while (changed);
    }

    /**
     * Applies explicit {@link #mappings} (if any), then optional named-type {@code Type}
     * suffix strip. Each step matches against the current intermediate short name.
     */
    private String mapName(Candidate candidate) {
        var current = candidate.shortName;
        if (mappings != null) {
            for (var mapping : mappings) {
                if (!matches(mapping, candidate.packageName, current)) {
                    continue;
                }
                var next = current.replaceAll(mapping.from.pattern(), mapping.to);
                if (next.equals(current)) {
                    continue;
                }
                if (!JJavaName.isJavaIdentifier(next)) {
                    log.warn(
                        "Invalid Java class name after mapping: '{}' (from {}); skipping rule",
                        next,
                        candidate.fullName
                    );
                    continue;
                }
                current = next;
            }
        }
        if (Boolean.TRUE.equals(stripTypeSuffix) && isStripTypeEligible(candidate)) {
            var stripped = stripTypeSuffix(current);
            if (stripped != null && !stripped.equals(current)) {
                if (!JJavaName.isJavaIdentifier(stripped)) {
                    log.warn(
                        "Invalid Java class name after Type suffix strip: '{}' (from {}); skipping strip",
                        stripped,
                        candidate.fullName
                    );
                } else {
                    current = stripped;
                }
            }
        }
        return current;
    }

    /**
     * Named schema type whose local name ends with {@code Type} (includes {@code Foo_Type}).
     * Anonymous beans, element classes, and types without a {@code Type} suffix are excluded.
     */
    private static boolean isStripTypeEligible(Candidate candidate) {
        var local = candidate.schemaTypeLocalName;
        return local != null && local.endsWith("Type");
    }

    /**
     * Strips a trailing {@code Type} from the short name, or returns {@code name} unchanged.
     */
    private static String stripTypeSuffix(String name) {
        var matcher = TYPE_SUFFIX.matcher(name);
        return matcher.matches() ? matcher.group(1) : name;
    }

    private static boolean matches(MappingConfig mapping, String packageName, String shortName) {
        return (mapping.packageName == null || mapping.packageName.equals(packageName))
            && mapping.from.matcher(shortName).matches();
    }

    /**
     * One class-name mapping: optional package filter, required short-name pattern, target name.
     * <p>
     * Mappings form an ordered pipeline (see class Javadoc). Compact CLI (see {@link Compact}):
     * {@code -mapping=Person->CustomPerson}, {@code -mapping=/(.*)Type/->$1}.
     * </p>
     */
    @Compact(formats = {"/{from}/->{to}", "{from}->{to}"})
    public static class MappingConfig {

        /**
         * Optional exact match on the owner package name ({@code getOwnerPackage().name()}).
         * Nested types use their package, not the outer class name.
         */
        @Option(name = "package", description = "Owner package name filter (exact match)")
        String packageName;

        /**
         * Matches the current intermediate short name; replacement uses {@link #to} via
         * {@link String#replaceAll}.
         */
        @Option(name = "from", required = true, description = "Regular expression matching the short class name")
        Pattern from;

        /**
         * Target short name (may contain {@code $n} replacement groups).
         */
        @Option(name = "to", required = true, description = "Target short class name")
        String to;
    }

    /**
     * One rename target. {@link #shortName} is always the <em>original</em> model name;
     * the provisional target lives in the {@code names} map. {@link #bean} is non-null only
     * for beans (needed for ObjectFactory ancestor walks).
     * {@link #schemaTypeLocalName} is the schema type QName local part when the type is
     * named; {@code null} for anonymous beans and element classes.
     */
    private record Candidate(
        String kind,
        String shortName,
        String fullName,
        CClassInfoParent parent,
        String packageName,
        Consumer<String> apply,
        CClassInfo bean,
        String schemaTypeLocalName
    ) {
    }

    /**
     * Simple-name namespace under one parent (package or outer class).
     */
    private record Slot(CClassInfoParent parent, String name) {
    }
}
