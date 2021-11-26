package com.jonathansteele.parsnip.adapters

import com.jonathansteele.parsnip.XmlAdapter
import com.jonathansteele.parsnip.XmlReader
import com.jonathansteele.parsnip.XmlWriter
import java.util.Date

/**
 * Formats dates using <a href="https://www.ietf.org/rfc/rfc3339.txt">RFC 3339</a>, which is
 * formatted like {@code 2015-09-26T18:23:50.250Z}.
 */
class Rfc3339DateJsonAdapter : XmlAdapter<Date>() {
    override fun fromXml(reader: XmlReader): Date {
        val string: String = reader.nextText()
        return Iso8601Utils.parse(string)
    }

    override fun toXml(writer: XmlWriter, value: Date) {
        val string = Iso8601Utils.format(value)
        writer.value(string)
    }
}
