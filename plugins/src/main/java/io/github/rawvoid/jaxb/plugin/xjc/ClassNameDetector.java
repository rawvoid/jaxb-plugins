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

package io.github.rawvoid.jaxb.plugin.xjc;

import java.util.regex.Pattern;

/**
 * Detects if a fully qualified class name is present as a standalone token in the text.
 * This class handles class names containing non-ASCII characters (e.g., "λ.Function").
 *
 * @author Rawvoid
 */
public class ClassNameDetector {

    /**
     * Checks if the given text contains the specified fully qualified class name as a standalone token.
     *
     * @param text          the text to search in
     * @param fullClassName the fully qualified class name to search for
     * @return true if the class name is found as a standalone token, false otherwise
     */
    public static boolean detect(String text, String fullClassName) {
        if (text == null || fullClassName == null || fullClassName.isEmpty()) {
            return false;
        }

        var escaped = Pattern.quote(fullClassName);
        var regex = "(?<![\\p{javaJavaIdentifierPart}.])" + escaped + "(?![\\p{javaJavaIdentifierPart}.])";
        return Pattern.compile(regex).matcher(text).find();
    }
}
