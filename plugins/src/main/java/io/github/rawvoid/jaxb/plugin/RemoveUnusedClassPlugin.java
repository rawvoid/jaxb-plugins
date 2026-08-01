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
import io.github.rawvoid.jaxb.plugin.option.AbstractPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import io.github.rawvoid.jaxb.plugin.xjc.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import java.util.*;
import java.util.regex.Pattern;

/**
 * XJC plugin that removes unreferenced JAXB classes and enums from the {@link Model}
 * during {@link #postProcessModel(Model, ErrorHandler)}.
 *
 * <p>Uses a graph reachability analysis (Mark &amp; Sweep) starting from global XML root elements
 * and user-configured white-list patterns to identify and prune unreachable classes and enums.</p>
 *
 * @author Rawvoid
 */
@Option(name = "Xremove-unused-class", description = "Removes unreferenced JAXB classes and enums from the model in postProcessModel")
public class RemoveUnusedClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(RemoveUnusedClassPlugin.class);

    @Option(name = "keep-classes", description = "Repeatable regex patterns to forcibly keep matching classes or enums as root elements.")
    List<Pattern> keepClasses = new ArrayList<>();

    @Option(name = "preserve-polymorphism", defaultValue = "false", description = "Whether to treat subclasses of a reachable base class as reachable (default: false)")
    Boolean preservePolymorphism = false;

    @Option(name = "verbose", defaultValue = "false", description = "Enable detailed logging of reachability and deleted classes (default: false)")
    Boolean verbose = false;

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        if (model == null) {
            return;
        }

        var roots = new LinkedHashSet<CTypeInfo>();
        var queue = new ArrayDeque<CTypeInfo>();

        // 1. Collect Root Set
        collectRoots(model, roots, queue);

        // 2. Traverse reachability graph
        var reachable = new HashSet<>(roots);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            traverseReachableEdges(current, reachable, queue);
        }

        // 3. Identify dead classes and enums
        var deadClasses = model.beans().values().stream()
            .filter(c -> !reachable.contains(c))
            .toList();

        var deadEnums = model.enums().values().stream()
            .filter(e -> !reachable.contains(e))
            .toList();

        if (deadClasses.isEmpty() && deadEnums.isEmpty()) {
            if (Boolean.TRUE.equals(verbose)) {
                log.info("[Xremove-unused-class] No unreferenced classes or enums found.");
            }
            return;
        }

        // 4. Remove dead classes
        for (var deadClass : deadClasses) {
            ModelUtils.removeClass(model, deadClass);
            if (Boolean.TRUE.equals(verbose)) {
                log.info("[Xremove-unused-class] Removed unreferenced class: {}", deadClass.fullName());
            }
        }

        // 5. Remove dead enums
        for (var deadEnum : deadEnums) {
            ModelUtils.removeEnum(model, deadEnum);
            if (Boolean.TRUE.equals(verbose)) {
                log.info("[Xremove-unused-class] Removed unreferenced enum: {}", deadEnum.fullName());
            }
        }

        // 6. Clean up orphan CElementInfo objects pointing to dead classes/enums
        cleanOrphanElements(model, deadClasses, deadEnums);
    }

    private void collectRoots(Model model, Set<CTypeInfo> roots, Queue<CTypeInfo> queue) {
        // Roots from global CElementInfo declarations
        for (var elementInfo : model.getAllElements()) {
            var contentType = elementInfo.getContentType();
            if (contentType != null) {
                addRoot(contentType, roots, queue);
            }
            var property = elementInfo.getProperty();
            if (property != null) {
                for (var refTarget : property.ref()) {
                    addRoot(refTarget, roots, queue);
                }
            }
        }

        // Roots from classes marked as root elements (isElement == true or elementName != null)
        for (var bean : model.beans().values()) {
            if (bean.isElement() || bean.getElementName() != null) {
                addRoot(bean, roots, queue);
            }
        }

        // Roots matching keepClasses regex patterns
        if (keepClasses != null && !keepClasses.isEmpty()) {
            for (var bean : model.beans().values()) {
                if (matchesKeepPattern(bean.fullName(), bean.shortName, keepClasses)) {
                    addRoot(bean, roots, queue);
                }
            }
            for (var enumInfo : model.enums().values()) {
                if (matchesKeepPattern(enumInfo.fullName(), enumInfo.shortName, keepClasses)) {
                    addRoot(enumInfo, roots, queue);
                }
            }
        }
    }

    private void addRoot(CTypeInfo typeInfo, Set<CTypeInfo> roots, Queue<CTypeInfo> queue) {
        if (typeInfo != null && roots.add(typeInfo)) {
            queue.add(typeInfo);
        }
    }

    private void traverseReachableEdges(CTypeInfo current, Set<CTypeInfo> reachable, Queue<CTypeInfo> queue) {
        if (current instanceof CClassInfo classInfo) {
            // Superclass
            var baseClass = classInfo.getBaseClass();
            if (baseClass != null) {
                addReachable(baseClass, reachable, queue);
            }

            // Subclasses (Polymorphism)
            if (Boolean.TRUE.equals(preservePolymorphism) && classInfo.hasSubClasses()) {
                var subClasses = classInfo.listSubclasses();
                while (subClasses.hasNext()) {
                    var subClass = subClasses.next();
                    if (subClass != null) {
                        addReachable(subClass, reachable, queue);
                    }
                }
            }

            // Properties
            for (var prop : classInfo.getProperties()) {
                for (var refTarget : prop.ref()) {
                    addReachable(refTarget, reachable, queue);
                }
                var adapter = prop.getAdapter();
                if (adapter != null) {
                    if (adapter.adapterType instanceof CTypeInfo adapterType) {
                        addReachable(adapterType, reachable, queue);
                    }
                    if (adapter.customType instanceof CTypeInfo customType) {
                        addReachable(customType, reachable, queue);
                    }
                }
            }
        } else if (current instanceof CElementInfo elementInfo) {
            if (elementInfo.getContentType() != null) {
                addReachable(elementInfo.getContentType(), reachable, queue);
            }
            if (elementInfo.getProperty() != null) {
                for (var refTarget : elementInfo.getProperty().ref()) {
                    addReachable(refTarget, reachable, queue);
                }
            }
        }
    }

    private void addReachable(CTypeInfo target, Set<CTypeInfo> reachable, Queue<CTypeInfo> queue) {
        if (target instanceof CClassInfo || target instanceof CEnumLeafInfo || target instanceof CElementInfo) {
            if (reachable.add(target)) {
                queue.add(target);
            }
        }
    }

    private static boolean matchesKeepPattern(String fullName, String shortName, List<Pattern> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (var pattern : patterns) {
            if ((fullName != null && pattern.matcher(fullName).find())
                || (shortName != null && pattern.matcher(shortName).find())) {
                return true;
            }
        }
        return false;
    }

    private void cleanOrphanElements(Model model, List<CClassInfo> deadClasses, List<CEnumLeafInfo> deadEnums) {
        var deadSet = new HashSet<CTypeInfo>(deadClasses);
        deadSet.addAll(deadEnums);

        var orphanElements = new ArrayList<CElementInfo>();
        for (var elementInfo : model.getAllElements()) {
            var contentType = elementInfo.getContentType();
            if (contentType != null && deadSet.contains(contentType)) {
                orphanElements.add(elementInfo);
            }
        }

        for (var orphan : orphanElements) {
            ModelUtils.removeElementInfo(model, orphan);
        }
    }
}
