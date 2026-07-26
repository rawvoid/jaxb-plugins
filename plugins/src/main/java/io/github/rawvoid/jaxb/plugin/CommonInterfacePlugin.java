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
import com.sun.tools.xjc.outline.Outline;
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
 * Detects common bean properties across a user-selected set of generated classes and
 * emits a Java interface declaring their accessors. Matching classes then
 * {@code implements} that interface. Fields and XML bindings are left untouched.
 *
 * <p>CLI example:</p>
 * <pre>{@code
 * -Xcommon-interface \
 *   -class=.*Request \
 *   -interface=com.example.CommonRequest \
 *   -fields=id,timestamp
 * }</pre>
 *
 * <p><b>Rules:</b></p>
 * <ul>
 *   <li>{@code -class} (required, repeatable): regex against generated class FQCN.</li>
 *   <li>{@code -interface} (required): FQCN of the interface to generate.</li>
 *   <li>{@code -fields} (optional): comma-separated Java property names; omit for full
 *       intersection of matching classes.</li>
 *   <li>Getter is always declared when a property is common. Setter is declared only when
 *       every participating class has a one-argument setter with the same parameter type
 *       (collection properties usually have no setter under stock XJC).</li>
 *   <li>Zero class matches → error. Empty common property set → warning, no generation.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xcommon-interface", description = "Generate an interface from common property accessors and implement it on matching classes")
public class CommonInterfacePlugin extends AbstractPlugin {

    @Option(name = "class", required = true, placeholder = "regex",
        description = "Regex matching fully-qualified generated class names (repeatable)")
    List<Pattern> classPatterns;

    @Option(name = "interface", required = true, placeholder = "FQCN",
        description = "Fully-qualified name of the interface to generate")
    String interfaceName;

    @Option(name = "fields", placeholder = "a,b,c",
        description = "Optional comma-separated Java property names to consider (default: full intersection)")
    String fields;

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var ifaceFqcn = interfaceName == null ? "" : interfaceName.trim();
        if (ifaceFqcn.isEmpty()) {
            error(errorHandler, "Option -interface is required and must be a non-empty FQCN");
            return false;
        }

        var matched = selectClasses(outline);
        if (matched.isEmpty()) {
            error(errorHandler, "No generated classes matched -class pattern(s): " + patternSummary());
            return false;
        }

        var fieldFilter = parseFieldFilter(fields);
        var common = intersectProperties(matched, fieldFilter, errorHandler);
        if (common.isEmpty()) {
            warn(errorHandler, "No common properties for %d class(es) matching %s; interface '%s' not generated"
                .formatted(matched.size(), patternSummary(), ifaceFqcn));
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
            iface.method(0, property.getterReturnType(), property.getterName());
            if (property.setterParamType() != null) {
                var setter = iface.method(0, void.class, property.setterName());
                setter.param(property.setterParamType(), "value");
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

    private List<ClassOutline> selectClasses(Outline outline) {
        var result = new ArrayList<ClassOutline>();
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
        Set<String> fieldFilter,
        ErrorHandler errorHandler
    ) throws SAXException {
        List<Map<String, ClassProperty>> perClass = new ArrayList<>(classes.size());
        for (var classOutline : classes) {
            perClass.add(collectProperties(classOutline));
        }

        var first = perClass.getFirst();
        var common = new ArrayList<CommonProperty>();
        for (var entry : first.entrySet()) {
            var propertyName = entry.getKey();
            if (fieldFilter != null && !fieldFilter.contains(propertyName)) {
                continue;
            }
            var seed = entry.getValue();
            if (seed.getter() == null) {
                continue;
            }

            var getterName = seed.getter().name();
            var getterType = seed.getter().type();
            var getterTypeKey = typeKey(getterType);
            JMethod setter = seed.setter();
            var setterParamType = setter != null ? setter.params().getFirst().type() : null;
            var setterParamKey = setterParamType != null ? typeKey(setterParamType) : null;
            var includeSetter = setterParamType != null;

            var presentOnAll = true;
            for (var i = 1; i < perClass.size(); i++) {
                var other = perClass.get(i).get(propertyName);
                if (other == null || other.getter() == null) {
                    presentOnAll = false;
                    break;
                }
                if (!getterName.equals(other.getter().name())
                    || !getterTypeKey.equals(typeKey(other.getter().type()))) {
                    if (fieldFilter != null) {
                        warn(errorHandler,
                            "Skipping property '%s': getter signature differs across matching classes"
                                .formatted(propertyName));
                    }
                    presentOnAll = false;
                    break;
                }
                var otherSetter = other.setter();
                if (includeSetter) {
                    if (otherSetter == null || otherSetter.params().size() != 1
                        || !setterParamKey.equals(typeKey(otherSetter.params().getFirst().type()))) {
                        includeSetter = false;
                        setterParamType = null;
                    }
                }
            }
            if (!presentOnAll) {
                continue;
            }

            common.add(new CommonProperty(
                propertyName,
                getterName,
                getterType,
                includeSetter ? "set" + seed.seed() : null,
                includeSetter ? setterParamType : null
            ));
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

    private static Map<String, ClassProperty> collectProperties(ClassOutline classOutline) {
        var map = new LinkedHashMap<String, ClassProperty>();
        var implClass = classOutline.implClass;
        for (var fieldOutline : classOutline.getDeclaredFields()) {
            var prop = fieldOutline.getPropertyInfo();
            var propertyName = prop.getName(false);
            var seed = prop.getName(true);
            var getter = findGetter(implClass, seed);
            var setter = findSetter(implClass, seed);
            map.put(propertyName, new ClassProperty(propertyName, seed, getter, setter));
        }
        return map;
    }

    private static JMethod findGetter(JDefinedClass implClass, String seed) {
        var getName = "get" + seed;
        var isName = "is" + seed;
        for (var method : implClass.methods()) {
            if (method.params().size() != 0) {
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

    private String patternSummary() {
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

    private record ClassProperty(String propertyName, String seed, JMethod getter, JMethod setter) {
    }

    private record CommonProperty(
        String propertyName,
        String getterName,
        JType getterReturnType,
        String setterName,
        JType setterParamType
    ) {
    }
}
