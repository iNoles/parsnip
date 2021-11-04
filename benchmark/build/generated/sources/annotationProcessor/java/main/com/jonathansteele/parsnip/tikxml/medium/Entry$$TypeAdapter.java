package com.jonathansteele.parsnip.tikxml.medium;

import com.tickaroo.tikxml.TikXmlConfig;
import com.tickaroo.tikxml.XmlReader;
import com.tickaroo.tikxml.XmlWriter;
import com.tickaroo.tikxml.typeadapter.ChildElementBinder;
import com.tickaroo.tikxml.typeadapter.TypeAdapter;
import java.io.IOException;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Entry$$TypeAdapter implements TypeAdapter<Entry> {
  private Map<String, ChildElementBinder<Entry>> childElementBinders = new  HashMap<String, ChildElementBinder<Entry>>();

  public Entry$$TypeAdapter() {
    childElementBinders.put("summary", new ChildElementBinder<Entry>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Entry value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.summary = reader.nextTextContent();
      }
    });
    childElementBinders.put("link", new ChildElementBinder<Entry>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Entry value) throws IOException {
        if (value.links == null) {
          value.links = new ArrayList<Link>();
        }
        value.links.add((Link) config.getTypeAdapter(Link.class).fromXml(reader, config) );
      }
    });
    childElementBinders.put("id", new ChildElementBinder<Entry>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Entry value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.id = reader.nextTextContent();
      }
    });
    childElementBinders.put("title", new ChildElementBinder<Entry>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Entry value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.title = reader.nextTextContent();
      }
    });
    childElementBinders.put("updated", new ChildElementBinder<Entry>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Entry value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.updated = reader.nextTextContent();
      }
    });
  }

  @Override
  public Entry fromXml(XmlReader reader, TikXmlConfig config) throws IOException {
    Entry value = new Entry();
    while(reader.hasAttribute()) {
      String attributeName = reader.nextAttributeName();
      if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
        throw new IOException("Could not map the xml attribute with the name '"+attributeName+"' at path "+reader.getPath()+" to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
      }
      reader.skipAttributeValue();
    }
    while(true) {
      if (reader.hasElement()) {
        reader.beginElement();
        String elementName = reader.nextElementName();
        ChildElementBinder<Entry> childElementBinder = childElementBinders.get(elementName);
        if (childElementBinder != null) {
          childElementBinder.fromXml(reader, config, value);
          reader.endElement();
        } else if (config.exceptionOnUnreadXml()) {
          throw new IOException("Could not map the xml element with the tag name <" + elementName + "> at path '" + reader.getPath()+"' to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
        } else {
          reader.skipRemainingElement();
        }
      } else if (reader.hasTextContent()) {
        if (config.exceptionOnUnreadXml()) {
          throw new IOException("Could not map the xml element's text content at path '"+reader.getPath()+" to java class. Have you annotated such a field in your java class to map the xml element's text content? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().");
        }
        reader.skipTextContent();
      } else {
        break;
      }
    }
    return value;
  }

  @Override
  public void toXml(XmlWriter writer, TikXmlConfig config, Entry value, String overridingXmlElementTagName) throws IOException {
    if (value != null) {
      if (overridingXmlElementTagName == null) {
        writer.beginElement("entry");
      } else {
        writer.beginElement(overridingXmlElementTagName);
      }
      if (value.summary != null) {
        writer.beginElement("summary");
        if (value.summary != null) {
          writer.textContent(value.summary);
        }
        writer.endElement();
      }
      if (value.links != null) {
        List<Link> list = value.links;
        int listSize = list.size();
        for (int i =0; i<listSize; i++) {
          Link item = list.get(i);
          config.getTypeAdapter(Link.class).toXml(writer, config, item, "link");
        }
      }
      if (value.id != null) {
        writer.beginElement("id");
        if (value.id != null) {
          writer.textContent(value.id);
        }
        writer.endElement();
      }
      if (value.title != null) {
        writer.beginElement("title");
        if (value.title != null) {
          writer.textContent(value.title);
        }
        writer.endElement();
      }
      if (value.updated != null) {
        writer.beginElement("updated");
        if (value.updated != null) {
          writer.textContent(value.updated);
        }
        writer.endElement();
      }
      writer.endElement();
    }
  }
}
