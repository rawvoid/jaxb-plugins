package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
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

        assertThat(count).isEqualTo(args.length - 1);
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

        // 只传必须项，其他用 defaultValue 填充
        var args = List.of(
            "-Xdefault-required",
            "-config",
            "-class-name=io.github.rawvoid.jaxb.plugin.AbstractPluginTest"
        ).toArray(new String[0]);

        var count = plugin.parseArgument(new Options(), args, 0);
        assertThat(count).isEqualTo(args.length - 1);

        // defaultValue 应该生效
        assertThat(plugin.name).isEqualTo("bob");
        assertThat(plugin.count).isEqualTo(3);
        assertThat(plugin.tags).containsExactly("alpha");

        // required 复合对象 + 内部 required 字段应该生效
        assertThat(plugin.config).isNotNull();
        assertThat(plugin.config.className).isEqualTo(AbstractPluginTest.class);

        // Usage 应该包含 required/default 的标记（你升级后的格式）
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

        // 缺少 config 的内部 required 字段：-class-name
        var args = List.of(
            "-Xdefault-required",
            "-config"
        ).toArray(new String[0]);

        assertThatThrownBy(() -> plugin.parseArgument(new Options(), args, 0))
            .isInstanceOf(BadCommandLineException.class);
    }

    @Option(prefix = "-X", name = "default-required", description = "Test defaults + required")
    private static class DefaultAndRequiredPlugin extends AbstractPlugin {

        @Option(name = "name", description = "Name", defaultValue = "bob")
        String name;

        // 注意：用包装类型才能区分“没传”和“默认 0”
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
