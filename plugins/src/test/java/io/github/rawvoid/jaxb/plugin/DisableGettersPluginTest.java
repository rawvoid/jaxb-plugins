package io.github.rawvoid.jaxb.plugin;

import io.github.rawvoid.jaxb.AbstractXJCMojoTestCase;
import org.junit.jupiter.api.Test;

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
            assertThat(hasGetter(clazz)).isTrue();
        });
    }

    @Test
    public void testPlugin() throws Exception {
        testExecute(List.of("-" + DisableGettersPlugin.OPTION_NAME), clazz -> {
            if (!clazz.getSimpleName().equals("Person")) {
                return;
            }
            assertThat(hasGetter(clazz)).isFalse();
        });
    }

    public boolean hasGetter(Class<?> clazz) {
        var methods = clazz.getDeclaredMethods();
        return Arrays.stream(methods)
            .anyMatch(method -> (method.getName().startsWith("get") || method.getName().startsWith("is")) && method.getParameterCount() == 0);
    }
}