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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Rawvoid
 */
class ClassNameDetectorTest {

    private static Stream<Arguments> providePositiveCases() {
        return Stream.of(
            Arguments.of("List<org.example.User>", "org.example.User"),
            Arguments.of("List<? extends org.example.User>", "org.example.User"),
            Arguments.of("Map<String, org.example.User>", "org.example.User"),
            Arguments.of("org.example.User user;", "org.example.User"),
            Arguments.of("new org.example.User()", "org.example.User"),
            Arguments.of("org.example.User[] arr;", "org.example.User"),
            Arguments.of("@org.example.User", "org.example.User"),
            Arguments.of("import org.example.User;", "org.example.User")
        );
    }

    private static Stream<Arguments> provideNegativeCases() {
        return Stream.of(
            Arguments.of("org.example.UserService", "org.example.User"),
            Arguments.of("org.example.User.ID", "org.example.User"),
            Arguments.of("@org.example.User.ID", "org.example.User"),
            Arguments.of("List<org.example.User.Inner>", "org.example.User"),
            Arguments.of("org.example.UserInner", "org.example.User"),
            Arguments.of("org.example.User.class", "org.example.User"),
            Arguments.of("superorg.example.User", "org.example.User"),
            Arguments.of("org.example.User_abc", "org.example.User"),
            Arguments.of("org.example.User$1", "org.example.User"),
            Arguments.of("org.example.User123", "org.example.User"),
            Arguments.of("my.org.example.User", "org.example.User")
        );
    }

    @ParameterizedTest
    @MethodSource("providePositiveCases")
    void testDetect_Positive(String text, String fullClassName) {
        assertTrue(ClassNameDetector.detect(text, fullClassName));
    }

    @ParameterizedTest
    @MethodSource("provideNegativeCases")
    void testDetect_Negative(String text, String fullClassName) {
        assertFalse(ClassNameDetector.detect(text, fullClassName));
    }

    @Test
    void testUnicodeEdgeCases() {
        var connector = "\u200D";
        assertFalse(ClassNameDetector.detect("org.example.User" + connector + "extra", "org.example.User"));

        assertTrue(ClassNameDetector.detect("List<λ.Function>", "λ.Function"));
        assertFalse(ClassNameDetector.detect("λ.FunctionExtra", "λ.Function"));
    }
}
