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

package io.github.rawvoid.jaxb.plugin.xjc;

import com.sun.codemodel.JAnnotationClassValue;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JMod;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationUtilsTest {

    private JDefinedClass type;

    @BeforeEach
    void setUp() throws Exception {
        var codeModel = new JCodeModel();
        type = codeModel._class("io.github.rawvoid.jaxb.test.Sample");
    }

    @Test
    void hasFindAndRemoveByFqcn() {
        type.annotate(XmlRootElement.class).param("name", "sample");

        assertThat(AnnotationUtils.hasAnnotation(type, XmlRootElement.class)).isTrue();
        assertThat(AnnotationUtils.findAnnotation(type, XmlRootElement.class.getName())).isPresent();
        assertThat(AnnotationUtils.findAnnotations(type, XmlRootElement.class)).hasSize(1);

        AnnotationUtils.removeAnnotations(type, XmlRootElement.class);

        assertThat(AnnotationUtils.hasAnnotation(type, XmlRootElement.class)).isFalse();
        assertThat(type.annotations()).isEmpty();
    }

    @Test
    void annotateIfAbsentIsIdempotent() {
        var first = AnnotationUtils.annotateIfAbsent(type, XmlRootElement.class);
        first.param("name", "once");
        var second = AnnotationUtils.annotateIfAbsent(type, XmlRootElement.class);

        assertThat(second).isSameAs(first);
        assertThat(AnnotationUtils.findAnnotations(type, XmlRootElement.class)).hasSize(1);
        assertThat(AnnotationUtils.readStringMember(first, "name")).isEqualTo("once");
    }

    @Test
    void annotateIfAbsentWithJClass() {
        var jClass = type.owner().ref(XmlJavaTypeAdapter.class);
        var use = AnnotationUtils.annotateIfAbsent(type, jClass);
        use.param("value", String.class);

        assertThat(AnnotationUtils.hasAnnotation(type, XmlJavaTypeAdapter.class.getName())).isTrue();
        assertThat(AnnotationUtils.annotateIfAbsent(type, jClass)).isSameAs(use);
    }

    @Test
    void applyXAnnotationFillsParamsAndReplacesNonRepeatable() {
        var first = AnnotationUtils.parseXAnnotation("@jakarta.xml.bind.annotation.XmlRootElement(name = \"old\")");
        AnnotationUtils.applyXAnnotation(type, first);
        assertThat(AnnotationUtils.readStringMember(
            AnnotationUtils.findAnnotation(type, XmlRootElement.class).orElseThrow(), "name"))
            .isEqualTo("old");

        var second = AnnotationUtils.parseXAnnotation("@jakarta.xml.bind.annotation.XmlRootElement(name = \"new\")");
        AnnotationUtils.applyXAnnotation(type, second);

        assertThat(AnnotationUtils.findAnnotations(type, XmlRootElement.class)).hasSize(1);
        assertThat(AnnotationUtils.readStringMember(
            AnnotationUtils.findAnnotation(type, XmlRootElement.class).orElseThrow(), "name"))
            .isEqualTo("new");
    }

    @Test
    void parseXAnnotationRejectsInvalidSource() {
        assertThatThrownBy(() -> AnnotationUtils.parseXAnnotation("@not.a.valid.Annotation("))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to parse annotation");
    }

    @Test
    void xAnnotationTextParserParsesSource() throws Exception {
        var parsed = AnnotationUtils.xAnnotationTextParser()
            .parse("anno", "@jakarta.xml.bind.annotation.XmlRootElement(name = \"via-parser\")");
        AnnotationUtils.applyXAnnotation(type, parsed);

        assertThat(AnnotationUtils.readStringMember(
            AnnotationUtils.findAnnotation(type, XmlRootElement.class).orElseThrow(), "name"))
            .isEqualTo("via-parser");
    }

    @Test
    void readStringMemberReturnsNullForMissingOrNonString() {
        var use = type.annotate(XmlJavaTypeAdapter.class).param("value", String.class);
        assertThat(AnnotationUtils.readStringMember(use, "value")).isNull();
        assertThat(AnnotationUtils.readStringMember(use, "missing")).isNull();
        assertThat(AnnotationUtils.readStringMember(null, "name")).isNull();
    }

    @Test
    void worksOnFields() {
        var field = type.field(JMod.PRIVATE, String.class, "value");
        assertThat(AnnotationUtils.hasAnnotation(field, XmlRootElement.class)).isFalse();

        AnnotationUtils.applyXAnnotation(field,
            AnnotationUtils.parseXAnnotation("@jakarta.xml.bind.annotation.XmlRootElement(name = \"field\")"));

        assertThat(AnnotationUtils.hasAnnotation(field, XmlRootElement.class)).isTrue();
        AnnotationUtils.removeAnnotations(field, XmlRootElement.class.getName());
        assertThat(AnnotationUtils.hasAnnotation(field, XmlRootElement.class)).isFalse();
    }

    @Test
    void applyXAnnotationAppendsRepeatableAnnotations() {
        var first = AnnotationUtils.parseXAnnotation(
            "@io.github.rawvoid.jaxb.plugin.xjc.RepeatableMarker(value = \"one\")");
        var second = AnnotationUtils.parseXAnnotation(
            "@io.github.rawvoid.jaxb.plugin.xjc.RepeatableMarker(value = \"two\")");

        AnnotationUtils.applyXAnnotation(type, first);
        AnnotationUtils.applyXAnnotation(type, second);

        var uses = AnnotationUtils.findAnnotations(type, RepeatableMarker.class);
        assertThat(uses).hasSize(2);
        assertThat(uses).extracting(use -> AnnotationUtils.readStringMember(use, "value"))
            .containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void referencesAndReplacesAnnotationClassValues() throws Exception {
        var codeModel = type.owner();
        var from = codeModel._class("io.github.rawvoid.jaxb.test.FromType");
        var to = codeModel._class("io.github.rawvoid.jaxb.test.ToType");

        type.annotate(TypeRefMarker.class).param("value", from);

        assertThat(AnnotationUtils.referencesType(type, from.fullName())).isTrue();
        assertThat(AnnotationUtils.referencesType(type, to.fullName())).isFalse();

        AnnotationUtils.replaceAnnotationReferences(type, from, to);

        assertThat(AnnotationUtils.referencesType(type, from.fullName())).isFalse();
        assertThat(AnnotationUtils.referencesType(type, to.fullName())).isTrue();

        var value = AnnotationUtils.findAnnotation(type, TypeRefMarker.class).orElseThrow()
            .getAnnotationMembers().get("value");
        assertThat(value).isInstanceOf(JAnnotationClassValue.class);
        assertThat(((JAnnotationClassValue) value).type().fullName()).isEqualTo(to.fullName());
    }
}
