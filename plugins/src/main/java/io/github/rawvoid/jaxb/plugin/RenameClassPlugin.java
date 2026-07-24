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
import io.github.rawvoid.jaxb.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.github.rawvoid.jaxb.utils.ModelUtils.*;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

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
 * <strong>Conflict policy.</strong> Conflicting types keep their original names; non-conflicting
 * renames still apply. Conflicts are reported as warnings (build does not fail). Checks cover:
 * </p>
 * <ul>
 *   <li>Same simple name under one parent (beans / enums / element classes)</li>
 *   <li>Parent and nested child sharing the same simple name after rename
 *       (BeanGenerator rejects that shape)</li>
 *   <li>Duplicate {@link CClassInfo#getSqueezedName()} values in a package — ObjectFactory
 *       value-factory methods use that name. A parent rename can change nested types' squeezed
 *       names; those renames are rolled back when they collide. Detection uses XJC's own
 *       {@code getSqueezedName()} after writing provisional short names onto the model</li>
 * </ul>
 * <p>
 * Prefer running plugins that re-parent types (for example {@link PromoteNestedClassPlugin})
 * before this plugin when both are active.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xrename-class", description = "Rename generated class names in postProcessModel")
public class RenameClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(RenameClassPlugin.class);

    @Option(name = "mapping", description = "Class rename rule (package filter + regex + to)")
    List<NameMapping> mappings;

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
                bean.getLocator(),
                name -> setFieldValue(CCLASSINFO_SHORTNAME_FIELD, bean, name),
                bean
            ));
        }
        for (var enumInfo : model.enums().values()) {
            result.add(new Candidate(
                "enum",
                enumInfo.shortName,
                enumInfo.fullName(),
                enumInfo.parent,
                enumInfo.parent.getOwnerPackage().name(),
                enumInfo.getLocator(),
                name -> setFieldValue(CENUMLEAFINFO_SHORTNAME_FIELD, enumInfo, name),
                null
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
                element.getLocator(),
                name -> setFieldValue(CELEMENTINFO_CLASSNAME_FIELD, element, name),
                null
            ));
        }
        return result;
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
        if (mappings == null || mappings.isEmpty()) {
            return;
        }

        var candidates = collect(model);
        // Map starts as the mapped "desired" name; conflict steps may put entries back to
        // candidate.shortName (the original). Only this map is authoritative until final apply.
        Map<Candidate, String> names = new LinkedHashMap<>();
        for (var candidate : candidates) {
            names.put(candidate, mapName(candidate));
        }

        blockDuplicateSimpleNames(model, candidates, names, errorHandler);
        blockParentChildClashes(candidates, names, errorHandler);
        blockObjectFactoryClashes(model, candidates, names, errorHandler);

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
        Map<Candidate, String> names,
        ErrorHandler errorHandler
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
            for (var candidate : group) {
                names.put(candidate, candidate.shortName);
            }
            warn(
                errorHandler,
                group.getFirst().locator,
                "Class name conflict after rename under '"
                    + parentLabel(group.getFirst().parent)
                    + "': '"
                    + entry.getKey().name()
                    + "'; keeping original names"
            );
        }
    }

    /**
     * Outer type and nested member must not share a simple name after rename.
     * Prefer undoing the parent's rename; if the parent was not renamed, undo the child.
     */
    private void blockParentChildClashes(
        List<Candidate> candidates,
        Map<Candidate, String> names,
        ErrorHandler errorHandler
    ) {
        Map<CClassInfo, Candidate> beans = beansByClass(candidates);
        boolean changed;
        do {
            changed = false;
            for (var child : candidates) {
                if (!(child.parent instanceof CClassInfo parentBean)) {
                    continue;
                }
                var parent = beans.get(parentBean);
                if (parent == null) {
                    continue;
                }
                if (!normalize(names.get(child)).equals(normalize(names.get(parent)))) {
                    continue;
                }
                // Prefer undoing the parent's rename; otherwise undo the child's.
                var undo = isPending(parent, names) ? parent : isPending(child, names) ? child : null;
                if (undo == null) {
                    continue;
                }
                names.put(undo, undo.shortName);
                warn(
                    errorHandler,
                    undo.locator,
                    "Parent-child name conflict after rename ('"
                        + parent.fullName
                        + "' / '"
                        + child.fullName
                        + "'); keeping original name for "
                        + undo.fullName
                );
                changed = true;
            }
        } while (changed);
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
        Map<Candidate, String> names,
        ErrorHandler errorHandler
    ) {
        Map<CClassInfo, Candidate> beans = beansByClass(candidates);
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
                warn(
                    errorHandler,
                    toRevert.iterator().next().locator,
                    "ObjectFactory name conflict '"
                        + squeezed
                        + "'; keeping original name(s) for "
                        + toRevert.stream().map(c -> c.fullName).toList()
                );
                changed = true;
            }
        } while (changed);
    }

    private String mapName(Candidate candidate) {
        for (var mapping : mappings) {
            if (mapping.packageName != null
                && !Objects.equals(mapping.packageName, candidate.packageName)) {
                continue;
            }
            if (mapping.regex == null || mapping.to == null) {
                continue;
            }
            if (!mapping.regex.matcher(candidate.shortName).matches()) {
                continue;
            }
            var next = candidate.shortName.replaceAll(mapping.regex.pattern(), mapping.to);
            if (!JJavaName.isJavaIdentifier(next)) {
                log.warn(
                    "Invalid Java class name after rename: '{}' (from {}); keeping original name",
                    next,
                    candidate.fullName
                );
                return candidate.shortName;
            }
            return next;
        }
        return candidate.shortName;
    }

    private void warn(ErrorHandler errorHandler, Locator locator, String message) {
        log.warn(message);
        if (errorHandler == null) {
            return;
        }
        try {
            errorHandler.warning(new SAXParseException(message, locator));
        } catch (SAXException ignored) {
            // XJC ErrorReceiver does not rely on SAXException; keep build successful.
        }
    }

    /**
     * Single rename rule: optional package filter, required short-name regex, target name.
     */
    public static class NameMapping {

        /**
         * Optional exact match on the owner package name ({@code getOwnerPackage().name()}).
         * Nested types use their package, not the outer class name.
         */
        @Option(name = "package", description = "Owner package name filter (exact match)")
        String packageName;

        /**
         * Matches the type short name; replacement uses {@link #to} via {@link String#replaceAll}.
         */
        @Option(name = "regex", required = true, description = "Regular expression matching the short class name")
        Pattern regex;

        /**
         * Target short name (may contain {@code $n} replacement groups).
         */
        @Option(name = "to", required = true, description = "Target short class name after rename")
        String to;
    }

    /**
     * One rename target. {@link #shortName} is always the <em>original</em> model name;
     * the provisional target lives in the {@code names} map. {@link #bean} is non-null only
     * for beans (needed for ObjectFactory ancestor walks).
     */
    private record Candidate(
        String kind,
        String shortName,
        String fullName,
        CClassInfoParent parent,
        String packageName,
        Locator locator,
        Consumer<String> apply,
        CClassInfo bean
    ) {
    }

    /**
     * Simple-name namespace under one parent (package or outer class).
     */
    private record Slot(CClassInfoParent parent, String name) {
    }
}
