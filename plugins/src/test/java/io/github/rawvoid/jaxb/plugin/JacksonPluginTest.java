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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XJC integration tests for {@link JacksonPlugin}.
 * Uses dedicated {@code jackson.xsd} only.
 */
class JacksonPluginTest extends AbstractXJCMojoTestCase {

    private static final String PKG = "com.github.rawvoid.xjc_plugins.jackson";
    private static final String PERSON = PKG + ".Person";
    private static final String ORDER = PKG + ".Order";
    private static final String PERSON_FILTER = "com\\.github\\.rawvoid\\.xjc_plugins\\.jackson\\.Person";
    private static final String ORDER_FILTER = "com\\.github\\.rawvoid\\.xjc_plugins\\.jackson\\.Order";
    private static final String JACKSON_PLUGIN_FQCN = "io.github.rawvoid.jaxb.plugin.JacksonPlugin";

    private final String optionCmd = optionCommand(JacksonPlugin.class);

    private static String optionCommand(Class<? extends OptionPlugin> pluginClass) {
        var option = pluginClass.getAnnotation(Option.class);
        return option.prefix() + option.name();
    }

    @BeforeEach
    void setSchema() {
        schemaIncludes = List.of("jackson.xsd");
    }

    @Test
    void testUsage() {
        assertThat(new JacksonPlugin().getUsage()).isNotNull();
    }

    /**
     * {@link JacksonPlugin} must load and construct when jackson-annotations is absent.
     * Regression for class-load hard-links that caused XJC {@code Failure to load a plugin}.
     */
    @Test
    void loadsWithoutJacksonAnnotations() throws Exception {
        var urls = classpathWithoutJacksonAnnotations();
        try (var cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            assertThatThrownBy(() -> Class.forName(
                "com.fasterxml.jackson.annotation.JsonInclude", true, cl))
                .isInstanceOf(ClassNotFoundException.class);

            var pluginClass = Class.forName(JACKSON_PLUGIN_FQCN, true, cl);
            var plugin = pluginClass.getDeclaredConstructor().newInstance();
            assertThat(pluginClass.getMethod("getOptionName").invoke(plugin)).isEqualTo("Xjackson");
        }
    }

    private static URL[] classpathWithoutJacksonAnnotations() throws Exception {
        var path = System.getProperty("java.class.path");
        var urls = new ArrayList<URL>();
        for (var entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            var name = Path.of(entry).getFileName().toString();
            if (name.startsWith("jackson-annotations")) {
                continue;
            }
            urls.add(Path.of(entry).toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    @Test
    void baselineWithoutPlugin() throws Exception {
        testExecute(List.of(), PERSON_FILTER, (source, clazz) -> {
            assertThat(source).doesNotContain("JsonInclude");
            assertThat(source).doesNotContain("JsonIgnoreProperties");
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNull();
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNull();
        });
    }

    @Test
    void defaultJacksonAnnotations() throws Exception {
        testExecute(List.of(optionCmd), PERSON_FILTER, (source, clazz) -> {
            assertThat(source).contains("JsonInclude");
            assertThat(source).contains("NON_NULL");
            assertThat(source).contains("JsonIgnoreProperties");
            assertThat(source).contains("ignoreUnknown");

            var include = clazz.getAnnotation(JsonInclude.class);
            assertThat(include).isNotNull();
            assertThat(include.value()).isEqualTo(JsonInclude.Include.NON_NULL);

            var ignore = clazz.getAnnotation(JsonIgnoreProperties.class);
            assertThat(ignore).isNotNull();
            assertThat(ignore.ignoreUnknown()).isTrue();
        });

        testExecute(List.of(optionCmd), ORDER_FILTER, (source, clazz) -> {
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNotNull();
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNotNull();
        });
    }

    @Test
    void disableBuiltIns() throws Exception {
        var args = List.of(optionCmd, "-include=none", "-ignore-unknown=false");
        testExecute(args, PERSON_FILTER, (source, clazz) -> {
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNull();
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNull();
            assertThat(source).doesNotContain("JsonInclude");
            assertThat(source).doesNotContain("JsonIgnoreProperties");
        });
    }

    @Test
    void customInclude() throws Exception {
        var args = List.of(optionCmd, "-include=NON_EMPTY", "-ignore-unknown=false");
        testExecute(args, PERSON_FILTER, (source, clazz) -> {
            var include = clazz.getAnnotation(JsonInclude.class);
            assertThat(include).isNotNull();
            assertThat(include.value()).isEqualTo(JsonInclude.Include.NON_EMPTY);
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNull();
        });
    }

    @Test
    void classNameFilter() throws Exception {
        var args = List.of(optionCmd, "-class-name=.*\\.Person");
        testExecute(args, PERSON_FILTER, (source, clazz) -> {
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNotNull();
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNotNull();
        });
        testExecute(args, ORDER_FILTER, (source, clazz) -> {
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNull();
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNull();
        });
    }

    @Test
    void skipsExistingJsonInclude() throws Exception {
        var args = List.of(
            "-Xannotate",
            "-add-to-class",
            "-anno=@com.fasterxml.jackson.annotation.JsonInclude(JsonInclude.Include.NON_EMPTY)",
            "-target=.*\\.Person",
            optionCmd
        );
        testExecute(args, PERSON_FILTER, (source, clazz) -> {
            var include = clazz.getAnnotation(JsonInclude.class);
            assertThat(include).isNotNull();
            // Built-in NON_NULL must not replace the annotate-plugin value.
            assertThat(include.value()).isEqualTo(JsonInclude.Include.NON_EMPTY);
            // ignoreUnknown still applied (was not pre-existing).
            assertThat(clazz.getAnnotation(JsonIgnoreProperties.class)).isNotNull();
        });
    }

    @Test
    void extraAnnoEscapeHatch() throws Exception {
        var args = List.of(
            optionCmd,
            "-anno=@com.fasterxml.jackson.annotation.JsonPropertyOrder({\"name\",\"email\"})"
        );
        testExecute(args, PERSON_FILTER, (source, clazz) -> {
            assertThat(clazz.getAnnotation(JsonInclude.class)).isNotNull();
            var order = clazz.getAnnotation(JsonPropertyOrder.class);
            assertThat(order).isNotNull();
            assertThat(order.value()).containsExactly("name", "email");
        });
    }
}
