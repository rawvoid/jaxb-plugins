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

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import io.github.rawvoid.jaxb.utils.LombokAccessors;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects common bean properties across user-selected sets of generated classes and
 * emits Java interfaces declaring their accessors. Matching classes then
 * {@code implements} those interfaces. Fields and XML bindings are left untouched.
 *
 * <p>CLI example (compact groups):</p>
 * <pre>{@code
 * -Xcommon-interface \
 *   -group=.*Request->com.example.CommonRequest \
 *   -group=.*Response->com.example.CommonResponse
 * }</pre>
 *
 * <p>CLI example (structured groups; needed when {@code -fields} or multiple {@code -class} patterns):</p>
 * <pre>{@code
 * -Xcommon-interface \
 *   -group \
 *   -class=.*Request \
 *   -interface=com.example.CommonRequest \
 *   -fields=id,timestamp \
 *   -group \
 *   -class=.*Response \
 *   -interface=com.example.CommonResponse
 * }</pre>
 *
 * <p><b>Rules (per group):</b></p>
 * <ul>
 *   <li>{@code -group} accepts compact {@code pattern->InterfaceFqcn} or the structured form below.</li>
 *   <li>{@code -class} (required, repeatable within a structured group): regex against generated class FQCN.
 *       Compact form supplies a single pattern.</li>
 *   <li>{@code -interface} (required): FQCN of the interface to generate for this group.</li>
 *   <li>{@code -fields} (optional, structured form only): comma-separated Java property names; omit for full
 *       intersection of matching classes.</li>
 *   <li>Property discovery uses the field model ({@code FieldOutline}), not whether XJC
 *       accessors are still present in source — so it works after {@code -Xlombok} strips methods.</li>
 *   <li><b>Without</b> {@code @lombok.Data}: XJC-style accessors. Getter always; setter only for
 *       non-collection properties (stock XJC has no {@code set} for live lists).</li>
 *   <li><b>With</b> {@code @Data} (already on the class, or applied by an active
 *       {@link LombokPlugin} with default/{@code @Data} annos): Lombok-style names via
 *       {@link LombokAccessors}, and setters for collections too. Detection does not depend on
 *       plugin {@code run} order.</li>
 *   <li>Setter is declared only when every participating class will have a same-signature setter.</li>
 *   <li>Zero class matches → error. Empty common property set → warning, skip that group.</li>
 *   <li>Naming follows Lombok <em>defaults</em> (no project {@code lombok.config} / {@code @Accessors}).</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xcommon-interface", description = "Generate interfaces from common property accessors and implement them on matching classes")
public class CommonInterfacePlugin extends AbstractPlugin {

    private static final String LOMBOK_DATA = "lombok.Data";

    @Option(name = "group", required = true,
        description = "Interface generation group (repeatable; restatable group marker separates items)")
    List<GroupConfig> groups;

    /**
     * One interface generation unit: class selection, target interface FQCN, optional field filter.
     */
    @Compact(formats = {"{class}->{interface}"})
    public static class GroupConfig {

        @Option(name = "class", required = true, placeholder = "regex",
            description = "Regex matching fully-qualified generated class names (repeatable within the group)")
        List<Pattern> classPatterns;

        @Option(name = "interface", required = true, placeholder = "FQCN",
            description = "Fully-qualified name of the interface to generate")
        String interfaceName;

        @Option(name = "fields", placeholder = "a,b,c",
            description = "Optional comma-separated Java property names to consider (default: full intersection)")
        String fields;
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        if (groups == null || groups.isEmpty()) {
            error(errorHandler, "At least one -group is required");
            return false;
        }

        var ok = true;
        var generatedInterfaces = new LinkedHashSet<String>();
        for (var group : groups) {
            if (!processGroup(outline, options, group, generatedInterfaces, errorHandler)) {
                ok = false;
            }
        }
        return ok;
    }

