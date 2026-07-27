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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XJC integration tests for {@link CommonInterfacePlugin}.
 * Uses {@code common-interface.xsd}.
 */
class CommonInterfacePluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.common_interface";
    private static final String CREATE = PKG + ".CreateRequestType";
    private static final String UPDATE = PKG + ".UpdateRequestType";
    private static final String DELETE = PKG + ".DeleteRequestType";
    private static final String CREATE_RESPONSE = PKG + ".CreateResponseType";
    private static final String UNRELATED = PKG + ".UnrelatedType";
    private static final String IFACE = PKG + ".CommonRequest";
    private static final String IFACE_RESPONSE = PKG + ".CommonResponse";
    private static final String CREATE_FILTER = escapeDots(CREATE);
    private static final String UPDATE_FILTER = escapeDots(UPDATE);
    private static final String DELETE_FILTER = escapeDots(DELETE);
    private static final String CREATE_RESPONSE_FILTER = escapeDots(CREATE_RESPONSE);
    private static final String IFACE_FILTER = escapeDots(IFACE);
    private static final String IFACE_RESPONSE_FILTER = escapeDots(IFACE_RESPONSE);

    private final String optionCmd = optionCommand(CommonInterfacePlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    private static String escapeDots(String fqcn) {
        return fqcn.replace(".", "\\.");
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("common-interface.xsd");
    }

    @Test
    void testUsage() {
        var usage = new CommonInterfacePlugin().getUsage();
        assertThat(usage).contains("-group");
        assertThat(usage).contains("{class}->{interface}");
        assertThat(usage).contains("-class");
        assertThat(usage).contains("-interface");
        assertThat(usage).contains("-fields");
    }

    @Test
    void generatesInterfaceWithGettersAndSettersForScalarCommonProps() throws Exception {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*RequestType",
            "-interface=" + IFACE
        );
        // All *RequestType share only "id"
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            assertThat(methodNames(clazz)).containsExactlyInAnyOrder("getId", "setId");
            assertThat(clazz.getMethod("getId").getReturnType()).isEqualTo(String.class);
            assertThat(clazz.getMethod("setId", String.class).getReturnType()).isEqualTo(void.class);
        });
        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements CommonRequest");
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
        });
        testExecute(args, DELETE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
        });
    }

    @Test
    void collectionCommonPropertyHasGetterOnly() throws Exception {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE
        );
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            var names = methodNames(clazz);
            assertThat(names).contains("getId", "setId", "getName", "setName", "getTags");
            assertThat(names).doesNotContain("setTags");
            assertThat(clazz.getMethod("getTags").getReturnType()).isEqualTo(List.class);
        });
        testExecute(args, CREATE_FILTER, (source, clazz) ->
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE)));
        testExecute(args, UPDATE_FILTER, (source, clazz) ->
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE)));
    }

    @Test
    void fieldsOptionRestrictsInterfaceMethods() throws Exception {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE,
            "-fields=id"
        );
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(methodNames(clazz)).containsExactlyInAnyOrder("getId", "setId");
            assertThat(methodNames(clazz)).doesNotContain("getName", "getTags");
        });
    }

    @Test
    void compactGroupFormGeneratesInterfaceAndImplements() throws Exception {
        var args = List.of(
            optionCmd,
            "-group=.*RequestType->" + IFACE
        );
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            assertThat(methodNames(clazz)).containsExactlyInAnyOrder("getId", "setId");
        });
        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(source).contains("implements CommonRequest");
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
        });
        testExecute(args, DELETE_FILTER, (source, clazz) ->
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE)));
    }

    @Test
    void compactMultipleGroupsGenerateIndependentInterfaces() throws Exception {
        var args = List.of(
            optionCmd,
            "-group=.*CreateRequestType->" + IFACE,
            "-group=.*ResponseType->" + IFACE_RESPONSE
        );
        var classes = testExecute(args);
        var names = classes.stream().map(Class::getName).toList();
        assertThat(names).contains(IFACE, IFACE_RESPONSE, CREATE, CREATE_RESPONSE);

        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
            assertThat(clazz.getInterfaces()).noneMatch(i -> i.getName().equals(IFACE_RESPONSE));
        });
        testExecute(args, CREATE_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE_RESPONSE));
            assertThat(clazz.getInterfaces()).noneMatch(i -> i.getName().equals(IFACE));
        });
    }

    @Test
    void multipleGroupsGenerateIndependentInterfaces() throws Exception {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE,
            "-fields=id",
            "-group",
            "-class=.*ResponseType",
            "-interface=" + IFACE_RESPONSE
        );
        var classes = testExecute(args);
        var names = classes.stream().map(Class::getName).toList();
        assertThat(names).contains(IFACE, IFACE_RESPONSE, CREATE, CREATE_RESPONSE);

        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            assertThat(methodNames(clazz)).containsExactlyInAnyOrder("getId", "setId");
        });
        testExecute(args, IFACE_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            assertThat(methodNames(clazz)).containsExactlyInAnyOrder(
                "getCode", "setCode", "getMessage", "setMessage");
        });
        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
            assertThat(clazz.getInterfaces()).noneMatch(i -> i.getName().equals(IFACE_RESPONSE));
        });
        testExecute(args, CREATE_RESPONSE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE_RESPONSE));
            assertThat(clazz.getInterfaces()).noneMatch(i -> i.getName().equals(IFACE));
        });
    }

    @Test
    void typeMismatchExcludesPropertyFromInterface() throws Exception {
        // CreateRequestType.id is String; IdMismatchType.id is int/Integer — not common.
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*IdMismatchType",
            "-interface=" + IFACE
        );
        // Empty intersection → warning, no interface generated; Create still compiles without implements.
        var classes = testExecute(args);
        assertThat(classes.stream().map(Class::getName)).doesNotContain(IFACE);
        var create = classes.stream().filter(c -> c.getName().equals(CREATE)).findFirst().orElseThrow();
        assertThat(create.getInterfaces()).noneMatch(i -> i.getName().equals(IFACE));
    }

    @Test
    void emptyIntersectionDoesNotGenerateInterface() throws Exception {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UnrelatedType",
            "-interface=" + IFACE
        );
        var classes = testExecute(args);
        assertThat(classes.stream().map(Class::getName)).doesNotContain(IFACE);
        assertThat(classes.stream().map(Class::getName)).contains(CREATE, UNRELATED);
    }

    @Test
    void noMatchingClassIsError() {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*DoesNotExist",
            "-interface=" + IFACE
        );
        assertThatThrownBy(() -> testExecute(args))
            .isInstanceOf(Exception.class);
    }

    @Test
    void duplicateInterfaceAcrossGroupsIsError() {
        var args = List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE,
            "-group",
            "-class=.*ResponseType",
            "-interface=" + IFACE
        );
        assertThatThrownBy(() -> testExecute(args))
            .isInstanceOf(Exception.class);
    }

    @Test
    void lombokAfterCommonInterfaceStillImplementsAndCompiles() throws Exception {
        assertLombokCombo(List.of(
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE,
            "-Xlombok"
        ));
    }

    @Test
    void lombokBeforeCommonInterfaceStillImplementsAndCompiles() throws Exception {
        assertLombokCombo(List.of(
            "-Xlombok",
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE
        ));
    }

    /**
     * {@link JavaTimePlugin} rewrites field types before this plugin runs. Under {@code @Data}
     * (no XJC getters to inspect), the interface must use the rewritten type — not
     * {@code FieldOutline#getRawType()} ({@code XMLGregorianCalendar}).
     */
    @Test
    void javaTimeBeforeCommonInterfaceUsesRewrittenFieldType() throws Exception {
        var args = List.of(
            "-Xlombok",
            "-Xjava-time",
            optionCmd,
            "-group",
            "-class=.*CreateRequestType",
            "-class=.*UpdateRequestType",
            "-interface=" + IFACE
        );
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            assertThat(clazz.getMethod("getTimeStamp").getReturnType())
                .isEqualTo(java.time.OffsetDateTime.class);
            assertThat(clazz.getMethod("setTimeStamp", java.time.OffsetDateTime.class)).isNotNull();
            assertThat(source).doesNotContain("XMLGregorianCalendar");
        });
        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
            assertThat(clazz.getDeclaredField("timeStamp").getType())
                .isEqualTo(java.time.OffsetDateTime.class);
        });
    }

    /**
     * With {@code @Data}, shared list properties get setters on the generated interface.
     */
    private void assertLombokCombo(List<String> args) throws Exception {
        testExecute(args, IFACE_FILTER, (source, clazz) -> {
            assertThat(clazz.isInterface()).isTrue();
            var names = methodNames(clazz);
            assertThat(names).contains("getId", "setId", "getName", "setName", "getTags", "setTags");
        });
        testExecute(args, CREATE_FILTER, (source, clazz) -> {
            assertThat(source).contains("@Data");
            assertThat(source).contains("implements CommonRequest");
            assertThat(clazz.getInterfaces()).anyMatch(i -> i.getName().equals(IFACE));
            // APT restores accessors required by the interface.
            assertThat(clazz.getMethod("getId")).isNotNull();
            assertThat(clazz.getMethod("setId", String.class)).isNotNull();
            assertThat(clazz.getMethod("getTags")).isNotNull();
            assertThat(clazz.getMethod("setTags", List.class)).isNotNull();
        });
    }

    private static List<String> methodNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .map(Method::getName)
            .toList();
    }
}
