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

package io.github.rawvoid.jaxb.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Field-name singularization aligned with Project Lombok's
 * {@code lombok.core.handlers.Singulars#autoSingularize(String)}.
 * <p>
 * Lombok ships that class only inside its internal SCL packaging, so it is not a
 * normal classpath type. The matching rules and {@code singulars.txt} table are the
 * same; we load Lombok's resource when present, otherwise our bundled copy.
 * </p>
 *
 * @see <a href="https://projectlombok.org/features/Builder">Lombok @Builder / @Singular</a>
 */
public final class LombokSingulars {

    private static final List<String> SINGULAR_STORE = loadStore();

    private LombokSingulars() {
    }

    /**
     * @return singular form, or {@code null} when Lombok would refuse auto-singularization
     *         (caller should pass an explicit {@code @Singular} value)
     */
    public static String autoSingularize(String in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        final int inLen = in.length();
        for (int i = 0; i < SINGULAR_STORE.size(); i += 2) {
            final var lastPart = SINGULAR_STORE.get(i);
            final boolean wholeWord = Character.isUpperCase(lastPart.charAt(0));
            final int endingOnly = lastPart.charAt(0) == '-' ? 1 : 0;
            final int len = lastPart.length();
            if (inLen < len) {
                continue;
            }
            if (!in.regionMatches(true, inLen - len + endingOnly, lastPart, endingOnly, len - endingOnly)) {
                continue;
            }
            if (wholeWord && inLen != len && !Character.isUpperCase(in.charAt(inLen - len))) {
                continue;
            }

            var replacement = SINGULAR_STORE.get(i + 1);
            if (replacement.equals("!")) {
                return null;
            }

            boolean capitalizeFirst = !replacement.isEmpty()
                && Character.isUpperCase(in.charAt(inLen - len + endingOnly));
            var pre = in.substring(0, inLen - len + endingOnly);
            var post = capitalizeFirst
                ? Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1)
                : replacement;
            return pre + post;
        }
        return null;
    }

    private static List<String> loadStore() {
        var store = new ArrayList<String>();
        try (var in = openSingularsTable();
             var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (var line = br.readLine(); line != null; line = br.readLine()) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                if (line.endsWith(" =")) {
                    store.add(line.substring(0, line.length() - 2));
                    store.add("");
                    continue;
                }
                var idx = line.indexOf(" = ");
                if (idx < 0) {
                    continue;
                }
                store.add(line.substring(0, idx));
                store.add(line.substring(idx + 3));
            }
        } catch (Exception e) {
            store.clear();
        }
        return List.copyOf(store);
    }

    /**
     * Prefer Lombok's own jar resource so a newer Lombok on the XJC classpath updates the table;
     * fall back to the copy shipped with this plugin.
     */
    private static InputStream openSingularsTable() {
        var cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            var fromLombok = cl.getResourceAsStream("lombok/core/handlers/singulars.txt");
            if (fromLombok != null) {
                return fromLombok;
            }
        }
        var fromPlugin = LombokSingulars.class.getResourceAsStream(
            "/io/github/rawvoid/jaxb/internal/singulars.txt");
        if (fromPlugin == null) {
            throw new IllegalStateException("singulars.txt not found on classpath");
        }
        return fromPlugin;
    }
}
