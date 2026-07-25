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

import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSSimpleType;

import javax.xml.XMLConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declared XSD facets collected from a user-defined simple type restriction chain.
 * <p>
 * Walks {@code restriction} bases and records each facet name the first time it appears
 * (child restrictions win). Stops at built-in XML Schema types so facets such as
 * {@code xs:integer}'s inherited {@code fractionDigits=0} are never applied.
 * </p>
 */
final class SchemaFacets {

    private static final String CXF_NOISE_PATTERN = "\\c+";

    private final Map<String, String> singles = new LinkedHashMap<>();
    private final List<String> patterns = new ArrayList<>();

    private SchemaFacets() {
    }

    static SchemaFacets of(XSSimpleType simpleType) {
        var facets = new SchemaFacets();
        if (simpleType == null) {
            return facets;
        }
        for (var type = simpleType; type != null; type = nextUserRestrictionBase(type)) {
            if (!type.isRestriction()) {
                break;
            }
            var restriction = type.asRestriction();
            for (var facet : restriction.getDeclaredFacets()) {
                if (facet == null || facet.getValue() == null) {
                    continue;
                }
                var value = facet.getValue().value;
                if (value == null || value.isBlank()) {
                    continue;
                }
                var name = facet.getName();
                if (XSFacet.FACET_PATTERN.equals(name)) {
                    if (!CXF_NOISE_PATTERN.equals(value) && !facets.patterns.contains(value)) {
                        facets.patterns.add(value);
                    }
                } else {
                    facets.singles.putIfAbsent(name, value);
                }
            }
        }
        return facets;
    }

    private static XSSimpleType nextUserRestrictionBase(XSSimpleType type) {
        var base = type.getSimpleBaseType();
        if (base == null || isBuiltInSchemaType(base)) {
            return null;
        }
        return base;
    }

    private static boolean isBuiltInSchemaType(XSSimpleType type) {
        var ns = type.getTargetNamespace();
        return ns != null && XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(ns);
    }

    String get(String facetName) {
        return singles.get(facetName);
    }

    List<String> patterns() {
        return List.copyOf(patterns);
    }

    Integer intValue(String facetName) {
        return parseInt(get(facetName));
    }

    static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
