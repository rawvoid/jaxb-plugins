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
import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CClassInfoParent;
import com.sun.tools.xjc.model.CElementInfo;
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.outline.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CCLASSINFO_SHORTNAME_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.CELEMENTINFO_CLASSNAME_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.CENUMLEAFINFO_SHORTNAME_FIELD;
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
 *   <li>Parent and nested child sharing the same simple name after rename</li>
 *   <li>Duplicate {@link CClassInfo#getSqueezedName()} values in a package (ObjectFactory
 *       value-factory methods). Parent renames that would change a nested type's squeezed
 *       name are rolled back when they collide</li>
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

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }

        var candidates = collect(model);
        Map<Candidate, String> desired = new LinkedHashMap<>();
        for (var candidate : candidates) {
            desired.put(candidate, mapName(candidate));
        }

        // Provisional short names start as desired, then conflict resolution reverts some.
        Map<Candidate, String> provisional = new LinkedHashMap<>(desired);
        var reports = new ArrayList<ConflictReport>();

        blockShortNameCollisions(model, candidates, provisional, reports);
        blockParentChildCollisions(model, candidates, provisional, reports);
        blockSqueezedNameCollisions(model, candidates, provisional, reports);

        var renamed = 0;
        for (var candidate : candidates) {
            var next = provisional.get(candidate);
            if (!next.equals(candidate.shortName)) {
                candidate.apply.accept(next);
                renamed++;
            }
        }

        for (var report : reports) {
            log.warn(report.message());
            warn(errorHandler, report.locator(), report.message());
        }

        if (renamed > 0 || !reports.isEmpty()) {
            log.info("Renamed {} type(s), reported {} conflict group(s)", renamed, reports.size());
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * Two or more types would share the same simple name under one parent → keep originals.
     */
    private static void blockShortNameCollisions(
        Model model,
        List<Candidate> candidates,
        Map<Candidate, String> provisional,
        List<ConflictReport> reports
    ) {
        Map<Slot, List<Candidate>> bySlot = new LinkedHashMap<>();
        for (var candidate : candidates) {
            var slot = new Slot(canonicalParent(model, candidate.parent), normalize(provisional.get(candidate)));
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(candidate);
        }

        for (var entry : bySlot.entrySet()) {
            var group = entry.getValue();
            if (group.size() <= 1) {
                continue;
            }
            // Only report / revert groups that still contain at least one pending rename.
            if (!anyPendingRename(group, provisional)) {
                continue;
            }
            revertAll(group, provisional);
            reports.add(shortNameReport(group, provisional, entry.getKey()));
        }
    }

    /**
     * Outer type and nested member must not share a simple name (BeanGenerator rejects that).
     */
    private static void blockParentChildCollisions(
        Model model,
        List<Candidate> candidates,
        Map<Candidate, String> provisional,
        List<ConflictReport> reports
    ) {
        Map<Object, Candidate> bySource = indexBySource(candidates);
        boolean changed;
        do {
            changed = false;
            for (var candidate : candidates) {
                if (!(candidate.parent instanceof CClassInfo parentBean)) {
                    continue;
                }
                var parentCandidate = bySource.get(parentBean);
                if (parentCandidate == null) {
                    continue;
                }
                var childName = normalize(provisional.get(candidate));
                var parentName = normalize(provisional.get(parentCandidate));
                if (!childName.equals(parentName)) {
                    continue;
                }

                // Prefer undoing the parent's rename; otherwise undo the child's.
                Candidate undo = isPending(parentCandidate, provisional) ? parentCandidate
                    : isPending(candidate, provisional) ? candidate
                    : null;
                if (undo == null) {
                    continue;
                }
                provisional.put(undo, undo.shortName);
                reports.add(new ConflictReport(
                    undo.locator,
                    "Parent-child class name conflict after rename: parent '"
                        + parentCandidate.fullName
                        + "' and nested '"
                        + candidate.fullName
                        + "' would both be '"
                        + parentName
                        + "'; keeping original name for "
                        + undo.fullName
                ));
                changed = true;
            }
        } while (changed);
    }

    /**
     * ObjectFactory value factories use {@link CClassInfo#getSqueezedName()} (parent-chain
     * concatenation). A parent rename can collide a nested type with an unrelated top-level type.
     */
    private static void blockSqueezedNameCollisions(
        Model model,
        List<Candidate> candidates,
        Map<Candidate, String> provisional,
        List<ConflictReport> reports
    ) {
        Map<CClassInfo, Candidate> beanCandidates = new LinkedHashMap<>();
        for (var candidate : candidates) {
            if (candidate.bean != null) {
                beanCandidates.put(candidate.bean, candidate);
            }
        }
        if (beanCandidates.isEmpty()) {
            return;
        }

        boolean changed;
        do {
            changed = false;
            Map<CClassInfo, String> nameByBean = new LinkedHashMap<>();
            for (var entry : beanCandidates.entrySet()) {
                nameByBean.put(entry.getKey(), provisional.get(entry.getValue()));
            }

            // (package, squeezedName) → beans that would register the same ObjectFactory method.
            Map<SqueezedSlot, List<CClassInfo>> bySqueezed = new LinkedHashMap<>();
            for (var bean : beanCandidates.keySet()) {
                if (bean.isAbstract()) {
                    // Abstract types do not get value factory methods, but still affect children.
                    // Include them only as parents via chain walk; skip as factory registrants.
                }
                var squeezed = provisionalSqueezedName(bean, nameByBean);
                var slot = new SqueezedSlot(bean.getOwnerPackage(), squeezed);
                bySqueezed.computeIfAbsent(slot, k -> new ArrayList<>()).add(bean);
            }

            // Filter to beans that actually emit createXxx() (non-abstract).
            for (var entry : bySqueezed.entrySet()) {
                var group = entry.getValue().stream().filter(b -> !b.isAbstract()).toList();
                if (group.size() <= 1) {
                    continue;
                }

                // Undo renames on members and their ancestors — parent rename is the usual cause.
                Set<Candidate> toRevert = new LinkedHashSet<>();
                for (var bean : group) {
                    collectRenamedAncestors(bean, beanCandidates, provisional, toRevert);
                }
                if (toRevert.isEmpty()) {
                    // Pre-existing squeezed clash with original names only: report once, cannot fix.
                    if (reports.stream().noneMatch(r -> r.message().contains(entry.getKey().squeezed()))) {
                        reports.add(new ConflictReport(
                            beanCandidates.get(group.getFirst()).locator,
                            "ObjectFactory name conflict (unrelated to rename): '"
                                + entry.getKey().squeezed()
                                + "' under package '"
                                + packageLabel(entry.getKey().pkg())
                                + "'"
                        ));
                    }
                    continue;
                }

                for (var candidate : toRevert) {
                    provisional.put(candidate, candidate.shortName);
                }
                reports.add(squeezedReport(group, entry.getKey(), toRevert, beanCandidates));
                changed = true;
            }
        } while (changed);
    }

    private static void collectRenamedAncestors(
        CClassInfo bean,
        Map<CClassInfo, Candidate> beanCandidates,
        Map<Candidate, String> provisional,
        Set<Candidate> out
    ) {
        CClassInfoParent parent = bean;
        while (parent instanceof CClassInfo current) {
            var candidate = beanCandidates.get(current);
            if (candidate != null && isPending(candidate, provisional)) {
                out.add(candidate);
            }
            parent = current.parent();
        }
    }

    private static String provisionalSqueezedName(CClassInfo bean, Map<CClassInfo, String> nameByBean) {
        return appendSqueezed(bean.parent(), nameByBean) + nameByBean.getOrDefault(bean, bean.shortName);
    }

    /**
     * Mirrors XJC {@code CClassInfo} squeezed-name visitor: package → empty, bean → chain + shortName,
     * element parent → chain + element short name.
     */
    private static String appendSqueezed(CClassInfoParent parent, Map<CClassInfo, String> nameByBean) {
        return switch (parent) {
            case CClassInfo bean -> appendSqueezed(bean.parent(), nameByBean)
                + nameByBean.getOrDefault(bean, bean.shortName);
            case CElementInfo element -> appendSqueezed(element.parent, nameByBean)
                + element.shortName();
            case CClassInfoParent.Package ignored -> "";
            case null -> "";
            default -> "";
        };
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

    private static Map<Object, Candidate> indexBySource(List<Candidate> candidates) {
        Map<Object, Candidate> bySource = new LinkedHashMap<>();
        for (var candidate : candidates) {
            if (candidate.bean != null) {
                bySource.put(candidate.bean, candidate);
            }
        }
        return bySource;
    }

    private static CClassInfoParent canonicalParent(Model model, CClassInfoParent parent) {
        if (parent instanceof CClassInfoParent.Package pkgParent) {
            return model.getPackage(pkgParent.pkg);
        }
        return parent;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isPending(Candidate candidate, Map<Candidate, String> provisional) {
        return !provisional.get(candidate).equals(candidate.shortName);
    }

    private static boolean anyPendingRename(List<Candidate> group, Map<Candidate, String> provisional) {
        for (var candidate : group) {
            if (isPending(candidate, provisional)) {
                return true;
            }
        }
        return false;
    }

    private static void revertAll(List<Candidate> group, Map<Candidate, String> provisional) {
        for (var candidate : group) {
            provisional.put(candidate, candidate.shortName);
        }
    }

    private static ConflictReport shortNameReport(
        List<Candidate> group,
        Map<Candidate, String> provisional,
        Slot slot
    ) {
        var first = group.getFirst();
        var parentLabel = first.parent.fullName();
        if (parentLabel == null || parentLabel.isEmpty()) {
            parentLabel = "(default package)";
        }
        var lines = new StringBuilder();
        lines.append("Class name conflict after rename under '")
            .append(parentLabel)
            .append("': '")
            .append(slot.name())
            .append('\'');
        for (var candidate : group) {
            lines.append(System.lineSeparator())
                .append("  - ")
                .append(candidate.kind)
                .append(' ')
                .append(candidate.fullName)
                .append(" (kept ")
                .append(provisional.get(candidate))
                .append(')');
        }
        return new ConflictReport(first.locator, lines.toString());
    }

    private static ConflictReport squeezedReport(
        List<CClassInfo> group,
        SqueezedSlot slot,
        Set<Candidate> reverted,
        Map<CClassInfo, Candidate> beanCandidates
    ) {
        var lines = new StringBuilder();
        lines.append("ObjectFactory name conflict after rename under package '")
            .append(packageLabel(slot.pkg()))
            .append("': '")
            .append(slot.squeezed())
            .append('\'');
        for (var bean : group) {
            lines.append(System.lineSeparator())
                .append("  - bean ")
                .append(bean.fullName());
        }
        lines.append(System.lineSeparator()).append("  reverted rename(s):");
        for (var candidate : reverted) {
            lines.append(System.lineSeparator())
                .append("  - ")
                .append(candidate.fullName);
        }
        var locator = beanCandidates.get(group.getFirst()).locator;
        return new ConflictReport(locator, lines.toString());
    }

    private static String packageLabel(JPackage pkg) {
        var name = pkg.name();
        return name == null || name.isEmpty() ? "(default package)" : name;
    }

    private static void warn(ErrorHandler errorHandler, Locator locator, String message) {
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

    private record Slot(CClassInfoParent parent, String name) {
    }

    private record SqueezedSlot(JPackage pkg, String squeezed) {
    }

    private record ConflictReport(Locator locator, String message) {
    }
}
