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

package io.github.rawvoid.jaxb.plugin.validation;

/**
 * Bean Validation API package mode ({@code jakarta} or {@code javax}).
 *
 * @param constraintPrefix fully-qualified prefix ending with {@code .constraints.}
 * @param validFqcn        fully-qualified {@code @Valid} type name
 */
public record ValidationApi(String constraintPrefix, String validFqcn) {

    private static final String JAKARTA = "jakarta";
    private static final String JAVAX = "javax";

    public static ValidationApi parse(String raw) {
        var mode = raw == null || raw.isBlank() ? JAKARTA : raw.trim().toLowerCase();
        return switch (mode) {
            case JAKARTA -> jakarta();
            case JAVAX -> javax();
            default -> throw new IllegalArgumentException(
                "Invalid -api value '%s'; expected 'jakarta' or 'javax'".formatted(raw));
        };
    }

    public static ValidationApi jakarta() {
        return new ValidationApi("jakarta.validation.constraints.", "jakarta.validation.Valid");
    }

    public static ValidationApi javax() {
        return new ValidationApi("javax.validation.constraints.", "javax.validation.Valid");
    }

    public String constraint(String simpleName) {
        return constraintPrefix + simpleName;
    }
}
