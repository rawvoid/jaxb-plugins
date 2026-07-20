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

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XJC integration tests for {@link GeneratedAnnoPlugin}.
 * Uses dedicated {@code generated-anno.xsd} only.
 */
class GeneratedAnnoPluginTest extends AbstractXJCMojoTestCase {

    private static final String PACKAGE_NAME = "com.github.rawvoid.xjc_plugins.generated_anno";
    private static final String ROOT_CLASS = PACKAGE_NAME + ".Root";
    private static final String NESTED_CLASS = PACKAGE_NAME + ".Nested";
    private static final String OBJECT_FACTORY_CLASS = PACKAGE_NAME + ".ObjectFactory";

    private final String optionCmd = optionCommand(GeneratedAnnoPlugin.class);

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("generated-anno.xsd");
    }

    @Test
    void testUsage() {
        var plugin = new GeneratedAnnoPlugin();
        assertThat(plugin.getUsage()).isNotNull();
    }

    @Test
    void baselineNoPlugin() throws Exception {
        testExecute(List.of(), ROOT_CLASS, (source, clazz) -> {
            assertThat(source).doesNotContain("@Generated");
            assertThat(source).doesNotContain("jakarta.annotation.Generated");
        });

        testExecute(List.of(), NESTED_CLASS, (source, clazz) -> {
            assertThat(source).doesNotContain("@Generated");
            assertThat(source).doesNotContain("jakarta.annotation.Generated");
        });
    }

    @Test
    void defaultPluginOptions() throws Exception {
        testExecute(List.of(optionCmd), PACKAGE_NAME + "\\.(Root|Nested|ObjectFactory)", (source, clazz) -> {
            // Verify all generated classes (Root, Nested, ObjectFactory) contain the annotation
            assertThat(source).contains("Generated(");
            assertThat(source).contains("value = \"Xgenerated-anno\"");
            assertThat(source).contains("comments = \"https://github.com/rawvoid/jaxb-plugins\"");
            assertThat(source).doesNotContain("date =");
        });

        // Verify package-info.java also contains the annotation
        var packageInfoSource = getPackageInfoSource();
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        assertThat(packageInfoSource).contains("value = \"Xgenerated-anno\"");
        assertThat(packageInfoSource).contains("comments = \"https://github.com/rawvoid/jaxb-plugins\"");
        assertThat(packageInfoSource).doesNotContain("date =");
    }

    @Test
    void customPluginOptions() throws Exception {
        var args = List.of(
            optionCmd,
            "-value=CustomGenerator",
            "-comments=CustomComments"
        );
        testExecute(args, PACKAGE_NAME + "\\.(Root|Nested|ObjectFactory)", (source, clazz) -> {
            assertThat(source).contains("Generated(");
            assertThat(source).contains("value = \"CustomGenerator\"");
            assertThat(source).contains("comments = \"CustomComments\"");
            assertThat(source).doesNotContain("date =");
        });

        var packageInfoSource = getPackageInfoSource();
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        assertThat(packageInfoSource).contains("value = \"CustomGenerator\"");
        assertThat(packageInfoSource).contains("comments = \"CustomComments\"");
        assertThat(packageInfoSource).doesNotContain("date =");
    }

    @Test
    void timestampPluginOption() throws Exception {
        var args = List.of(
            optionCmd,
            "-timestamp=true"
        );
        testExecute(args, PACKAGE_NAME + "\\.(Root|Nested|ObjectFactory)", (source, clazz) -> {
            assertThat(source).contains("Generated(");
            assertThat(source).contains("value = \"Xgenerated-anno\"");
            assertThat(source).contains("comments = \"https://github.com/rawvoid/jaxb-plugins\"");
            assertThat(source).contains("date = \"");
            // Check that the date value matches ISO-8601 offset date time (e.g. 2026-07-20T21:37:29...)
            var datePattern = "date = \"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";
            assertThat(source).containsPattern(datePattern);
        });

        var packageInfoSource = getPackageInfoSource();
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        assertThat(packageInfoSource).contains("value = \"Xgenerated-anno\"");
        assertThat(packageInfoSource).contains("comments = \"https://github.com/rawvoid/jaxb-plugins\"");
        assertThat(packageInfoSource).contains("date = \"");
        var datePattern = "date = \"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";
        assertThat(packageInfoSource).containsPattern(datePattern);
    }

    private String getPackageInfoSource() throws IOException {
        var path = generatedDirectory.resolve(PACKAGE_NAME.replace('.', '/') + "/package-info.java");
        return Files.readString(path);
    }

    private static String optionCommand(Class<? extends AbstractPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }
}