    private boolean processGroup(
        Outline outline,
        Options options,
        GroupConfig group,
        Set<String> generatedInterfaces,
        ErrorHandler errorHandler
    ) throws SAXException {
        var ifaceFqcn = group.interfaceName == null ? "" : group.interfaceName.trim();
        if (ifaceFqcn.isEmpty()) {
            error(errorHandler, "Option -interface is required and must be a non-empty FQCN");
            return false;
        }
        if (!generatedInterfaces.add(ifaceFqcn)) {
            error(errorHandler, "Duplicate -interface '%s' across -group entries".formatted(ifaceFqcn));
            return false;
        }

        var matched = selectClasses(outline, group.classPatterns);
        if (matched.isEmpty()) {
            error(errorHandler, "No generated classes matched -class pattern(s): "
                + patternSummary(group.classPatterns));
            return false;
        }

        var fieldFilter = parseFieldFilter(group.fields);
        var common = intersectProperties(matched, options, fieldFilter, errorHandler);
        if (common.isEmpty()) {
            warn(errorHandler, "No common properties for %d class(es) matching %s; interface '%s' not generated"
                .formatted(matched.size(), patternSummary(group.classPatterns), ifaceFqcn));
            return true;
        }

        JDefinedClass iface;
        try {
            iface = outline.getCodeModel()._class(JMod.PUBLIC, ifaceFqcn, ClassType.INTERFACE);
        } catch (JClassAlreadyExistsException ex) {
            error(errorHandler, "Cannot generate interface '%s': type already exists".formatted(ifaceFqcn));
            return false;
        }

        for (var property : common) {
            iface.method(0, property.type(), property.getterName());
            if (property.setterName() != null) {
                var setter = iface.method(0, void.class, property.setterName());
                setter.param(property.type(), "value");
            }
        }

        for (var classOutline : matched) {
            var implClass = classOutline.implClass;
            if (lacksInterface(implClass, iface.fullName())) {
                implClass._implements(iface);
            }
        }
        return true;
    }

    private static List<ClassOutline> selectClasses(Outline outline, List<Pattern> classPatterns) {
        var result = new ArrayList<ClassOutline>();
        if (classPatterns == null || classPatterns.isEmpty()) {
            return result;
        }
        for (var classOutline : outline.getClasses()) {
            var fqcn = classOutline.implClass.fullName();
            for (var pattern : classPatterns) {
                if (pattern != null && pattern.matcher(fqcn).matches()) {
                    result.add(classOutline);
                    break;
                }
            }
        }
        return result;
    }

    private List<CommonProperty> intersectProperties(
        List<ClassOutline> classes,
        Options options,
        Set<String> fieldFilter,
        ErrorHandler errorHandler
    ) throws SAXException {
        List<Map<String, ClassProperty>> perClass = new ArrayList<>(classes.size());
        for (var classOutline : classes) {
            perClass.add(collectProperties(classOutline, options));
        }

        var first = perClass.getFirst();
        var common = new ArrayList<CommonProperty>();
        for (var entry : first.entrySet()) {
            var propertyName = entry.getKey();
            if (fieldFilter != null && !fieldFilter.contains(propertyName)) {
                continue;
            }
            var seed = entry.getValue();
            var getterName = seed.getterName();
            var type = seed.type();
            var typeKey = typeKey(type);
            var setterName = seed.setterName();
            var includeSetter = setterName != null;

            var presentOnAll = true;
            for (var i = 1; i < perClass.size(); i++) {
                var other = perClass.get(i).get(propertyName);
                if (other == null) {
                    presentOnAll = false;
                    break;
                }
                if (!getterName.equals(other.getterName()) || !typeKey.equals(typeKey(other.type()))) {
                    if (fieldFilter != null) {
                        warn(errorHandler,
                            "Skipping property '%s': getter signature differs across matching classes"
                                .formatted(propertyName));
                    }
                    presentOnAll = false;
                    break;
                }
                if (includeSetter) {
                    if (other.setterName() == null || !setterName.equals(other.setterName())) {
                        includeSetter = false;
                        setterName = null;
                    }
                }
            }
            if (!presentOnAll) {
                continue;
            }

            common.add(new CommonProperty(propertyName, getterName, includeSetter ? setterName : null, type));
        }

        if (fieldFilter != null) {
            for (var requested : fieldFilter) {
                var found = common.stream().anyMatch(p -> p.propertyName().equals(requested));
                if (!found) {
                    warn(errorHandler,
                        "Property '%s' from -fields is not a common property on matching classes"
                            .formatted(requested));
                }
            }
        }
        return common;
    }

    private static Map<String, ClassProperty> collectProperties(ClassOutline classOutline, Options options) {
        var map = new LinkedHashMap<String, ClassProperty>();
        var implClass = classOutline.implClass;
        var dataMode = usesLombokDataAccessors(implClass, options);
        for (var fieldOutline : classOutline.getDeclaredFields()) {
            var property = toClassProperty(fieldOutline, implClass, dataMode);
            if (property != null) {
                map.put(property.propertyName(), property);
            }
        }
        return map;
    }

