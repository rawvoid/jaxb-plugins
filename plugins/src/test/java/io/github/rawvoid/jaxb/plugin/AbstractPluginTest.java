package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.junit.jupiter.api.Test;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Rawvoid
 */
class AbstractPluginTest {

    @Test
    void testUsage() {
        var plugin = new TestPlugin();
        var usage = plugin.getUsage();
        assertNotNull(usage);
        assertTrue(usage.contains("-Xtest-plugin"));
    }

    @Option(prefix = "-X", name = "test-plugin", description = "Just a test plugin")
    private static class TestPlugin extends AbstractPlugin {

        @Option(name = "test-field", description = "test list field")
        List<Integer> testList;

        @Option(name = "annotation", description = "The annotation to be processed")
        List<AnnotationInfo> annotationInfos;

        @Option(name = "config", description = "The config of the plugin")
        Config config;

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
            String className;

            @Option(name = "index", description = "The index of the option")
            int index;
        }
    }

}
