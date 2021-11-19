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
package com.jonathansteele.parsnip

import retrofit2.Retrofit
import kotlin.jvm.JvmOverloads
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import java.lang.reflect.Type

@Suppress("unused")
class ParsnipConverterFactory private constructor(private val parsnip: Parsnip) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *> {
        val adapter = parsnip.adapter<Any>(type)
        return ParsnipResponseBodyConverter(adapter)
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody> {
        val adapter= parsnip.adapter<Any>(type)
        return ParsnipRequestBodyConverter<Any>(adapter)
    }

    companion object {
        @JvmOverloads
        fun create(parsnip: Parsnip = Parsnip.Builder().build()): ParsnipConverterFactory =
            ParsnipConverterFactory(parsnip)
    }
}