package com.jonathansteele.parsnip.parnsip.small;

import com.jonathansteele.parsnip.annotations.SerializedName;
import com.jonathansteele.parsnip.annotations.Tag;

@SerializedName("employee")
public class Employee {
    @Tag
    public String name;
}