    private static ClassProperty toClassProperty(FieldOutline fieldOutline, JDefinedClass implClass, boolean dataMode) {
        var prop = fieldOutline.getPropertyInfo();
        var propertyName = prop.getName(false);
        var seed = prop.getName(true);
        var type = fieldOutline.getRawType();
        var collection = prop.isCollection();
        var isBoolean = isPrimitiveBoolean(type);

        String getterName;
        String setterName;
        if (dataMode) {
            // Lombok names from field name (property / field id), not XJC method seed.
            getterName = LombokAccessors.toGetterName(propertyName, isBoolean);
            setterName = LombokAccessors.toSetterName(propertyName, isBoolean);
        } else {
            var existingGetter = findGetter(implClass, seed);
            if (existingGetter != null) {
                getterName = existingGetter.name();
                type = existingGetter.type();
            } else {
                getterName = isBoolean ? "is" + seed : "get" + seed;
            }
            var existingSetter = findSetter(implClass, seed);
            if (collection) {
                // Stock XJC: live list, no setX.
                setterName = null;
            } else if (existingSetter != null) {
                setterName = existingSetter.name();
                if (existingSetter.params().size() == 1) {
                    type = existingSetter.params().getFirst().type();
                }
            } else {
                setterName = "set" + seed;
            }
        }

        if (getterName == null) {
            return null;
        }
        return new ClassProperty(propertyName, getterName, setterName, type);
    }

    /**
     * {@code @Data} already on the class, or an active {@link LombokPlugin} will apply {@code @Data}.
     */
    static boolean usesLombokDataAccessors(JDefinedClass implClass, Options options) {
        if (AnnotationUtils.hasAnnotation(implClass, LOMBOK_DATA)) {
            return true;
        }
        return LombokPlugin.anyActiveAppliesData(options, implClass.fullName());
    }

    private static boolean isPrimitiveBoolean(JType type) {
        return type != null && type.isPrimitive() && "boolean".equals(type.name());
    }

    private static JMethod findGetter(JDefinedClass implClass, String seed) {
        var getName = "get" + seed;
        var isName = "is" + seed;
        for (var method : implClass.methods()) {
            if (!method.params().isEmpty()) {
                continue;
            }
            var name = method.name();
            if ((getName.equals(name) || isName.equals(name)) && !isVoid(method)) {
                return method;
            }
        }
        return null;
    }

    private static JMethod findSetter(JDefinedClass implClass, String seed) {
        var setName = "set" + seed;
        for (var method : implClass.methods()) {
            if (setName.equals(method.name()) && method.params().size() == 1) {
                return method;
            }
        }
        return null;
    }

    private static boolean isVoid(JMethod method) {
        var type = method.type();
        return type != null && "void".equals(type.name());
    }

    private static String typeKey(JType type) {
        return type.fullName();
    }

    private static Set<String> parseFieldFilter(String fieldsOption) {
        if (fieldsOption == null || fieldsOption.isBlank()) {
            return null;
        }
        var set = new LinkedHashSet<String>();
        for (var part : fieldsOption.split(",")) {
            var name = part.trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set.isEmpty() ? null : set;
    }

    private static String patternSummary(List<Pattern> classPatterns) {
        if (classPatterns == null || classPatterns.isEmpty()) {
            return "[]";
        }
        return classPatterns.stream()
            .filter(Objects::nonNull)
            .map(Pattern::pattern)
            .toList()
            .toString();
    }

    private static boolean lacksInterface(JDefinedClass implClass, String interfaceFqcn) {
        var it = implClass._implements();
        while (it.hasNext()) {
            if (interfaceFqcn.equals(it.next().fullName())) {
                return false;
            }
        }
        return true;
    }

    private static void error(ErrorHandler errorHandler, String message) throws SAXException {
        errorHandler.error(new SAXParseException(message, null));
    }

    private static void warn(ErrorHandler errorHandler, String message) throws SAXException {
        errorHandler.warning(new SAXParseException(message, null));
    }

    private record ClassProperty(String propertyName, String getterName, String setterName, JType type) {
    }

    private record CommonProperty(String propertyName, String getterName, String setterName, JType type) {
    }
}
