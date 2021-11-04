/*
 * Copyright 2015 Evan Tatarka.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jonathansteele.parsnip;

import okhttp3.ResponseBody;
import okio.BufferedSource;
import retrofit2.Converter;

import java.io.IOException;

public class ParsnipResponseBodyConverter<T> implements Converter<ResponseBody, T> {
    private final XmlAdapter<T> adapter;

    public ParsnipResponseBodyConverter(XmlAdapter<T> adapter) {
        this.adapter = adapter;
    }

    @Override
    public T convert(ResponseBody value) throws IOException {
        try (BufferedSource source = value.source()) {
            return adapter.fromXml(source);
        }
    }
}
