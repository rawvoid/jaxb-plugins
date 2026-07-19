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
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CClassInfoParent;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.outline.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CCLASSINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * Lifts nested classes toward package scope one parent level at a time.
 * <p>
 * Nested anonymous complex types become deep member classes under stock XJC.
 * This plugin rewrites {@link CClassInfo#parent()} in {@link #postProcessModel}
 * so BeanGenerator emits shallower (often top-level) types. A class stops
 * moving when its simple name already exists under the target parent, or when
 * another class would claim the same slot in the same pass.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xflatten-inner-class", description = "Lift nested classes toward package scope until a name conflict")
public class FlattenInnerClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(FlattenInnerClassPlugin.class);

    /**
     * Nesting lives on the model ({@code CClassInfo.parent}). Changing it here
     * lets BeanGenerator place classes correctly; {@link #run} has nothing left to do.
     */
    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var moved = 0;
        while (true) {
            var batch = promoteOneLevel(model);
            if (batch == 0) {
                break;
            }
            moved += batch;
        }
        if (moved > 0) {
            log.info("Promoted {} nested class placement(s)", moved);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * Attempts a single-level promotion for every nested bean that can move safely.
     *
     * @return number of classes re-parented in this pass
     */
    private int promoteOneLevel(Model model) {
        // Occupied simple names under each parent (case-insensitive, matches CodeModel maps).
        Map<CClassInfoParent, Set<String>> occupied = new HashMap<>();
        for (var bean : model.beans().values()) {
            occupied
                .computeIfAbsent(bean.parent(), k -> new HashSet<>())
                .add(normalize(bean.shortName));
        }

        // Proposal key: target parent + name. Only unique free slots are accepted.
        Map<ProposalKey, List<CClassInfo>> proposals = new LinkedHashMap<>();
        for (var bean : model.beans().values()) {
            if (!(bean.parent() instanceof CClassInfo immediateParent)) {
                continue;
            }
            var target = immediateParent.parent();
            var key = new ProposalKey(target, normalize(bean.shortName));
            proposals.computeIfAbsent(key, k -> new ArrayList<>()).add(bean);
        }

        var moved = 0;
        for (var entry : proposals.entrySet()) {
            var key = entry.getKey();
            var candidates = entry.getValue();
            if (candidates.size() != 1) {
                // Symmetric stop: two+ classes would land on the same name under the same parent.
                continue;
            }
            if (occupied.getOrDefault(key.target(), Set.of()).contains(key.name())) {
                continue;
            }

            var bean = candidates.getFirst();
            setFieldValue(CCLASSINFO_PARENT_FIELD, bean, key.target());
            occupied.computeIfAbsent(key.target(), k -> new HashSet<>()).add(key.name());
            moved++;
        }
        return moved;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record ProposalKey(CClassInfoParent target, String name) {
    }
}
