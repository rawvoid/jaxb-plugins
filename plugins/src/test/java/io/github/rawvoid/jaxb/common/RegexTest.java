/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
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
