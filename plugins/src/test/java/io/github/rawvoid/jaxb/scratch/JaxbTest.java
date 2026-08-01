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

package io.github.rawvoid.jaxb.scratch;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author Rawvoid
 */
class JaxbTest {

    @Test
    @Disabled("scratch: observe XmlElementWrapper marshalling")
    void testXmlElementWrapper() throws JAXBException {
        var jaxbContext = JAXBContext.newInstance(Root.class);
        var marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        var root = new Root();
        root.id = "123";
        root.names = List.of("Andy", "Bob", 123);
        root.product1 = new Product();
        root.product1.id = List.of("123", "456");

        root.product2 = List.of("123", "456");

        Assertions.assertDoesNotThrow(() -> marshaller.marshal(root, System.out));
    }

    @XmlRootElement
    public static class Root {

        public String id;

        @XmlElements({
            @XmlElement(name = "name1", type = String.class),
            @XmlElement(name = "name2", type = Integer.class)
        })
        // @XmlElement(name = "name3")
        @XmlElementWrapper
        public List<Object> names;


        public Product product1;

        // @XmlAttribute
        // @XmlElement(name = "id")
        // @XmlList
        @XmlMixed
        @XmlElementWrapper
        public List<String> product2;

    }

    public static class Product {
        // @XmlID
        // @XmlValue
        // @XmlElement
        // @XmlList
        @XmlMixed
        public List<String> id;
    }
}
