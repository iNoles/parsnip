package com.jonathansteele.parsnip.parsnip.small

import com.jonathansteele.parsnip.Parsnip

fun parseParsnipXml(xml: String?) {
    val parsnip = Parsnip.Builder().build()
    val employeeAdapter = parsnip.adapter(
        Employee::class.java
    )
    val employee = employeeAdapter.fromXml(xml!!)
    println("Parsnip " + employee.name)
}