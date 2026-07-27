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

import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XJC integration tests for {@link ElementWrapperPlugin}.
 * Uses dedicated {@code element-wrapper.xsd} only.
 */
public class ElementWrapperPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.element_wrapper";
    private static final String ROOT = PKG.replace(".", "\\.") + "\\.Root";
    private static final String MIXED = PKG.replace(".", "\\.") + "\\.MixedBag";
    private static final String GROUP_SHELL = PKG.replace(".", "\\.") + "\\.GroupShell";

    private static void assertListField(
        Class<?> owner,
        String fieldName,
        Class<?> itemType,
        String xmlElementName
    ) throws Exception {
        var field = owner.getDeclaredField(fieldName);
        assertThat(List.class.isAssignableFrom(field.getType())).as(fieldName).isTrue();
        assertThat(listItemType(field)).isEqualTo(itemType);

        var wrapper = field.getAnnotation(XmlElementWrapper.class);
        assertThat(wrapper).as("@XmlElementWrapper on %s", fieldName).isNotNull();

        var element = field.getAnnotation(XmlElement.class);
        assertThat(element).as("@XmlElement on %s", fieldName).isNotNull();
        assertThat(element.name()).isEqualTo(xmlElementName);
    }

    private static Class<?> listItemType(Field field) {
        var generic = field.getGenericType();
        assertThat(generic).isInstanceOf(ParameterizedType.class);
        var args = ((ParameterizedType) generic).getActualTypeArguments();
        assertThat(args).hasSize(1);
        return (Class<?>) args[0];
    }

    private static Set<String> simpleNames(List<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }

    private static Set<String> methodNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("element-wrapper.xsd");
    }

    @Test
    void baselineKeepsWrapperTypes() throws Exception {
        var classes = testExecute(List.of(), ROOT, (source, clazz) -> {
            assertThat(clazz.getDeclaredField("tags").getType().getSimpleName()).isEqualTo("TagList");
            assertThat(clazz.getDeclaredField("products").getType().getSimpleName()).isEqualTo("ProductList");
            assertThat(clazz.getDeclaredField("tags").getAnnotation(XmlElementWrapper.class)).isNull();
        });
        assertThat(simpleNames(classes)).contains(
            "TagList", "ProductList", "CodeList", "NoteList", "BatchList", "GroupList", "SharedItems", "AliasList");
    }

    @Test
    void flattensStringAndComplexCollectionsPreservingOrder() throws Exception {
        var classes = testExecute(List.of("-Xelement-wrapper"), ROOT, (source, clazz) -> {
            assertListField(clazz, "tags", String.class, "tag");
            assertListField(clazz, "notes", String.class, "note");

            var productType = listItemType(clazz.getDeclaredField("products"));
            assertThat(productType.getSimpleName()).isEqualTo("Product");
            assertListField(clazz, "products", productType, "product");
            assertThat(productType.getDeclaredField("sku").getType()).isEqualTo(String.class);
            assertThat(productType.getDeclaredField("price").getType()).isEqualTo(BigDecimal.class);

            // Flattened properties keep original propOrder slots.
            assertThat(clazz.getAnnotation(XmlType.class).propOrder()).containsExactly(
                "title", "tags", "products", "codes", "aliases", "notes", "itemBatch", "shared", "mixed", "groups",
                "total", "tagCarrier", "namedTagCarrier");

            var fieldNames = Arrays.stream(clazz.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> !n.contains("$"))
                .toList();
            assertThat(fieldNames).containsExactly(
                "title", "tags", "products", "codes", "aliases", "notes", "itemBatch", "shared", "mixed", "groups",
                "total", "tagCarrier", "namedTagCarrier");

            // Collection properties: getter only.
            assertThat(methodNames(clazz)).contains("getTags", "getProducts", "getNotes");
            assertThat(methodNames(clazz)).doesNotContain("setTags", "setProducts", "setNotes");
        });

        assertThat(simpleNames(classes))
            .contains("Product")
            .doesNotContain("TagList", "ProductList", "NoteList");
    }

    @Test
    void annotatesRequiredAndExplicitWrapperName() throws Exception {
        testExecute(List.of("-Xelement-wrapper"), ROOT, (source, clazz) -> {
            var codes = clazz.getDeclaredField("codes").getAnnotation(XmlElementWrapper.class);
            assertThat(codes).isNotNull();
            assertThat(codes.required()).isTrue();
            assertThat(codes.nillable()).isFalse();
            assertThat(codes.name()).isEqualTo("##default");
            assertThat(codes.namespace()).isEqualTo("##default");
            assertListField(clazz, "codes", String.class, "code");

            // XML local name "item-batch" ≠ Java property "itemBatch".
            var batch = clazz.getDeclaredField("itemBatch").getAnnotation(XmlElementWrapper.class);
            assertThat(batch).isNotNull();
            assertThat(batch.name()).isEqualTo("item-batch");
            assertThat(batch.namespace()).isEqualTo("##default");
            assertListField(clazz, "itemBatch", String.class, "item");
        });
    }

    @Test
    void flattensNillableComplexOuterWithWrapperNillable() throws Exception {
        // XJC models nillable+optional complex elements as CReferencePropertyInfo /
        // JAXBElement; the plugin rewrites them into List + @XmlElementWrapper(nillable=true).
        var classes = testExecute(List.of("-Xelement-wrapper"), ROOT, (source, clazz) -> {
            assertListField(clazz, "aliases", String.class, "alias");
            var wrapper = clazz.getDeclaredField("aliases").getAnnotation(XmlElementWrapper.class);
            assertThat(wrapper).isNotNull();
            assertThat(wrapper.nillable()).isTrue();
            assertThat(wrapper.required()).isFalse();
            assertThat(wrapper.name()).isEqualTo("##default");
        });
        // Shell only used as nillable wrapper → removed after flatten.
        assertThat(simpleNames(classes)).doesNotContain("AliasList");
    }

    // --- helpers ---

    @Test
    void removesUnusedWrappersButKeepsReferencedOnes() throws Exception {
        var classes = testExecute(List.of("-Xelement-wrapper"), ".*", null);
        var names = simpleNames(classes);

        // Pure wrappers only used as flattenable shells (incl. nillable aliases) → removed.
        assertThat(names).doesNotContain(
            "TagList", "ProductList", "CodeList", "NoteList", "BatchList", "GroupList", "AliasList");

        // Still referenced after flatten.
        assertThat(names).contains(
            "SharedItems", // global element still uses it
            "GroupShell",  // list item type after one-level flatten
            "Product",
            "MixedBag",
            "Root");
    }

    @Test
    void keepWrapperClassOptionRetainsShells() throws Exception {
        var classes = testExecute(
            List.of("-Xelement-wrapper", "-remove-wrapper-class=false"),
            ROOT,
            (source, clazz) -> {
                assertListField(clazz, "tags", String.class, "tag");
                assertThat(clazz.getDeclaredField("tags").getAnnotation(XmlElementWrapper.class)).isNotNull();
            });

        assertThat(simpleNames(classes)).contains(
            "TagList", "ProductList", "CodeList", "NoteList", "BatchList", "GroupList", "SharedItems", "AliasList");
    }

    @Test
    void doesNotFlattenNonWrapperNestedBean() throws Exception {
        testExecute(List.of("-Xelement-wrapper"), ROOT, (source, clazz) -> {
            var mixed = clazz.getDeclaredField("mixed");
            assertThat(mixed.getType().getSimpleName()).isEqualTo("MixedBag");
            assertThat(mixed.getAnnotation(XmlElementWrapper.class)).isNull();
            assertThat(List.class.isAssignableFrom(mixed.getType())).isFalse();
        });

        testExecute(List.of("-Xelement-wrapper"), MIXED, (source, clazz) -> {
            assertThat(clazz.getDeclaredField("value").getType()).isEqualTo(String.class);
            assertThat(clazz.getDeclaredField("count").getType()).isEqualTo(int.class);
        });
    }

    /**
     * Dedupe before element-wrapper: nested {@code TagCarrier} merges into {@code TagCarrierType},
     * then flatten runs on the surviving host. Correct model-plugin order.
     */
    @Test
    void afterDedupeKeepsWrapperAnnotationOnMergeHost() throws Exception {
        var classes = testExecute(
            List.of("-Xdedupe-class", "-Xelement-wrapper"),
            ".*TagCarrier.*|" + ROOT,
            null
        );
        var byName = classes.stream().collect(Collectors.groupingBy(Class::getSimpleName));

        assertThat(byName.getOrDefault("TagCarrier", List.of())).isEmpty();
        assertThat(byName).containsKey("TagCarrierType");
        var host = byName.get("TagCarrierType").getFirst();
        assertThat(host.isMemberClass()).isFalse();
        assertListField(host, "tags", String.class, "tag");

        var root = byName.get("Root").getFirst();
        assertThat(root.getDeclaredField("tagCarrier").getType()).isEqualTo(host);
        assertThat(root.getDeclaredField("namedTagCarrier").getType()).isEqualTo(host);
    }

    /**
     * Element-wrapper before dedupe deletes a flatten owner → run must fail (no rebind guess).
     * Mojo wraps the XJC error; the detailed message is on the error receiver / logs.
     */
    @Test
    void failsWhenDedupeRunsAfterAndRemovesFlattenOwner() {
        assertThatThrownBy(() ->
            testExecute(List.of("-Xelement-wrapper", "-Xdedupe-class"), ".*", null)
        ).isInstanceOf(Exception.class);
    }

    @Test
    void doesNotRecursivelyUnwrapListOfWrapperShells() throws Exception {
        var classes = testExecute(List.of("-Xelement-wrapper"), ROOT, (source, clazz) -> {
            var groups = clazz.getDeclaredField("groups");
            assertThat(groups.getAnnotation(XmlElementWrapper.class)).isNotNull();
            assertThat(List.class.isAssignableFrom(groups.getType())).isTrue();

            var itemType = listItemType(groups);
            assertThat(itemType.getSimpleName()).isEqualTo("GroupShell");
            // Not flattened to List<String>.
            assertThat(itemType).isNotEqualTo(String.class);

            var xmlElement = groups.getAnnotation(XmlElement.class);
            assertThat(xmlElement).isNotNull();
            assertThat(xmlElement.name()).isEqualTo("group");
        });

        // GroupShell remains a pure collection shell bean (plugin does not re-walk items).
        assertThat(simpleNames(classes)).contains("GroupShell").doesNotContain("GroupList");
        testExecute(List.of("-Xelement-wrapper"), GROUP_SHELL, (source, clazz) -> {
            assertThat(List.class.isAssignableFrom(clazz.getDeclaredField("member").getType())).isTrue();
            assertThat(clazz.getDeclaredField("member").getAnnotation(XmlElementWrapper.class)).isNull();
        });
    }
}
