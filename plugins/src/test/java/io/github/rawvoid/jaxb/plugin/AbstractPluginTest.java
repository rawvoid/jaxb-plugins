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

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.outline.Outline;
import org.junit.jupiter.api.Test;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractPluginTest {

    @Test
    void testUsage() {
        var plugin = new TestPlugin();
        var usage = plugin.getUsage();
        assertThat(usage).isNotNull();
        assertThat(usage.contains("-Xtest-plugin")).isTrue();
    }

    @Test
    void testRepeatedPluginOptionOnCommandLine() throws BadCommandLineException {
        var options = new Options();
        options.parseArguments(new String[]{"-Xremove-getter", "-Xremove-getter", "src/test/resources/schema/remove-getter.xsd"});
        assertThat(options.activePlugins).hasSize(2);
        assertThat(options.activePlugins.get(0)).isSameAs(options.activePlugins.get(1));
    }

    @Test
    void testRepeatedPluginExecutionInDriver() throws Exception {
        var runCount = new int[]{0};
        var testPlugin = new Plugin() {
            @Override
            public String getOptionName() {
                return "Xtest-dup";
            }

            @Override
            public String getUsage() {
                return "  -Xtest-dup : test dup";
            }

            @Override
            public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
                runCount[0]++;
                return true;
            }
        };

        var options = new Options();
        options.getAllPlugins().add(testPlugin);
        options.parseArguments(new String[]{"-Xtest-dup", "-Xtest-dup", "src/test/resources/schema/remove-getter.xsd"});
        assertThat(options.activePlugins).hasSize(2);
        assertThat(options.activePlugins.get(0)).isSameAs(testPlugin);
        assertThat(options.activePlugins.get(1)).isSameAs(testPlugin);

        for (Plugin plugin : options.activePlugins) {
            plugin.run(null, options, null);
        }
        assertThat(runCount[0]).isEqualTo(2);
    }

    @Test
    void testParseArguments() throws BadCommandLineException, IOException {
        var plugin = new TestPlugin();
        plugin.registerTextParser(XAnnotation.class, ((optionName, text) -> XAnnotationParser.INSTANCE.parse(text.toString())));
        plugin.registerTextParser(Pattern.class, (optionName, text) -> Pattern.compile(text.toString()));
        plugin.registerTextParser("int-list2", (optionName, text) -> Stream.of(text.toString().split(",")).map(Integer::parseInt).toList());
        plugin.registerTextParser("magic-string", (optionName, text) -> "abc" + text.toString());
        var args = List.of(
            "-Xtest-plugin",
            "-int-list=1",
            "-int-list= 2",
            "-int-list=3",
            "-annotation-list",
            "-annotation=@jakarta.xml.bind.annotation.XmlElement",
            "-regex=.*",
            "-annotation-list",
            "-annotation=@jakarta.xml.bind.annotation.XmlRootElement",
            "-int-list2=4,5,6",
            "-magic-string=def",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest",
            "-index=0",
            "-enabled"
        ).toArray(new String[0]);
        var count = plugin.parseArgument(new Options(), args, 0);

        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.isEnabled).isTrue();
        assertThat(plugin.intList).containsExactly(1, 2, 3);
        assertThat(plugin.intList2).containsExactly(4, 5, 6);
        assertThat(plugin.annotationList.size()).isEqualTo(2);
        assertThat(plugin.annotationList.getFirst().annotation.size()).isEqualTo(1);
        assertThat(plugin.annotationList.getFirst().annotation.getFirst().getAnnotationClass()).isEqualTo(jakarta.xml.bind.annotation.XmlElement.class);
        assertThat(plugin.annotationList.getFirst().regex.pattern()).isEqualTo(".*");
        assertThat(plugin.annotationList.get(1).annotation.size()).isEqualTo(1);
        assertThat(plugin.annotationList.get(1).annotation.getFirst().getAnnotationClass()).isEqualTo(jakarta.xml.bind.annotation.XmlRootElement.class);
        assertThat(plugin.annotationList.get(1).regex).isNull();
        assertThat(plugin.magicString).isEqualTo("abcdef");
        assertThat(plugin.config.className).isEqualTo(AbstractPluginTest.class);
        assertThat(plugin.config.index).isEqualTo(0);
        assertThat(plugin.isEnabled).isTrue();
    }

    @Test
    void testDefaultValueAppliedAndUsageContainsDefaultAndRequired() throws BadCommandLineException, IOException {
        var plugin = new DefaultAndRequiredPlugin();

        var args = List.of(
            "-Xdefault-required",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);

        assertThat(plugin.name).isEqualTo("bob");
        assertThat(plugin.count).isEqualTo(3);
        assertThat(plugin.tags).containsExactly("alpha");

        assertThat(plugin.config).isNotNull();
        assertThat(plugin.config.className).isEqualTo(AbstractPluginTest.class);

        var usage = plugin.getUsage();
        assertThat(usage).contains("-Xdefault-required");
        assertThat(usage).contains("[default=bob]");
        assertThat(usage).contains("[default=3]");
        assertThat(usage).contains("[default=alpha]");
        assertThat(usage).contains("[required]");
    }

    @Test
    void testRequiredMissingShouldThrow() {
        var plugin = new DefaultAndRequiredPlugin();

        var args = List.of(
            "-Xdefault-required",
            "-config"
        ).toArray(new String[0]);

        assertThatThrownBy(() -> plugin.parseArgument(new Options(), args, 0))
            .isInstanceOf(BadCommandLineException.class);
    }

    @Test
    void testNestedListGroupMarkerOnceStartsNextItemOnSameField() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name",
            "-token=ns1",
            "-name=pkg1",
            "-token=ns2",
            "-name=pkg2",
            "-token=ns3",
            "-name=pkg3"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(3);
        assertThat(plugin.packageNames.get(0).token).isEqualTo("ns1");
        assertThat(plugin.packageNames.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.packageNames.get(1).token).isEqualTo("ns2");
        assertThat(plugin.packageNames.get(1).name).isEqualTo("pkg2");
        assertThat(plugin.packageNames.get(2).token).isEqualTo("ns3");
        assertThat(plugin.packageNames.get(2).name).isEqualTo("pkg3");
    }

    @Test
    void testCompactMappingSingleAndRepeated() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name=ns1->pkg1",
            "-package-name=ns2->pkg2"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(2);
        assertThat(plugin.packageNames.get(0).token).isEqualTo("ns1");
        assertThat(plugin.packageNames.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.packageNames.get(1).token).isEqualTo("ns2");
        assertThat(plugin.packageNames.get(1).name).isEqualTo("pkg2");
    }

    @Test
    void testCompactAndStructuredMappingsAppend() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name=ns1->pkg1",
            "-package-name",
            "-token=ns2",
            "-name=pkg2",
            "-class-name=Person->Human"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(2);
        assertThat(plugin.packageNames.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.packageNames.get(1).name).isEqualTo("pkg2");
        assertThat(plugin.classNames).hasSize(1);
        assertThat(plugin.classNames.getFirst().token).isEqualTo("Person");
        assertThat(plugin.classNames.getFirst().name).isEqualTo("Human");
    }

    @Test
    void testCompactInvalidFormatThrows() {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name=missing-arrow"
        ).toArray(new String[0]);

        assertThatThrownBy(() -> plugin.parseArgument(new Options(), args, 0))
            .isInstanceOf(BadCommandLineException.class)
            .hasMessageContaining("Invalid compact value");
    }

    @Test
    void testCompactTrimsWhitespaceAroundSeparators() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name= ns1  ->  pkg1 ",
            "-package-name=ns2->pkg2"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(2);
        assertThat(plugin.packageNames.get(0).token).isEqualTo("ns1");
        assertThat(plugin.packageNames.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.packageNames.get(1).token).isEqualTo("ns2");
        assertThat(plugin.packageNames.get(1).name).isEqualTo("pkg2");
    }

    @Test
    void testCompactThreeFieldTrimsWhitespaceAndAllowsEmptyTrailing() throws Exception {
        var plugin = new ThreeFieldCompactPlugin();
        var args = List.of(
            "-Xthree-field",
            "-map=http://a.com  ->  com.a  :  pref",
            "-map=http://b.com -> com.b :",
            "-map=http://c.com->com.c"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.maps).hasSize(3);
        assertThat(plugin.maps.get(0).ns).isEqualTo("http://a.com");
        assertThat(plugin.maps.get(0).pkg).isEqualTo("com.a");
        assertThat(plugin.maps.get(0).prefix).isEqualTo("pref");
        assertThat(plugin.maps.get(1).ns).isEqualTo("http://b.com");
        assertThat(plugin.maps.get(1).pkg).isEqualTo("com.b");
        assertThat(plugin.maps.get(1).prefix).isEmpty();
        assertThat(plugin.maps.get(2).ns).isEqualTo("http://c.com");
        assertThat(plugin.maps.get(2).pkg).isEqualTo("com.c");
        assertThat(plugin.maps.get(2).prefix).isNull();
    }

    @Test
    void testCompactRejectsWhitespaceOnlyMiddlePlaceholder() {
        // Only the three-field template (no "{ns}->{package}" fallback).
        var plugin = new ThreeFieldOnlyCompactPlugin();
        var args = List.of(
            "-Xthree-field-only",
            "-map=http://a.com ->  : pref"
        ).toArray(new String[0]);

        // Empty package after strip must not match "{ns}->{package}:{prefix}".
        assertThatThrownBy(() -> plugin.parseArgument(new Options(), args, 0))
            .isInstanceOf(BadCommandLineException.class)
            .hasMessageContaining("Invalid compact value");
    }

    @Test
    void testCompactUsageShowsFormat() {
        var plugin = new MappingPlugin();
        var usage = plugin.getUsage();
        assertThat(usage).contains("-package-name=<{token}->{name}>");
        assertThat(usage).contains("[compact]");
    }

    @Test
    void testCompactMultiTemplateTriesInOrder() throws Exception {
        var plugin = new MultiFormatPlugin();
        var args = List.of(
            "-Xmulti-format",
            "-map=ns1->pkg1",
            "-map=/foo.*/->bar"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.maps).hasSize(2);
        assertThat(plugin.maps.get(0).token).isEqualTo("ns1");
        assertThat(plugin.maps.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.maps.get(0).regex).isNull();
        assertThat(plugin.maps.get(1).token).isNull();
        assertThat(plugin.maps.get(1).regex.pattern()).isEqualTo("foo.*");
        assertThat(plugin.maps.get(1).name).isEqualTo("bar");
    }

    @Test
    void testFieldLevelCompactOverridesType() throws Exception {
        var plugin = new FieldCompactPlugin();
        var args = List.of(
            "-Xfield-compact",
            "-default-map=a->b",
            "-legacy-map=c:d"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.defaultMap).hasSize(1);
        assertThat(plugin.defaultMap.getFirst().token).isEqualTo("a");
        assertThat(plugin.defaultMap.getFirst().name).isEqualTo("b");
        assertThat(plugin.legacyMap).hasSize(1);
        assertThat(plugin.legacyMap.getFirst().token).isEqualTo("c");
        assertThat(plugin.legacyMap.getFirst().name).isEqualTo("d");
    }

    @Test
    void testCompactPlaceholderBoundToListFieldGetsSingleton() throws Exception {
        var plugin = new ListPlaceholderCompactPlugin();
        var args = List.of(
            "-Xlist-compact",
            "-group=.*Request->com.example.CommonRequest",
            "-group=  .*Response  ->  com.example.CommonResponse "
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.groups).hasSize(2);

        var first = plugin.groups.getFirst();
        assertThat(first.classPatterns).hasSize(1);
        assertThat(first.classPatterns.getFirst().pattern()).isEqualTo(".*Request");
        assertThat(first.interfaceName).isEqualTo("com.example.CommonRequest");

        var second = plugin.groups.get(1);
        assertThat(second.classPatterns).hasSize(1);
        assertThat(second.classPatterns.getFirst().pattern()).isEqualTo(".*Response");
        assertThat(second.interfaceName).isEqualTo("com.example.CommonResponse");
    }

    @Test
    void testCompactListPlaceholderStillAllowsStructuredMultiClass() throws Exception {
        var plugin = new ListPlaceholderCompactPlugin();
        var args = List.of(
            "-Xlist-compact",
            "-group",
            "-class=.*CreateRequest",
            "-class=.*UpdateRequest",
            "-interface=com.example.CommonRequest"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.groups).hasSize(1);
        assertThat(plugin.groups.getFirst().classPatterns).hasSize(2);
        assertThat(plugin.groups.getFirst().classPatterns.get(0).pattern()).isEqualTo(".*CreateRequest");
        assertThat(plugin.groups.getFirst().classPatterns.get(1).pattern()).isEqualTo(".*UpdateRequest");
        assertThat(plugin.groups.getFirst().interfaceName).isEqualTo("com.example.CommonRequest");
    }

    @Option(prefix = "-X", name = "multi-format", description = "Multi-template compact")
    private static class MultiFormatPlugin extends AbstractPlugin {

        @Option(name = "map", description = "Mappings")
        List<Entry> maps;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
            return true;
        }

        // More specific regex template first.
        @Compact(formats = {"/{regex}/->{name}", "{token}->{name}"})
        private static class Entry {
            @Option(name = "token")
            String token;
            @Option(name = "regex")
            Pattern regex;
            @Option(name = "name", required = true)
            String name;
        }
    }

    @Option(prefix = "-X", name = "field-compact", description = "Field-level compact override")
    private static class FieldCompactPlugin extends AbstractPlugin {

        @Option(name = "default-map", description = "Uses type-level compact")
        List<Pair> defaultMap;

        @Option(name = "legacy-map", description = "Field-level compact override")
        @Compact(formats = {"{token}:{name}"})
        List<Pair> legacyMap;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
            return true;
        }

        @Compact(formats = {"{token}->{name}"})
        private static class Pair {
            @Option(name = "token")
            String token;
            @Option(name = "name", required = true)
            String name;
        }
    }

    @Test
    void testNestedListRepeatedGroupMarkerStillWorks() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name",
            "-token=ns1",
            "-name=pkg1",
            "-package-name",
            "-token=ns2",
            "-name=pkg2"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(2);
        assertThat(plugin.packageNames.get(0).name).isEqualTo("pkg1");
        assertThat(plugin.packageNames.get(1).name).isEqualTo("pkg2");
    }

    @Test
    void testNestedListOptionsMayInterleave() throws Exception {
        var plugin = new MappingPlugin();
        var args = List.of(
            "-Xmapping",
            "-package-name",
            "-token=ns1",
            "-name=pkg1",
            "-class-name",
            "-token=Person",
            "-name=Human",
            "-package-name",
            "-token=ns2",
            "-name=pkg2",
            "-class-name",
            "-token=Root",
            "-name=RootType"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.packageNames).hasSize(2);
        assertThat(plugin.packageNames.get(0).token).isEqualTo("ns1");
        assertThat(plugin.packageNames.get(1).token).isEqualTo("ns2");
        assertThat(plugin.classNames).hasSize(2);
        assertThat(plugin.classNames.get(0).token).isEqualTo("Person");
        assertThat(plugin.classNames.get(1).token).isEqualTo("Root");
    }

    @Test
    void testScalarListOptionsMayInterleave() throws Exception {
        var plugin = new TestPlugin();
        plugin.registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
        plugin.registerTextParser(Pattern.class, (optionName, text) -> Pattern.compile(text.toString()));

        var args = List.of(
            "-Xtest-plugin",
            "-int-list=1",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest",
            "-index=1",
            "-int-list=2",
            "-int-list=3"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.intList).containsExactly(1, 2, 3);
        assertThat(plugin.config.index).isEqualTo(1);
    }

    @Test
    void testWholeCollectionTextParserAppendsWhenInterleaved() throws Exception {
        var plugin = new TestPlugin();
        plugin.registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
        plugin.registerTextParser(Pattern.class, (optionName, text) -> Pattern.compile(text.toString()));
        plugin.registerTextParser("int-list2", (optionName, text) ->
            Stream.of(text.toString().split(",")).map(Integer::parseInt).toList());

        var args = List.of(
            "-Xtest-plugin",
            "-int-list2=1,2",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest",
            "-index=0",
            "-int-list2=3,4",
            "-int-list=1"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.intList2).containsExactly(1, 2, 3, 4);
    }

    @Test
    void testAnnotationListGroupOnceAndSameFieldStartsNextItem() throws Exception {
        var plugin = new TestPlugin();
        plugin.registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
        plugin.registerTextParser(Pattern.class, (optionName, text) -> Pattern.compile(text.toString()));

        var args = List.of(
            "-Xtest-plugin",
            "-annotation-list",
            "-annotation=@jakarta.xml.bind.annotation.XmlElement",
            "-regex=a.*",
            "-annotation=@jakarta.xml.bind.annotation.XmlRootElement",
            "-regex=b.*",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest",
            "-int-list=1"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length);
        assertThat(plugin.annotationList).hasSize(2);
        assertThat(plugin.annotationList.get(0).annotation.getFirst().getAnnotationClass())
            .isEqualTo(jakarta.xml.bind.annotation.XmlElement.class);
        assertThat(plugin.annotationList.get(0).regex.pattern()).isEqualTo("a.*");
        assertThat(plugin.annotationList.get(1).annotation.getFirst().getAnnotationClass())
            .isEqualTo(jakarta.xml.bind.annotation.XmlRootElement.class);
        assertThat(plugin.annotationList.get(1).regex.pattern()).isEqualTo("b.*");
    }

    @Option(prefix = "-X", name = "mapping", description = "Nested list mapping options")
    private static class MappingPlugin extends AbstractPlugin {

        @Option(name = "package-name", description = "Package name mappings")
        List<NameMappingConfig> packageNames;

        @Option(name = "class-name", description = "Class name mappings")
        List<NameMappingConfig> classNames;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
            return true;
        }

        @Compact(formats = {"{token}->{name}"})
        private static class NameMappingConfig {

            @Option(name = "token", description = "Source token")
            String token;

            @Option(name = "name", required = true, description = "Target name")
            String name;
        }
    }

    @Option(prefix = "-X", name = "list-compact", description = "Compact with List placeholder")
    private static class ListPlaceholderCompactPlugin extends AbstractPlugin {

        @Option(name = "group", required = true, description = "Groups")
        List<GroupConfig> groups;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
            return true;
        }

        @Compact(formats = {"{class}->{interface}"})
        private static class GroupConfig {
            @Option(name = "class", required = true)
            List<Pattern> classPatterns;
            @Option(name = "interface", required = true)
            String interfaceName;
        }
    }

    @Option(prefix = "-X", name = "three-field", description = "Three-field compact like package-mapping")
    private static class ThreeFieldCompactPlugin extends AbstractPlugin {

        @Option(name = "map", description = "Namespace package mappings")
        List<NsPkgPrefix> maps;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
            return true;
        }

        @Compact(formats = {"{ns}->{package}:{prefix}", "{ns}->{package}"})
        private static class NsPkgPrefix {
            @Option(name = "ns", required = true)
            String ns;
            @Option(name = "package", required = true)
            String pkg;
            @Option(name = "prefix")
            String prefix;
        }
    }

    @Option(prefix = "-X", name = "three-field-only", description = "Three-field compact without package-only fallback")
    private static class ThreeFieldOnlyCompactPlugin extends AbstractPlugin {

        @Option(name = "map", description = "Namespace package mappings")
        List<Entry> maps;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
            return true;
        }

        @Compact(formats = {"{ns}->{package}:{prefix}"})
        private static class Entry {
            @Option(name = "ns", required = true)
            String ns;
            @Option(name = "package", required = true)
            String pkg;
            @Option(name = "prefix")
            String prefix;
        }
    }

    @Option(prefix = "-X", name = "default-required", description = "Test defaults + required")
    private static class DefaultAndRequiredPlugin extends AbstractPlugin {

        @Option(name = "name", description = "Name", defaultValue = "bob")
        String name;

        @Option(name = "count", description = "Count", defaultValue = "3")
        Integer count;

        @Option(name = "tags", description = "Tags", defaultValue = "alpha")
        List<String> tags;

        @Option(name = "config", description = "Config section", required = true)
        Config config;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
            return true;
        }

        private static class Config {

            @Option(name = "class-name", description = "Class name", required = true)
            Class<?> className;
        }
    }

    @Option(prefix = "-X", name = "test-plugin", description = """
        Just a test plugin
        JAXB plugin to test the abstract plugin
        """)
    private static class TestPlugin extends AbstractPlugin {

        @Option(name = "int-list", description = "The list of integers", required = true)
        List<Integer> intList;

        @Option(name = "int-list2", description = "The list2 of integers")
        List<Integer> intList2;

        @Option(name = "annotation-list", description = "The list of annotations to be processed")
        List<AnnotationInfo> annotationList;

        @Option(name = "config", description = "The config of the plugin", required = true)
        Config config;

        @Option(name = "magic-string", description = "The magic string")
        String magicString;

        @Option(name = "enabled", description = "enable the plugin")
        boolean isEnabled;

        @Override
        public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
            return true;
        }

        private static class AnnotationInfo {

            @Option(name = "annotation", placeholder = "annotation", required = true, description = "The annotation to be processed")
            ArrayList<XAnnotation<?>> annotation;

            @Option(name = "regex", placeholder = "regex", description = "The regex pattern to match the annotation")
            Pattern regex;
        }

        private static class Config {

            @Option(name = "class-name", description = "The class name of the option")
            Class<?> className;

            @Option(name = "index", description = "The index of the option")
            int index;
        }
    }

}
