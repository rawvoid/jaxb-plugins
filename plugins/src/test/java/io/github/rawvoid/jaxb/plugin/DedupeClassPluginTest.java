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
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class DedupeClassPluginTest extends AbstractXJCMojoTestCase {

    private static final String NS = "https://www.github.com/rawvoid/xjc-plugins/dedupe";

    private static Map<String, List<Class<?>>> bySimpleName(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.groupingBy(Class::getSimpleName));
    }

    private static Class<?> require(Map<String, List<Class<?>>> byName, String simpleName) {
        var list = byName.get(simpleName);
        assertThat(list).as(simpleName).isNotNull().isNotEmpty();
        return list.getFirst();
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static void invokeSet(Object target, String method, Class<?> argType, Object value) throws Exception {
        target.getClass().getMethod(method, argType).invoke(target, value);
    }

    /**
     * Classes are loaded by a disposable URLClassLoader; build the context from the Class
     * array so ObjectFactory resolves via the defining loader. Skip synthetic package-info /
     * debug helpers — JAXB rejects package-info when passed as a context class.
     */
    private static JAXBContext contextFor(List<Class<?>> classes) throws Exception {
        var jaxbClasses = classes.stream()
            .filter(c -> !c.isInterface())
            .filter(c -> !c.getSimpleName().startsWith("package-info"))
            .filter(c -> !c.getSimpleName().equals("JAXBDebug"))
            .toArray(Class[]::new);
        return JAXBContext.newInstance(jaxbClasses);
    }

    private static Object wrapRoot(List<Class<?>> classes, Class<?> valueType, Object value) throws Exception {
        var ofClass = require(bySimpleName(classes), "ObjectFactory");
        var of = ofClass.getDeclaredConstructor().newInstance();
        // ObjectFactory#createHolderA(HolderA) etc. — factory method name matches type simple name.
        return ofClass.getMethod("create" + valueType.getSimpleName(), valueType).invoke(of, value);
    }

    private static String marshal(JAXBContext ctx, Object root) throws Exception {
        var marshaller = ctx.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, false);
        var sw = new StringWriter();
        marshaller.marshal(root, sw);
        return sw.toString();
    }

    private static Object unmarshalValue(JAXBContext ctx, String xml) throws Exception {
        Unmarshaller unmarshaller = ctx.createUnmarshaller();
        var result = unmarshaller.unmarshal(new StringReader(xml));
        if (result instanceof JAXBElement<?> element) {
            return element.getValue();
        }
        return result;
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("dedupe-class.xsd");
    }

    @Test
    void nameKeyStripsTypeSuffix() {
        assertThat(DedupeClassPlugin.nameKey("AircraftCodeType")).isEqualTo("AircraftCode");
        assertThat(DedupeClassPlugin.nameKey("AircraftCode")).isEqualTo("AircraftCode");
        assertThat(DedupeClassPlugin.nameKey("Type")).isEqualTo("Type");
        assertThat(DedupeClassPlugin.nameKey("ListType")).isEqualTo("List");
    }

    @Test
    void withoutPluginKeepsSeparateNestedGroups() throws Exception {
        var classes = testExecute(List.of(), ".*", null);
        var byName = bySimpleName(classes);

        // Nested Group under holders + named GroupType
        assertThat(byName.get("Group")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(byName.get("Group")).allMatch(Class::isMemberClass);
        assertThat(byName).containsKey("GroupType");
    }

    @Test
    void exactMergeCollapsesIsomorphicGroupsIntoNamedHost() throws Exception {
        var args = List.of("-Xdedupe-class");
        var classes = testExecute(args, ".*Group.*|.*Holder.*|.*Party.*|.*AircraftCode.*", null);
        var byName = bySimpleName(classes);

        // Anonymous Groups merge into package GroupType (nameKey Group ≡ GroupType).
        assertThat(byName).containsKey("GroupType");
        assertThat(byName.get("GroupType")).anyMatch(c -> !c.isMemberClass());
        assertThat(byName).doesNotContainKey("Group");
        // Party is isomorphic but different nameKey — kept separate.
        assertThat(byName).containsKey("Party");
        assertThat(byName.get("Party")).hasSize(1);

        var holderA = require(byName, "HolderA");
        assertThat(holderA.getDeclaredField("group").getType().getSimpleName()).isEqualTo("GroupType");
        // Empty global element-class AircraftCode extends AircraftCodeType → always collapsed.
        assertThat(byName).containsKey("AircraftCodeType");
        // Nested HolderA.AircraftCode is a true field subset — needs -merge-subset (may remain).
        assertThat(holderA.getDeclaredField("aircraftCode").getType().isMemberClass()).isTrue();
        assertThat(byName.get("AircraftCode")).isNotNull().allMatch(Class::isMemberClass);
        // Root element binding transferred onto the named host.
        assertThat(require(byName, "AircraftCodeType").isAnnotationPresent(
            jakarta.xml.bind.annotation.XmlRootElement.class)).isTrue();
    }

    @Test
    void emptyElementExtensionMergesWithoutSubsetFlag() throws Exception {
        var classes = testExecute(List.of("-Xdedupe-class"), ".*AircraftCode.*", null);
        var byName = bySimpleName(classes);

        assertThat(byName).containsKey("AircraftCodeType");
        // Package-level empty element-class gone; nested subset class may remain.
        assertThat(byName.getOrDefault("AircraftCode", List.of())).allMatch(Class::isMemberClass);
        var host = require(byName, "AircraftCodeType");
        assertThat(host.getSuperclass()).isEqualTo(Object.class);
        var root = host.getAnnotation(jakarta.xml.bind.annotation.XmlRootElement.class);
        assertThat(root).isNotNull();
        assertThat(root.name()).isEqualTo("AircraftCode");
    }

    @Test
    void subsetMergesAnonymousAircraftCodeIntoNamedType() throws Exception {
        var args = List.of(
            "-Xdedupe-class",
            "-merge-subset"
        );
        var classes = testExecute(args, ".*AircraftCode.*|.*HolderA.*|.*HolderC.*", null);
        var byName = bySimpleName(classes);

        // Named AircraftCodeType remains; empty element-class and nested subset both gone.
        assertThat(byName).containsKey("AircraftCodeType");
        assertThat(byName.get("AircraftCodeType")).anyMatch(c -> !c.isMemberClass());
        assertThat(byName.getOrDefault("AircraftCode", List.of())).isEmpty();

        // HolderA.AircraftCode field type should be AircraftCodeType
        var holderA = require(byName, "HolderA");
        var field = holderA.getDeclaredField("aircraftCode");
        assertThat(field.getType().getSimpleName()).isEqualTo("AircraftCodeType");
    }

    @Test
    void sameNameDifferentStructureNotMerged() throws Exception {
        var args = List.of(
            "-Xdedupe-class",
            "-merge-subset"
        );
        var classes = testExecute(args, ".*Details.*", null);
        var byName = bySimpleName(classes);

        // HolderB.Details {note} vs HolderC.Details {amount} — two distinct types.
        assertThat(byName.get("Details")).hasSize(2);
    }

    @Test
    void exactMergeRoundTripPreservesElementNamesAndContent() throws Exception {
        var classes = testExecute(List.of("-Xdedupe-class"), ".*", null);
        var byName = bySimpleName(classes);
        var holderAClass = require(byName, "HolderA");
        var groupTypeClass = require(byName, "GroupType");
        // Nested subset type under HolderA (not the collapsed global empty-ext class).
        var nestedAircraftClass = holderAClass.getDeclaredField("aircraftCode").getType();
        assertThat(nestedAircraftClass.isMemberClass()).isTrue();

        var ctx = contextFor(classes);
        var holder = holderAClass.getDeclaredConstructor().newInstance();
        var group = groupTypeClass.getDeclaredConstructor().newInstance();
        invokeSet(group, "setCode", String.class, "G1");
        invokeSet(holder, "setGroup", groupTypeClass, group);

        var aircraft = nestedAircraftClass.getDeclaredConstructor().newInstance();
        invokeSet(aircraft, "setValue", String.class, "AB");
        invokeSet(holder, "setAircraftCode", nestedAircraftClass, aircraft);

        var xml = marshal(ctx, wrapRoot(classes, holderAClass, holder));
        assertThat(xml)
            .contains("<Group>")
            .contains("<code>G1</code>")
            .contains("<AircraftCode>AB</AircraftCode>")
            .doesNotContain("Context");

        var roundTripped = unmarshalValue(ctx, xml);
        assertThat(roundTripped.getClass()).isEqualTo(holderAClass);
        var group2 = invoke(roundTripped, "getGroup");
        assertThat(invoke(group2, "getCode")).isEqualTo("G1");
        assertThat(group2.getClass()).isEqualTo(groupTypeClass);
        var aircraft2 = invoke(roundTripped, "getAircraftCode");
        assertThat(invoke(aircraft2, "getValue")).isEqualTo("AB");
        // Nested anonymous type kept (no subset merge).
        assertThat(aircraft2.getClass()).isEqualTo(nestedAircraftClass);
    }

    @Test
    void emptyElementExtensionRootRoundTrip() throws Exception {
        var classes = testExecute(List.of("-Xdedupe-class"), ".*", null);
        var byName = bySimpleName(classes);
        var host = require(byName, "AircraftCodeType");
        assertThat(byName.getOrDefault("AircraftCode", List.of())).allMatch(Class::isMemberClass);

        var ctx = contextFor(classes);
        var code = host.getDeclaredConstructor().newInstance();
        invokeSet(code, "setValue", String.class, "318");
        invokeSet(code, "setContext", String.class, "IATA");

        // Host carries transferred @XmlRootElement(name=AircraftCode).
        var xml = marshal(ctx, code);
        assertThat(xml)
            .contains("AircraftCode")
            .contains(">318<")
            .contains("Context");

        var round = unmarshalValue(ctx, xml);
        assertThat(round.getClass()).isEqualTo(host);
        assertThat(invoke(round, "getValue")).isEqualTo("318");
        assertThat(invoke(round, "getContext")).isEqualTo("IATA");
    }

    @Test
    void subsetMergeRoundTripValueOnlyXml() throws Exception {
        var classes = testExecute(
            List.of("-Xdedupe-class", "-merge-subset"),
            ".*"
        );
        var byName = bySimpleName(classes);
        var holderAClass = require(byName, "HolderA");
        var groupTypeClass = require(byName, "GroupType");
        var aircraftCodeTypeClass = require(byName, "AircraftCodeType");

        var ctx = contextFor(classes);

        // Inbound XML shaped like the original anonymous AircraftCode (value only).
        var inbound = """
            <?xml version="1.0" encoding="UTF-8"?>
            <holderA xmlns="%s">
              <AircraftCode>AB</AircraftCode>
              <Group><code>G1</code></Group>
            </holderA>
            """.formatted(NS);

        var unmarshalled = unmarshalValue(ctx, inbound);
        var aircraft = invoke(unmarshalled, "getAircraftCode");
        assertThat(aircraft.getClass()).isEqualTo(aircraftCodeTypeClass);
        assertThat(invoke(aircraft, "getValue")).isEqualTo("AB");
        assertThat(invoke(aircraft, "getContext")).isNull();

        var xml = marshal(ctx, wrapRoot(classes, holderAClass, unmarshalled));
        assertThat(xml)
            .contains("<AircraftCode>AB</AircraftCode>")
            .contains("<Group>")
            .contains("<code>G1</code>")
            .doesNotContain("Context");

        // Programmatic construction via host type still marshals value-only when context null.
        var holder = holderAClass.getDeclaredConstructor().newInstance();
        var group = groupTypeClass.getDeclaredConstructor().newInstance();
        invokeSet(group, "setCode", String.class, "G2");
        invokeSet(holder, "setGroup", groupTypeClass, group);
        var code = aircraftCodeTypeClass.getDeclaredConstructor().newInstance();
        invokeSet(code, "setValue", String.class, "CD");
        invokeSet(holder, "setAircraftCode", aircraftCodeTypeClass, code);
        var out = marshal(ctx, wrapRoot(classes, holderAClass, holder));
        assertThat(out).contains("<AircraftCode>CD</AircraftCode>").doesNotContain("Context");
    }
}
