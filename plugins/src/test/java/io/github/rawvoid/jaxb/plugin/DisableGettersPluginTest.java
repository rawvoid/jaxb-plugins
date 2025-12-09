package io.github.rawvoid.jaxb.plugin;

import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
class DisableGettersPluginTest extends AbstractXJCMojoTestCase {

    @Test
    public void testDisablePlugin() throws Exception {
        testExecute(List.of(), clazz -> {
            if (!clazz.getSimpleName().equals("Person")) {
                return;
            }
            var methods = clazz.getDeclaredMethods();
            var getter = Arrays.stream(methods)
                .filter(this::isGetter)
                .findAny()
                .orElse(null);
            assertThat(getter).isNotNull();
        });
    }

    @Test
    public void testPlugin() throws Exception {
        testExecute(List.of("-" + DisableGettersPlugin.OPTION_NAME), clazz -> {
            if (!clazz.getSimpleName().equals("Person")) {
                return;
            }
            var methods = clazz.getDeclaredMethods();
            for (var method : methods) {
                assertThat(isGetter(method)).isFalse();
            }
        });
    }

    public boolean isGetter(Method method) {
        var name = method.getName();
        return (name.startsWith("get") || name.startsWith("is")) && method.getParameterCount() == 0;
    }
}