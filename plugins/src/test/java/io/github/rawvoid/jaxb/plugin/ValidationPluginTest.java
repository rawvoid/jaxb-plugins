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
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link ValidationPlugin}.
 * Uses dedicated {@code validation.xsd} only.
 */
class ValidationPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.validation";
    private static final String USER = PKG + "\\.UserType";
    private static final String ADDRESS = PKG + "\\.AddressType";
    private static final String ITEM = PKG + "\\.ItemType";
    private static final String PLAIN = PKG + "\\.PlainType";

    private final String optionCmd = optionCommand(ValidationPlugin.class);

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("validation.xsd");
    }

    @Test
    void testUsage() {
        assertThat(new ValidationPlugin().getUsage()).isNotNull();
    }

    @Test
    void baselineWithoutPlugin() throws Exception {
        testExecute(List.of(), USER, (source, clazz) -> {
            assertThat(source).doesNotContain("jakarta.validation");
            assertThat(source).doesNotContain("javax.validation");
            assertThat(field(clazz, "username").getAnnotation(NotNull.class)).isNull();
            assertThat(field(clazz, "username").getAnnotation(Size.class)).isNull();
        });
    }

    @Test
    void defaultJakartaFieldMappings() throws Exception {
        testExecute(List.of(optionCmd), USER, (source, clazz) -> {
            assertThat(source).contains("jakarta.validation.constraints.");

            // username: required + min/max length
            var username = field(clazz, "username");
            assertThat(username.getAnnotation(NotNull.class)).isNotNull();
            var usernameSize = username.getAnnotation(Size.class);
            assertThat(usernameSize).isNotNull();
            assertThat(usernameSize.min()).isEqualTo(3);
            assertThat(usernameSize.max()).isEqualTo(20);

            // email: required + pattern
            var email = field(clazz, "email");
            assertThat(email.getAnnotation(NotNull.class)).isNotNull();
            var emailPattern = email.getAnnotation(Pattern.class);
            assertThat(emailPattern).isNotNull();
            assertThat(emailPattern.regexp()).contains("@");

            // bio: optional maxLength only
            var bio = field(clazz, "bio");
            assertThat(bio.getAnnotation(NotNull.class)).isNull();
            var bioSize = bio.getAnnotation(Size.class);
            assertThat(bioSize).isNotNull();
            assertThat(bioSize.max()).isEqualTo(200);

            // age: min/max on integer restriction — no inherited @Digits
            var age = field(clazz, "age");
            assertThat(age.getType()).isEqualTo(int.class);
            assertThat(age.getAnnotation(NotNull.class)).isNull(); // primitive
            assertThat(age.getAnnotation(Min.class).value()).isEqualTo(18L);
            assertThat(age.getAnnotation(Max.class).value()).isEqualTo(120L);
            assertThat(age.getAnnotation(Digits.class)).isNull();

            // salary: exclusive min + digits
            var salary = field(clazz, "salary");
            var decMin = salary.getAnnotation(DecimalMin.class);
            assertThat(decMin).isNotNull();
            assertThat(decMin.value()).isEqualTo("0");
            assertThat(decMin.inclusive()).isFalse();
            var digits = salary.getAnnotation(Digits.class);
            assertThat(digits).isNotNull();
            assertThat(digits.integer()).isEqualTo(8);
            assertThat(digits.fraction()).isEqualTo(2);

            // roles: collection bounds
            var roles = field(clazz, "roles");
            assertThat(roles.getAnnotation(NotNull.class)).isNotNull();
            var rolesSize = roles.getAnnotation(Size.class);
            assertThat(rolesSize.min()).isEqualTo(1);
            assertThat(rolesSize.max()).isEqualTo(5);
            assertThat(roles.getAnnotation(Valid.class)).isNull();

            // address + contacts: cascade
            assertThat(field(clazz, "address").getAnnotation(NotNull.class)).isNotNull();
            assertThat(field(clazz, "address").getAnnotation(Valid.class)).isNotNull();
            assertThat(field(clazz, "contacts").getAnnotation(Valid.class)).isNotNull();
            assertThat(field(clazz, "contacts").getAnnotation(Size.class)).isNull();

            // nillable required
            assertThat(field(clazz, "note").getAnnotation(NotNull.class)).isNull();

            // unbounded min=1 collection
            var items = field(clazz, "items");
            assertThat(items.getAnnotation(NotNull.class)).isNotNull();
            var itemsSize = items.getAnnotation(Size.class);
            assertThat(itemsSize).isNotNull();
            assertThat(itemsSize.min()).isEqualTo(1);
            assertThat(itemsSize.max()).isEqualTo(Integer.MAX_VALUE); // default when max omitted
            assertThat(items.getAnnotation(Valid.class)).isNotNull();

            // collection multiplicity only (item length not applied)
            var codes = field(clazz, "codes");
            var codesSize = codes.getAnnotation(Size.class);
            assertThat(codesSize).isNotNull();
            assertThat(codesSize.max()).isEqualTo(3);
            assertThat(codesSize.min()).isEqualTo(0);
        });
    }

    @Test
    void addressRequiredFields() throws Exception {
        testExecute(List.of(optionCmd), ADDRESS, (source, clazz) -> {
            assertThat(field(clazz, "street").getAnnotation(NotNull.class)).isNotNull();
            assertThat(field(clazz, "city").getAnnotation(NotNull.class)).isNotNull();
        });
    }

    @Test
    void attributeAndValueProperty() throws Exception {
        testExecute(List.of(optionCmd), ITEM, (source, clazz) -> {
            // @XmlValue facets
            var value = field(clazz, "value");
            var valueSize = value.getAnnotation(Size.class);
            assertThat(valueSize).isNotNull();
            assertThat(valueSize.min()).isEqualTo(1);
            assertThat(valueSize.max()).isEqualTo(8);
            assertThat(value.getAnnotation(Pattern.class).regexp()).isEqualTo("[A-Z]+");

            // required attribute
            assertThat(field(clazz, "lang").getAnnotation(NotNull.class)).isNotNull();

            // optional attribute with numeric bounds (Integer — no NotNull)
            var priority = field(clazz, "priority");
            assertThat(priority.getAnnotation(NotNull.class)).isNull();
            assertThat(priority.getAnnotation(Min.class).value()).isEqualTo(1L);
            assertThat(priority.getAnnotation(Max.class).value()).isEqualTo(9L);
            assertThat(priority.getAnnotation(Digits.class)).isNull();
        });
    }

    @Test
    void javaxValidationMode() throws Exception {
        testExecute(List.of(optionCmd, "-api=javax"), USER, (source, clazz) -> {
            assertThat(source).contains("javax.validation.constraints.");
            assertThat(source).doesNotContain("jakarta.validation");
            assertThat(hasAnnotationByName(field(clazz, "username"), "javax.validation.constraints.NotNull")).isTrue();
            assertThat(hasAnnotationByName(field(clazz, "address"), "javax.validation.Valid")).isTrue();
        });
    }

    @Test
    void disableValidOption() throws Exception {
        testExecute(List.of(optionCmd, "-disable-valid=true"), USER, (source, clazz) -> {
            assertThat(field(clazz, "username").getAnnotation(NotNull.class)).isNotNull();
            assertThat(field(clazz, "address").getAnnotation(Valid.class)).isNull();
            assertThat(field(clazz, "contacts").getAnnotation(Valid.class)).isNull();
            assertThat(field(clazz, "items").getAnnotation(Valid.class)).isNull();
        });
    }

    @Test
    void classNameFilter() throws Exception {
        var args = List.of(optionCmd, "-class-name=.*UserType");
        testExecute(args, USER, (source, clazz) ->
            assertThat(field(clazz, "username").getAnnotation(NotNull.class)).isNotNull());
        testExecute(args, PLAIN, (source, clazz) ->
            assertThat(field(clazz, "label").getAnnotation(NotNull.class)).isNull());
        testExecute(args, ADDRESS, (source, clazz) ->
            assertThat(field(clazz, "street").getAnnotation(NotNull.class)).isNull());
    }

    @Test
    void fieldNameFilter() throws Exception {
        testExecute(List.of(optionCmd, "-field-name=username"), USER, (source, clazz) -> {
            assertThat(field(clazz, "username").getAnnotation(NotNull.class)).isNotNull();
            assertThat(field(clazz, "username").getAnnotation(Size.class)).isNotNull();
            assertThat(field(clazz, "email").getAnnotation(Pattern.class)).isNull();
            assertThat(field(clazz, "age").getAnnotation(Min.class)).isNull();
            assertThat(field(clazz, "address").getAnnotation(Valid.class)).isNull();
        });
    }

    private static Field field(Class<?> clazz, String name) throws NoSuchFieldException {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static boolean hasAnnotationByName(Field field, String fqcn) {
        return Arrays.stream(field.getAnnotations())
            .map(Annotation::annotationType)
            .map(Class::getName)
            .anyMatch(fqcn::equals);
    }
}
