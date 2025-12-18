package io.github.rawvoid.jaxb.plugin;

import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class DisableSettersPluginTest extends AbstractXJCMojoTestCase {

    Option option = DisableSettersPlugin.class.getAnnotation(Option.class);

    @Test
    void testDisablePlugin() throws Exception {
        var args = List.<String>of();
        testExecute(args, clazz -> {
            if (!clazz.getSimpleName().equals("Person")) {
                return;
            }
            assertThat(hasSetter(clazz)).isTrue();
        });
    }

    @Test
    void testPlugin() throws Exception {
        var optionCmd = option.prefix() + option.name();
        testExecute(List.of(optionCmd), clazz -> {
            if (!clazz.getSimpleName().equals("Person")) {
                return;
            }
            assertThat(hasSetter(clazz)).isFalse();
        });
    }

    boolean hasSetter(Class<?> clazz) {
        var methods = clazz.getDeclaredMethods();
        return Arrays.stream(methods)
            .anyMatch(method -> method.getName().startsWith("set") && method.getParameterCount() == 1);
    }

}
