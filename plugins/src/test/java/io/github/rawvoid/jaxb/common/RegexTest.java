package io.github.rawvoid.jaxb.common;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Rawvoid
 */
public class RegexTest {

    @Test
    void testReplacePattern() {
        var pattern = Pattern.compile("(?i)(.+)Type$");
        var name = "PersonType";
        var name2 = "PersonType2";
        var name3 = "PersonTYPE";
        name = name.replaceAll(pattern.pattern(), "$1");
        assertThat(name).isEqualTo("Person");

        name2 = name2.replaceAll(pattern.pattern(), "$1");
        assertThat(name2).isEqualTo("PersonType2");

        name3 = name3.replaceAll(pattern.pattern(), "$1");
        assertThat(name3).isEqualTo("Person");
    }

}
