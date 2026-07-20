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
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CReferencePropertyInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.outline.Outline;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Renames multi-element properties produced by XJC to a short plural base name.
 * <p>
 * A property is multi-element when it binds more than one element/type:
 * </p>
 * <ul>
 *   <li>{@link CElementPropertyInfo} with {@code getTypes().size() > 1}
 *       (typically {@code @XmlElements})</li>
 *   <li>{@link CReferencePropertyInfo} with {@code getElements().size() > 1}
 *       (typically {@code @XmlElementRefs}, including multi-member XJC
 *       {@code rest}/{@code content} catch-alls)</li>
 * </ul>
 * <p>
 * Names are rewritten to a plural base (default {@code items}) with numeric suffixes
 * when needed: {@code items}, {@code items2}, {@code items3}, … — stable within a bean
 * (sorted by original private name) and free of clashes with existing properties.
 * Single-element lists and single-member catch-alls are left unchanged.
 * </p>
 * <p>
 * Field renames do not affect {@code ObjectFactory} (which keys on class squeezed names).
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xrename-multi-element-prop",
    description = "Rename multi-element properties to a short plural base (items, items2, …)")
public class RenameMultiElementPropPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(RenameMultiElementPropPlugin.class);

    private static final NameConverter NAMES = NameConverter.standard;

    @Option(name = "name", defaultValue = "items",
        description = "Plural base name for renamed properties (default: items)")
    String name = "items";

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        if (name == null || name.isBlank() || !isValidBaseName(name)) {
            if (name != null && !name.isBlank()) {
                log.warn("Invalid -name '{}'; falling back to 'items'", name);
            }
            name = "items";
        }

        var renamed = 0;
        for (var bean : model.beans().values()) {
            renamed += handleClass(bean);
        }
        if (renamed > 0) {
            log.info("Renamed {} multi-element property name(s) using base '{}'", renamed, name);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    private int handleClass(CClassInfo bean) {
        var properties = bean.getProperties();
        Set<String> occupied = new HashSet<>();
        for (var prop : properties) {
            occupied.add(normalize(prop.getName(false)));
        }

        var targets = new ArrayList<CPropertyInfo>();
        for (var prop : properties) {
            if (isMultiElementProperty(prop)) {
                targets.add(prop);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }

        targets.sort(Comparator.comparing(p -> p.getName(false), String.CASE_INSENSITIVE_ORDER));

        var count = 0;
        for (var prop : targets) {
            occupied.remove(normalize(prop.getName(false)));
            var seed = allocateName(occupied);
            var privateName = NAMES.toVariableName(seed);
            var publicName = NAMES.toPropertyName(seed);
            occupied.add(normalize(privateName));
            prop.setName(false, privateName);
            prop.setName(true, publicName);
            count++;
        }
        return count;
    }

    /**
     * More than one bound element/type — the structural signal for multi-element properties.
     */
    static boolean isMultiElementProperty(CPropertyInfo prop) {
        if (prop instanceof CElementPropertyInfo elementProp) {
            return elementProp.getTypes().size() > 1;
        }
        if (prop instanceof CReferencePropertyInfo refProp) {
            return refProp.getElements().size() > 1;
        }
        return false;
    }

    private String allocateName(Set<String> occupied) {
        var index = 1;
        while (true) {
            var seed = index == 1 ? name : name + index;
            var privateName = NAMES.toVariableName(seed);
            if (!occupied.contains(normalize(privateName))) {
                return seed;
            }
            index++;
        }
    }

    private static boolean isValidBaseName(String base) {
        var privateName = NAMES.toVariableName(base);
        var publicName = NAMES.toPropertyName(base);
        return JJavaName.isJavaIdentifier(privateName) && JJavaName.isJavaIdentifier(publicName);
    }

    private static String normalize(String n) {
        return n.toLowerCase(Locale.ROOT);
    }
}
