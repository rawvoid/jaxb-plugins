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
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.ModelUtils;
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
import static io.github.rawvoid.jaxb.utils.ModelUtils.CENUMLEAFINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.setFieldValue;

/**
 * Lifts nested beans and enums toward package scope one parent level at a time.
 * <p>
 * Stock XJC nests anonymous complex types and local enums as member types. This
 * plugin rewrites nesting on the model before BeanGenerator runs:
 * </p>
 * <ul>
 *   <li>{@link CClassInfo#parent()} for beans</li>
 *   <li>{@link CEnumLeafInfo#parent} for enums</li>
 * </ul>
 * <p>
 * <strong>Algorithm.</strong> Each pass every nested type (parent is a
 * {@link CClassInfo}) proposes a one-level move to its grandparent. A proposal
 * is applied only when the simple name is free under the target, no other type
 * claims the same slot in that pass, and (for beans) the move does not create a
 * duplicate {@link CClassInfo#getSqueezedName()} in the package (ObjectFactory
 * value-factory methods). Passes repeat until none move.
 * </p>
 * <p>
 * Beans and enums share one simple-name namespace under each parent. Package
 * parents are canonicalized via {@link Model#getPackage}.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xpromote-nested-class", description = "Lift nested classes and enums toward package scope until a name conflict")
public class PromoteNestedClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(PromoteNestedClassPlugin.class);

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        var hops = 0;
        while (true) {
            var batch = promoteOneLevel(model);
            if (batch == 0) {
                break;
            }
            hops += batch;
        }
        if (hops > 0) {
            log.info("Promoted {} nested type placement(s) (beans and enums)", hops);
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        return true;
    }

    /**
     * One fixed-point iteration: every nested bean/enum may move at most one level up.
     *
     * @return number of types re-parented in this pass
     */
    private int promoteOneLevel(Model model) {
        Map<CClassInfoParent, Set<String>> occupied = new HashMap<>();
        for (var bean : model.beans().values()) {
            occupy(occupied, canonicalParent(model, bean.parent()), bean.shortName);
        }
        for (var enumInfo : model.enums().values()) {
            occupy(occupied, canonicalParent(model, enumInfo.parent), enumInfo.shortName);
        }

        // Simultaneous short-name proposals: only unique free slots proceed.
        Map<ProposalKey, List<Move>> proposals = new LinkedHashMap<>();
        for (var bean : model.beans().values()) {
            if (bean.parent() instanceof CClassInfo outer) {
                var target = canonicalParent(model, outer.parent());
                propose(proposals, target, bean.shortName, Move.bean(bean, target));
            }
        }
        for (var enumInfo : model.enums().values()) {
            if (enumInfo.parent instanceof CClassInfo outer) {
                var target = canonicalParent(model, outer.parent());
                propose(proposals, target, enumInfo.shortName, Move.enumInfo(enumInfo, target));
            }
        }

        var shortNameOk = new ArrayList<Move>();
        for (var entry : proposals.entrySet()) {
            var key = entry.getKey();
            var candidates = entry.getValue();
            if (candidates.size() != 1) {
                continue;
            }
            if (occupied.getOrDefault(key.target(), Set.of()).contains(key.name())) {
                continue;
            }
            shortNameOk.add(candidates.getFirst());
            occupy(occupied, key.target(), key.name());
        }

        // Apply each move; undo bean moves that break ObjectFactory squeezed names.
        var moved = 0;
        for (var move : shortNameOk) {
            if (move.bean != null) {
                var previous = move.bean.parent();
                setFieldValue(CCLASSINFO_PARENT_FIELD, move.bean, move.target);
                if (ModelUtils.hasObjectFactorySqueezedCollision(model)) {
                    setFieldValue(CCLASSINFO_PARENT_FIELD, move.bean, previous);
                    log.debug(
                        "Skip promoting {} — ObjectFactory squeezed name collision",
                        move.bean.fullName()
                    );
                    continue;
                }
            } else {
                setFieldValue(CENUMLEAFINFO_PARENT_FIELD, move.enumInfo, move.target);
            }
            moved++;
        }
        return moved;
    }

    private static CClassInfoParent canonicalParent(Model model, CClassInfoParent parent) {
        if (parent instanceof CClassInfoParent.Package pkgParent) {
            return model.getPackage(pkgParent.pkg);
        }
        return parent;
    }

    private static void occupy(Map<CClassInfoParent, Set<String>> occupied, CClassInfoParent parent, String shortName) {
        occupied.computeIfAbsent(parent, k -> new HashSet<>()).add(normalize(shortName));
    }

    private static void propose(
        Map<ProposalKey, List<Move>> proposals,
        CClassInfoParent target,
        String shortName,
        Move move
    ) {
        proposals.computeIfAbsent(new ProposalKey(target, normalize(shortName)), k -> new ArrayList<>()).add(move);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record ProposalKey(CClassInfoParent target, String name) {
    }

    private record Move(CClassInfo bean, CEnumLeafInfo enumInfo, CClassInfoParent target) {
        static Move bean(CClassInfo bean, CClassInfoParent target) {
            return new Move(bean, null, target);
        }

        static Move enumInfo(CEnumLeafInfo enumInfo, CClassInfoParent target) {
            return new Move(null, enumInfo, target);
        }
    }
}
