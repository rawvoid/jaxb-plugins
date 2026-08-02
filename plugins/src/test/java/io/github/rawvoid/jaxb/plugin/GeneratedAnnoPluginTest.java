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
import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
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
    private static final String ADAPTER_PACKAGE = PACKAGE_NAME + ".adapter";
    /**
     * XJC places global {@code jaxb:javaType} adapters under the XML Schema namespace package.
     */
    private static final String XJC_ADAPTER_PACKAGE = "org.w3._2001.xmlschema";
    private static final String ROOT_CLASS = PACKAGE_NAME + ".Root";
    private static final String NESTED_CLASS = PACKAGE_NAME + ".Nested";
    private static final String STATUS_CLASS = PACKAGE_NAME + ".Status";
    private static final String XJC_ADAPTER_CLASS = XJC_ADAPTER_PACKAGE + ".Adapter1";
    private static final String XJC_ADAPTER_CLASS_REGEX =
        XJC_ADAPTER_PACKAGE.replace(".", "\\.") + "\\.Adapter1";
    /**
     * Schema beans, ObjectFactory, and XJC Adapter1 (no java-time).
     */
    private static final String SCHEMA_CLASSES =
        "(" + PACKAGE_NAME + "\\.(Root|Nested|Status|ObjectFactory)|" + XJC_ADAPTER_CLASS_REGEX + ")";

    private final String optionCmd = optionCommand(GeneratedAnnoPlugin.class);
    private final String javaTimeCmd = optionCommand(JavaTimePlugin.class);

    private static String optionCommand(Class<? extends OptionPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

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

        testExecute(List.of(), STATUS_CLASS, (source, clazz) -> {
            assertThat(source).doesNotContain("@Generated");
            assertThat(source).doesNotContain("jakarta.annotation.Generated");
        });

        testExecute(List.of(), XJC_ADAPTER_CLASS_REGEX, (source, clazz) -> {
            assertThat(source).doesNotContain("@Generated");
            assertThat(source).doesNotContain("jakarta.annotation.Generated");
        });
    }

    @Test
    void defaultPluginOptions() throws Exception {
        var expectedValue = "JAXB RI v" + Options.getBuildID();
        testExecute(List.of(optionCmd), SCHEMA_CLASSES, (source, clazz) -> {
            // Verify all generated classes contain the annotation with only the value attribute
            assertThat(source).contains("Generated(\"" + expectedValue + "\")");
            assertThat(source).doesNotContain("comments =");
            assertThat(source).doesNotContain("date =");
        });

        // Verify package-info.java also contains the annotation with only the value attribute
        var packageInfoSource = getPackageInfoSource(PACKAGE_NAME);
        assertThat(packageInfoSource).contains("Generated(\"" + expectedValue + "\")");
        assertThat(packageInfoSource).doesNotContain("comments =");
        assertThat(packageInfoSource).doesNotContain("date =");
    }

    @Test
    void customPluginOptions() throws Exception {
        var args = List.of(
            optionCmd,
            "-value=CustomGenerator",
            "-comments=CustomComments"
        );
        testExecute(args, SCHEMA_CLASSES, (source, clazz) -> {
            assertThat(source).contains("Generated(");
            assertThat(source).contains("value = \"CustomGenerator\"");
            assertThat(source).contains("comments = \"CustomComments\"");
            assertThat(source).doesNotContain("date =");
        });

        var packageInfoSource = getPackageInfoSource(PACKAGE_NAME);
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        assertThat(packageInfoSource).contains("value = \"CustomGenerator\"");
        assertThat(packageInfoSource).contains("comments = \"CustomComments\"");
        assertThat(packageInfoSource).doesNotContain("date =");
    }

    @Test
    void datePluginOption() throws Exception {
        var args = List.of(
            optionCmd,
            "-date=true"
        );
        var expectedValue = "JAXB RI v" + Options.getBuildID();
        testExecute(args, SCHEMA_CLASSES, (source, clazz) -> {
            assertThat(source).contains("Generated(");
            // Verify value and date attributes only, with date placed right after value
            var datePattern = "value = \"" + java.util.regex.Pattern.quote(expectedValue) + "\",\\s*date = \"\\d{4}-\\d{2}-\\d{2}\"";
            assertThat(source).containsPattern(datePattern);
            assertThat(source).doesNotContain("comments =");
        });

        var packageInfoSource = getPackageInfoSource(PACKAGE_NAME);
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        var datePattern = "value = \"" + java.util.regex.Pattern.quote(expectedValue) + "\",\\s*date = \"\\d{4}-\\d{2}-\\d{2}\"";
        assertThat(packageInfoSource).containsPattern(datePattern);
        assertThat(packageInfoSource).doesNotContain("comments =");
    }

    @Test
    void emptyStringOptions() throws Exception {
        var args = List.of(
            optionCmd,
            "-value=",
            "-comments="
        );
        testExecute(args, SCHEMA_CLASSES, (source, clazz) -> {
            assertThat(source).contains("Generated(");
            assertThat(source).contains("value = \"\"");
            assertThat(source).contains("comments = \"\"");
            assertThat(source).doesNotContain("date =");
        });

        var packageInfoSource = getPackageInfoSource(PACKAGE_NAME);
        assertThat(packageInfoSource).contains("@jakarta.annotation.Generated(");
        assertThat(packageInfoSource).contains("value = \"\"");
        assertThat(packageInfoSource).contains("comments = \"\"");
        assertThat(packageInfoSource).doesNotContain("date =");
    }

    /**
     * XJC {@code Adapter1} from global {@code jaxb:javaType} is generated under the
     * XML Schema package (outside outline package contexts) and must receive {@code @Generated}.
     */
    @Test
    void annotatesXjcGeneratedAdapter() throws Exception {
        var args = List.of(optionCmd, "-value=CustomGenerator");
        testExecute(args, XJC_ADAPTER_CLASS_REGEX, (source, clazz) -> {
            assertThat(clazz.getName()).isEqualTo(XJC_ADAPTER_CLASS);
            // Single-member form: @Generated("CustomGenerator")
            assertThat(source).contains("Generated(\"CustomGenerator\")");
            assertThat(source).contains("extends XmlAdapter");
        });

        var xjcAdapterPackageInfo = getPackageInfoSource(XJC_ADAPTER_PACKAGE);
        assertThat(xjcAdapterPackageInfo).contains("Generated(\"CustomGenerator\")");
    }

    /**
     * Adapters emitted by {@link JavaTimePlugin} into a sibling {@code *.adapter}
     * package are outside outline package contexts; they must still be annotated
     * when {@code -Xgenerated-anno} runs after {@code -Xjava-time}.
     */
    @Test
    void annotatesJavaTimeAdaptersInSeparatePackage() throws Exception {
        var args = List.of(
            javaTimeCmd,
            optionCmd,
            "-value=CustomGenerator"
        );
        testExecute(args, ADAPTER_PACKAGE.replace(".", "\\.") + "\\..*XmlAdapter.*", (source, clazz) -> {
            assertThat(clazz.getPackageName()).isEqualTo(ADAPTER_PACKAGE);
            assertThat(source).contains("Generated(\"CustomGenerator\")");
            assertThat(source).contains("extends XmlAdapter");
        });

        var adapterPackageInfo = getPackageInfoSource(ADAPTER_PACKAGE);
        assertThat(adapterPackageInfo).contains("Generated(\"CustomGenerator\")");
    }

    private String getPackageInfoSource(String packageName) throws IOException {
        var path = generatedDirectory.resolve(packageName.replace('.', '/') + "/package-info.java");
        return Files.readString(path);
    }
}
