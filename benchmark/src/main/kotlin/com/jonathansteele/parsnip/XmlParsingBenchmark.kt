package com.jonathansteele.parsnip

import com.google.caliper.BeforeExperiment
import com.google.caliper.Benchmark
import com.google.caliper.api.VmOptions
import com.google.caliper.runner.CaliperMain
import com.jonathansteele.parsnip.parsnip.small.parseParsnipXml
import com.jonathansteele.parsnip.tikxml.small.parseTikXml

fun main(args: Array<String>) {
    CaliperMain.main(XmlBenchmark::class.java, args)
}

@VmOptions("-XX:-TieredCompilation")
class XmlBenchmark {
    private lateinit var xml: String

    @BeforeExperiment
    fun setUp() {
        xml = object {}.javaClass.getResource("small.xml")!!.readText()
    }

    @Benchmark
    fun jackson() {
        parseJacksonXml(xml)
    }

    @Benchmark
    fun tikXml() {
        parseTikXml(xml)
    }

    @Benchmark
    fun simpleFramework() {
        parseSimpleFrameworkXml(xml)
    }

    @Benchmark
    fun parnsip() {
        parseParsnipXml(xml)
    }
}