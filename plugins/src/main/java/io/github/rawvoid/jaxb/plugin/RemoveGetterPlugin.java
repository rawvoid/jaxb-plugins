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

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.OutlineUtils;
import org.xml.sax.ErrorHandler;

/**
 * Removes XJC-generated getter methods for bean properties.
 * <p>
 * Uses the property model ({@code prop.getName(true)}) to identify accessors,
 * matching XJC naming rather than stripping every {@code get*}/{@code is*} method.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xremove-getter", description = "Remove generated getter methods for fields")
public class RemoveGetterPlugin extends AbstractPlugin {

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        for (var classOutline : outline.getClasses()) {
            OutlineUtils.removePropertyAccessors(classOutline, true, false);
        }
        return true;
    }
}
