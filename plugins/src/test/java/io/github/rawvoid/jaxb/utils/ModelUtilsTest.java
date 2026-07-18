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

import com.sun.codemodel.JCodeModel;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CElementInfo;
import com.sun.tools.xjc.model.Model;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static io.github.rawvoid.jaxb.utils.ModelUtils.CELEMENTINFO_PARENT_FIELD;
import static io.github.rawvoid.jaxb.utils.ModelUtils.replaceClassReferences;
import static io.github.rawvoid.jaxb.utils.ReflectUtils.getFieldValue;
import static org.assertj.core.api.Assertions.assertThat;

class ModelUtilsTest {

    @Test
    void testReplaceClassReferencesUpdatesElementParentAndScopeMappings() {
        var model = new Model(new Options(), new JCodeModel(), NameConverter.standard, null, null);
        var from = new CClassInfo(model, model.codeModel, "io.github.rawvoid.jaxb.test.From", null, null, null, null, null);
        var to = new CClassInfo(model, model.codeModel, "io.github.rawvoid.jaxb.test.To", null, null, null, null, null);
        var elementName = new QName("urn:test", "localElement");
        var elementInfo = new CElementInfo(model, elementName, from, null, null, null, null, null);

        assertThat(model.getElementMappings(from.getClazz())).containsEntry(elementName, elementInfo);
        assertThat(model.getElementMappings(to.getClazz())).isNull();
        assertThat((Object) getFieldValue(CELEMENTINFO_PARENT_FIELD, elementInfo)).isEqualTo(from);

        replaceClassReferences(model, from, to);

        assertThat((Object) getFieldValue(CELEMENTINFO_PARENT_FIELD, elementInfo)).isEqualTo(to);
        assertThat(model.getElementMappings(from.getClazz())).isNull();
        assertThat(model.getElementMappings(to.getClazz())).containsEntry(elementName, elementInfo);
    }
}
