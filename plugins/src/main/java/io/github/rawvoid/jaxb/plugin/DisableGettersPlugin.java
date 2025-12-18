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

package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * Plugin to disable generation of getter methods for fields.
 *
 * @author Rawvoid
 */
@Option(name = "Xdisable-getters", description = "Disable generation of getter methods for fields")
public class DisableGettersPlugin extends AbstractPlugin {

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        var classes = outline.getClasses();
        for (var classOutline : classes) {
            var methods = classOutline.implClass.methods();
            methods.removeIf(method -> (method.name().startsWith("get") || method.name().startsWith("is"))
                && method.params().isEmpty());
        }
        return true;
    }
}
