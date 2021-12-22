/*
 * Copyright (C) 2015 Hannes Dorfmann
 * Copyright (C) 2015 Tickaroo, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.jonathansteele.parsnip

import com.fasterxml.jackson.dataformat.xml.XmlMapper

fun parseJacksonXml(xml: String?) {
    val mapper = XmlMapper()
    val employee = mapper.readValue(xml, JacksonEmployee::class.java)
    println("Jackson " + employee.name)
}

class JacksonEmployee {
    var name: String? = null
}