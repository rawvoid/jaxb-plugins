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
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link JSR310Plugin}.
 * Uses dedicated {@code jsr310.xsd} only.
 */
public class JSR310PluginTest extends AbstractXJCMojoTestCase {

    private static final String DATE_TIME_TYPES =
        "com\\.github\\.rawvoid\\.xjc_plugins\\.jsr310\\.DateTimeTypes";

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("jsr310.xsd");
    }

    @Test
    void testJSR310PluginDefault() throws Exception {
        var args = List.of("-Xjsr310");
        testExecute(args, DATE_TIME_TYPES, (source, clazz) -> {
            var dateTimeField = clazz.getDeclaredField("dateTime");
            assertThat(dateTimeField.getType()).isEqualTo(List.class);
            assertThat(((ParameterizedType) dateTimeField.getGenericType()).getActualTypeArguments()[0])
                .isEqualTo(LocalDateTime.class);

            assertThat(clazz.getDeclaredField("date").getType()).isEqualTo(LocalDate.class);
            assertThat(clazz.getDeclaredField("time").getType()).isEqualTo(LocalTime.class);
            assertThat(clazz.getDeclaredField("gYearMonth").getType()).isEqualTo(YearMonth.class);
            assertThat(clazz.getDeclaredField("gYear").getType()).isEqualTo(Year.class);
            assertThat(clazz.getDeclaredField("gMonthDay").getType()).isEqualTo(MonthDay.class);
            assertThat(clazz.getDeclaredField("gDay").getType()).isEqualTo(Integer.class);
            assertThat(clazz.getDeclaredField("gMonth").getType()).isEqualTo(Month.class);
            assertThat(clazz.getDeclaredField("duration").getType()).isEqualTo(Duration.class);
            assertThat(clazz.getDeclaredField("created").getType()).isEqualTo(LocalDate.class);
        });
    }

    @Test
    void testJSR310PluginCustomConfig() throws Exception {
        var args = List.of(
            "-Xjsr310",
            "-mapping",
            "-xsd-type=date",
            "-target-class=java.time.OffsetDateTime",
            "-mapping",
            "-regex=.*time",
            "-target-class=java.time.ZonedDateTime"
        );
        testExecute(args, DATE_TIME_TYPES, (source, clazz) -> {
            assertThat(clazz.getDeclaredField("date").getType()).isEqualTo(OffsetDateTime.class);
            assertThat(clazz.getDeclaredField("created").getType()).isEqualTo(OffsetDateTime.class);
            assertThat(clazz.getDeclaredField("time").getType()).isEqualTo(ZonedDateTime.class);
        });
    }

    @Test
    void testJSR310PluginCustomPattern() throws Exception {
        var args = List.of(
            "-Xjsr310",
            "-mapping",
            "-regex=.*\\.date",
            "-pattern=yyyy/MM/dd"
        );
        testExecute(args, DATE_TIME_TYPES, (source, clazz) -> {
            var field = clazz.getDeclaredField("date");
            var annotation = field.getAnnotation(XmlJavaTypeAdapter.class);
            assertThat(annotation).isNotNull();

            var adapterClass = annotation.value();
            var adapterSource = getJavaSource(adapterClass);
            assertThat(adapterSource).contains("\"yyyy/MM/dd\"");
        });
    }

    @Test
    void testAdapterPackageOption() throws Exception {
        var args = List.of(
            "-Xjsr310",
            "-adapter-package=io.github.rawvoid.jaxb.test.adapters"
        );
        testExecute(args, DATE_TIME_TYPES, (source, clazz) -> {
            var annotation = clazz.getDeclaredField("date").getAnnotation(XmlJavaTypeAdapter.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value().getPackageName())
                .isEqualTo("io.github.rawvoid.jaxb.test.adapters");
        });
    }
}
