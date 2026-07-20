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
 * is applied only when:
 * </p>
 * <ul>
 *   <li>the simple name is free under the target</li>
 *   <li>no other type claims the same slot in that pass</li>
 *   <li>for beans: the move would not introduce a duplicate
 *       {@link CClassInfo#getSqueezedName()} in the owner package (ObjectFactory
 *       value-factory methods use that name)</li>
 * </ul>
 * <p>
 * Passes repeat until none move. Name checks are case-insensitive so they stay
 * aligned with CodeModel's nested-class maps and case-insensitive filesystems.
 * </p>
 * <p>
 * <strong>Beans and enums share one namespace</strong> under each parent: a bean
 * named {@code Status} and an enum named {@code Status} block each other.
 * Types whose parent is already a package (or a {@code CElementInfo}) are left
 * alone.
 * </p>
 * <p>
 * <strong>Package identity.</strong> XJC sometimes builds
 * {@link CClassInfoParent.Package} via {@code new Package(jPackage)} (e.g. global
 * enums) instead of {@link Model#getPackage}. Occupancy and promotion always
 * canonicalize package parents through {@code model.getPackage} so those wrappers
 * compare as the same parent.
 * </p>
 * <p>
 * <strong>Side effect.</strong> {@link CClassInfo#getSqueezedName()} follows the
 * parent chain, so ObjectFactory method names become shorter after a successful
 * lift (for example {@code createFlattenRootGroup} → {@code createGroup}).
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xpromote-nested-class", description = "Lift nested classes and enums toward package scope until a name conflict")
public class PromoteNestedClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(PromoteNestedClassPlugin.class);

    /**
     * Nesting is defined on the model; changing parents here is enough for
     * BeanGenerator to emit the right containers. {@link #run} is a no-op.
     */
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
            // Counts one-level moves, not distinct types (a deep type may hop several times).
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
        // Occupied simple names under each parent — beans and enums compete together.
        Map<CClassInfoParent, Set<String>> occupied = new HashMap<>();
        for (var bean : model.beans().values()) {
            occupy(occupied, canonicalParent(model, bean.parent()), bean.shortName);
        }
        for (var enumInfo : model.enums().values()) {
            occupy(occupied, canonicalParent(model, enumInfo.parent), enumInfo.shortName);
        }

        // Proposal key: (target parent, normalized name). Only unique free slots apply.
        // Simultaneous evaluation: if two types would land on the same name, both stop
        // rather than letting traversal order pick a winner.
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

        // Short-name filter first (unique free slot).
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
            // Reserve the slot for later short-name proposals in this same pass.
            occupy(occupied, key.target(), key.name());
        }

        // ObjectFactory squeezed-name filter for beans (enums have no value factories).
        // Apply greedily so multi-move batches stay collision-free.
        Map<CClassInfo, CClassInfoParent> parentOverrides = new HashMap<>();
        var accepted = new ArrayList<Move>();
        for (var move : shortNameOk) {
            if (move.bean != null) {
                var trial = new HashMap<>(parentOverrides);
                trial.put(move.bean, move.target);
                if (hasSqueezedCollision(model, trial)) {
                    log.debug(
                        "Skip promoting {} — would collide on ObjectFactory squeezed name",
                        move.bean.fullName()
                    );
                    continue;
                }
                parentOverrides.put(move.bean, move.target);
            }
            accepted.add(move);
        }

        for (var move : accepted) {
            if (move.bean != null) {
                setFieldValue(CCLASSINFO_PARENT_FIELD, move.bean, move.target);
            } else {
                setFieldValue(CENUMLEAFINFO_PARENT_FIELD, move.enumInfo, move.target);
            }
        }
        return accepted.size();
    }

    /**
     * Whether non-abstract beans would register duplicate ObjectFactory create methods
     * under the given parent overrides (plus current model parents for unmoved types).
     */
    private static boolean hasSqueezedCollision(Model model, Map<CClassInfo, CClassInfoParent> parentOverrides) {
        Map<SqueezedSlot, CClassInfo> seen = new HashMap<>();
        for (var bean : model.beans().values()) {
            if (bean.isAbstract()) {
                continue;
            }
            var squeezed = squeezedWithOverrides(bean, parentOverrides);
            var slot = new SqueezedSlot(bean.getOwnerPackage(), squeezed);
            var previous = seen.put(slot, bean);
            if (previous != null) {
                return true;
            }
        }
        return false;
    }

    private static String squeezedWithOverrides(
        CClassInfo bean,
        Map<CClassInfo, CClassInfoParent> parentOverrides
    ) {
        return appendSqueezed(parentOf(bean, parentOverrides), parentOverrides) + bean.shortName;
    }

    private static CClassInfoParent parentOf(
        CClassInfo bean,
        Map<CClassInfo, CClassInfoParent> parentOverrides
    ) {
        return parentOverrides.getOrDefault(bean, bean.parent());
    }

    /**
     * Mirrors XJC {@code CClassInfo} squeezed-name visitor with optional parent overrides.
     */
    private static String appendSqueezed(
        CClassInfoParent parent,
        Map<CClassInfo, CClassInfoParent> parentOverrides
    ) {
        return switch (parent) {
            case CClassInfo bean -> appendSqueezed(parentOf(bean, parentOverrides), parentOverrides)
                + bean.shortName;
            case CElementInfo element -> appendSqueezed(element.parent, parentOverrides)
                + element.shortName();
            case CClassInfoParent.Package ignored -> "";
            case null -> "";
            default -> "";
        };
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

    private static void occupy(Map<CClassInfoParent, Set<String>> occupied, CClassInfoParent parent, String shortName) {
        occupied.computeIfAbsent(parent, k -> new HashSet<>()).add(normalize(shortName));
    }

    private static void propose(
        Map<ProposalKey, List<Move>> proposals,
        CClassInfoParent target,
        String shortName,
        Move move
    ) {
        var key = new ProposalKey(target, normalize(shortName));
        proposals.computeIfAbsent(key, k -> new ArrayList<>()).add(move);
    }

    /** Case-insensitive key so collisions match CodeModel / case-folding filesystems. */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record ProposalKey(CClassInfoParent target, String name) {
    }

    private record SqueezedSlot(JPackage pkg, String squeezed) {
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
