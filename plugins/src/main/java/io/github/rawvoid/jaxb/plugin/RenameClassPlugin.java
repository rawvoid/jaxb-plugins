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
 * generation. Name checks are case-insensitive.
 * </p>
 * <p>
 * <strong>Conflict policy.</strong> Conflicting types keep their original names; non-conflicting
 * renames still apply. Conflicts are reported as warnings (build does not fail). Prefer
 * running plugins that re-parent types (for example {@link PromoteNestedClassPlugin}) before
 * this plugin when both are active.
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

        // Slot: (parent, case-insensitive desired name). size > 1 → conflict group.
        Map<Slot, List<Candidate>> bySlot = new LinkedHashMap<>();
        for (var candidate : candidates) {
            var name = desired.get(candidate);
            var slot = new Slot(canonicalParent(model, candidate.parent), normalize(name));
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(candidate);
        }

        var conflictGroups = new ArrayList<List<Candidate>>();
        Set<Candidate> blocked = new LinkedHashSet<>();
        for (var entry : bySlot.entrySet()) {
            var group = entry.getValue();
            if (group.size() > 1) {
                conflictGroups.add(group);
                blocked.addAll(group);
            }
        }

        var renamed = 0;
        for (var candidate : candidates) {
            if (blocked.contains(candidate)) {
                continue;
            }
            var next = desired.get(candidate);
            if (!next.equals(candidate.shortName)) {
                candidate.apply.accept(next);
                renamed++;
            }
        }

        reportConflicts(conflictGroups, desired, errorHandler);

        if (renamed > 0 || !conflictGroups.isEmpty()) {
            log.info("Renamed {} type(s), skipped {} conflict group(s)", renamed, conflictGroups.size());
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
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
                name -> setFieldValue(CCLASSINFO_SHORTNAME_FIELD, bean, name)
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
                name -> setFieldValue(CENUMLEAFINFO_SHORTNAME_FIELD, enumInfo, name)
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
                name -> setFieldValue(CELEMENTINFO_CLASSNAME_FIELD, element, name)
            ));
        }
        return result;
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

    private void reportConflicts(
        List<List<Candidate>> conflictGroups,
        Map<Candidate, String> desired,
        ErrorHandler errorHandler
    ) {
        for (var group : conflictGroups) {
            var first = group.getFirst();
            var desiredName = desired.get(first);
            var parentLabel = first.parent.fullName();
            if (parentLabel == null || parentLabel.isEmpty()) {
                parentLabel = "(default package)";
            }

            var lines = new StringBuilder();
            lines.append("Class name conflict after rename under '")
                .append(parentLabel)
                .append("': '")
                .append(desiredName)
                .append('\'');
            for (var candidate : group) {
                lines.append(System.lineSeparator())
                    .append("  - ")
                    .append(candidate.kind)
                    .append(' ')
                    .append(candidate.fullName)
                    .append(" (desired ")
                    .append(desired.get(candidate))
                    .append(')');
            }
            var message = lines.toString();
            log.warn(message);
            warn(errorHandler, first.locator, message);
        }
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
        Consumer<String> apply
    ) {
    }

    private record Slot(CClassInfoParent parent, String name) {
    }
}
