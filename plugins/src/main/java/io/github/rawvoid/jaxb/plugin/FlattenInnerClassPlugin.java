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
import java.util.function.Consumer;

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
 * is applied only when the simple name is free under the target <em>and</em>
 * no other type claims the same slot in that pass. Passes repeat until none
 * move. Name checks are case-insensitive so they stay aligned with CodeModel's
 * nested-class maps and case-insensitive filesystems.
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
@Option(name = "Xflatten-inner-class", description = "Lift nested classes and enums toward package scope until a name conflict")
public class FlattenInnerClassPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(FlattenInnerClassPlugin.class);

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
        Map<ProposalKey, List<Consumer<CClassInfoParent>>> proposals = new LinkedHashMap<>();
        for (var bean : model.beans().values()) {
            if (bean.parent() instanceof CClassInfo outer) {
                var target = canonicalParent(model, outer.parent());
                propose(proposals, target, bean.shortName,
                    newParent -> setFieldValue(CCLASSINFO_PARENT_FIELD, bean, newParent));
            }
        }
        for (var enumInfo : model.enums().values()) {
            if (enumInfo.parent instanceof CClassInfo outer) {
                var target = canonicalParent(model, outer.parent());
                propose(proposals, target, enumInfo.shortName,
                    newParent -> setFieldValue(CENUMLEAFINFO_PARENT_FIELD, enumInfo, newParent));
            }
        }

        var moved = 0;
        for (var entry : proposals.entrySet()) {
            var key = entry.getKey();
            var candidates = entry.getValue();
            // More than one claimant for the same (parent, name) → symmetric stop.
            if (candidates.size() != 1) {
                continue;
            }
            // Target already has a bean or enum with this simple name.
            if (occupied.getOrDefault(key.target(), Set.of()).contains(key.name())) {
                continue;
            }

            candidates.getFirst().accept(key.target());
            // Mark the slot taken for later proposals in this same pass.
            // (The old parent slot is left occupied until the next pass rebuilds the map.)
            occupy(occupied, key.target(), key.name());
            moved++;
        }
        return moved;
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
        Map<ProposalKey, List<Consumer<CClassInfoParent>>> proposals,
        CClassInfoParent target,
        String shortName,
        Consumer<CClassInfoParent> reparent
    ) {
        var key = new ProposalKey(target, normalize(shortName));
        proposals.computeIfAbsent(key, k -> new ArrayList<>()).add(reparent);
    }

    /** Case-insensitive key so collisions match CodeModel / case-folding filesystems. */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record ProposalKey(CClassInfoParent target, String name) {
    }
}
