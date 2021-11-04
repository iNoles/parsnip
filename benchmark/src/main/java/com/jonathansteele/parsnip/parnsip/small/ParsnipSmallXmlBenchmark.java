package com.jonathansteele.parsnip.parnsip.small;

import com.jonathansteele.parsnip.Parsnip;
import com.jonathansteele.parsnip.XmlAdapter;

public class ParsnipSmallXmlBenchmark {
    public void parse(String xml) throws Exception {
        Parsnip parsnip = new Parsnip.Builder().build();
        XmlAdapter<Employee> employeeAdapter = parsnip.adapter(Employee.class);
        Employee employee = employeeAdapter.fromXml(xml);
        System.out.println(getClass().getSimpleName() + " " + employee.name);
    }
}
