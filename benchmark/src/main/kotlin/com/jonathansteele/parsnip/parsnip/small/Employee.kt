package com.jonathansteele.parsnip.parsnip.small

import com.jonathansteele.parsnip.annotations.SerializedName
import com.jonathansteele.parsnip.annotations.Tag

@SerializedName("employee")
data class Employee(
    @Tag
    var name: String? = null
)