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
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class AbstractPluginTest {

    @Test
    void testUsage() {
        var plugin = new TestPlugin();
        var usage = plugin.getUsage();
        assertThat(usage).isNotNull();
        assertThat(usage.contains("-Xtest-plugin")).isTrue();
    }

    @Test
    void testUsageWithOptions() throws BadCommandLineException, IOException {
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
        assertThat(plugin.annotationList.getFirst().annotation.getAnnotationClass()).isEqualTo(jakarta.xml.bind.annotation.XmlElement.class);
        assertThat(plugin.annotationList.getFirst().regex.pattern()).isEqualTo(".*");
        assertThat(plugin.annotationList.get(1).annotation.getAnnotationClass()).isEqualTo(jakarta.xml.bind.annotation.XmlRootElement.class);
        assertThat(plugin.annotationList.get(1).regex).isNull();
        assertThat(plugin.magicString).isEqualTo("abcdef");
        assertThat(plugin.config.className).isEqualTo(AbstractPluginTest.class);
        assertThat(plugin.config.index).isEqualTo(0);
        assertThat(plugin.isEnabled).isTrue();
    }

    @Option(prefix = "-X", name = "test-plugin", description = "Just a test plugin")
    private static class TestPlugin extends AbstractPlugin {

        @Option(name = "int-list", description = "The list of integers")
        List<Integer> intList;

        @Option(name = "int-list2", description = "The list2 of integers")
        List<Integer> intList2;

        @Option(name = "annotation-list", description = "The list of annotations to be processed")
        List<AnnotationInfo> annotationList;

        @Option(name = "config", description = "The config of the plugin")
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

            @Option(name = "annotation", description = "The annotation to be processed")
            XAnnotation<?> annotation;

            @Option(name = "regex", description = "The regex pattern to match the annotation")
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
