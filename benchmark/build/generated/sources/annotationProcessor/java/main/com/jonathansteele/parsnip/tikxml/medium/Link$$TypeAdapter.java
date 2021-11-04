package com.jonathansteele.parsnip.tikxml.medium;

import com.tickaroo.tikxml.TikXmlConfig;
import com.tickaroo.tikxml.XmlReader;
import com.tickaroo.tikxml.XmlWriter;
import com.tickaroo.tikxml.typeadapter.AttributeBinder;
import com.tickaroo.tikxml.typeadapter.TypeAdapter;
import java.io.IOException;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.Map;

public class Link$$TypeAdapter implements TypeAdapter<Link> {
  private Map<String, AttributeBinder<Link>> attributeBinders = new  HashMap<String, AttributeBinder<Link>>();

  public Link$$TypeAdapter() {
    attributeBinders.put("rel", new AttributeBinder<Link>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Link value) throws IOException {
        value.rel = reader.nextAttributeValue();
      }
    });
    attributeBinders.put("href", new AttributeBinder<Link>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Link value) throws IOException {
        value.href = reader.nextAttributeValue();
      }
    });
    attributeBinders.put("title", new AttributeBinder<Link>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Link value) throws IOException {
        value.title = reader.nextAttributeValue();
      }
    });
    attributeBinders.put("type", new AttributeBinder<Link>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Link value) throws IOException {
        value.type = reader.nextAttributeValue();
      }
    });
  }

  @Override
  public Link fromXml(XmlReader reader, TikXmlConfig config) throws IOException {
    Link value = new Link();
    while(reader.hasAttribute()) {
      String attributeName = reader.nextAttributeName();
      AttributeBinder<Link> attributeBinder = attributeBinders.get(attributeName);
      if (attributeBinder != null) {
        attributeBinder.fromXml(reader, config, value);
      } else {
        if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
          throw new IOException("Could not map the xml attribute with the name '"+attributeName+"' at path "+reader.getPath()+" to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
        }
        reader.skipAttributeValue();
      }
    }
    while (reader.hasElement() || reader.hasTextContent()) {
      if (reader.hasElement()) {
        if (config.exceptionOnUnreadXml()) {
          throw new IOException("Could not map the xml element with the tag name '"+reader.nextElementName()+"' at path "+reader.getPath()+" to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
        }
        while(reader.hasElement()) {
          reader.beginElement();
          reader.skipRemainingElement();
        }
      } else if (reader.hasTextContent()) {
        if (config.exceptionOnUnreadXml()) {
          throw new IOException("Could not map the xml element's text content at path '"+reader.getPath()+" to java class. Have you annotated such a field in your java class to map the xml element's text content? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
        }
        reader.skipTextContent();
      }
    }
    return value;
  }

  @Override
  public void toXml(XmlWriter writer, TikXmlConfig config, Link value, String overridingXmlElementTagName) throws IOException {
    if (value != null) {
      if (overridingXmlElementTagName == null) {
        writer.beginElement("link");
      } else {
        writer.beginElement(overridingXmlElementTagName);
      }
      if (value.rel != null) {
        writer.attribute("rel", value.rel);
      }
      if (value.href != null) {
        writer.attribute("href", value.href);
      }
      if (value.title != null) {
        writer.attribute("title", value.title);
      }
      if (value.type != null) {
        writer.attribute("type", value.type);
      }
      writer.endElement();
    }
  }
}
