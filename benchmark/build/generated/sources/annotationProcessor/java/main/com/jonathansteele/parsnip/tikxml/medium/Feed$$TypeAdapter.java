package com.jonathansteele.parsnip.tikxml.medium;

import com.tickaroo.tikxml.TikXmlConfig;
import com.tickaroo.tikxml.XmlReader;
import com.tickaroo.tikxml.XmlWriter;
import com.tickaroo.tikxml.typeadapter.ChildElementBinder;
import com.tickaroo.tikxml.typeadapter.NestedChildElementBinder;
import com.tickaroo.tikxml.typeadapter.TypeAdapter;
import java.io.IOException;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Feed$$TypeAdapter implements TypeAdapter<Feed> {
  private Map<String, ChildElementBinder<Feed>> childElementBinders = new  HashMap<String, ChildElementBinder<Feed>>();

  public Feed$$TypeAdapter() {
    childElementBinders.put("entry", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
        if (value.entries == null) {
          value.entries = new ArrayList<Entry>();
        }
        value.entries.add((Entry) config.getTypeAdapter(Entry.class).fromXml(reader, config) );
      }
    });
    childElementBinders.put("author", new NestedChildElementBinder<Feed>(false) {
      {
        childElementBinders = new HashMap<String, ChildElementBinder<Feed>>();
        childElementBinders.put("name", new ChildElementBinder<Feed>() {
          @Override
          public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
            while(reader.hasAttribute()) {
              String attributeName = reader.nextAttributeName();
              if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
                throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
              }
              reader.skipAttributeValue();
            }
            value.author = reader.nextTextContent();
          }
        });
      }
    });
    childElementBinders.put("link", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
        value.link = config.getTypeAdapter(Link.class).fromXml(reader, config);
      }
    });
    childElementBinders.put("logo", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.logo = reader.nextTextContent();
      }
    });
    childElementBinders.put("generator", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
        while(reader.hasAttribute()) {
          String attributeName = reader.nextAttributeName();
          if (config.exceptionOnUnreadXml() && !attributeName.startsWith("xmlns")) {
            throw new IOException("Unread attribute '"+ attributeName +"' at path "+ reader.getPath());
          }
          reader.skipAttributeValue();
        }
        value.generator = reader.nextTextContent();
      }
    });
    childElementBinders.put("id", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
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
    childElementBinders.put("title", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
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
    childElementBinders.put("updated", new ChildElementBinder<Feed>() {
      @Override
      public void fromXml(XmlReader reader, TikXmlConfig config, Feed value) throws IOException {
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
  public Feed fromXml(XmlReader reader, TikXmlConfig config) throws IOException {
    Feed value = new Feed();
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
        ChildElementBinder<Feed> childElementBinder = childElementBinders.get(elementName);
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
  public void toXml(XmlWriter writer, TikXmlConfig config, Feed value, String overridingXmlElementTagName) throws IOException {
    if (value != null) {
      if (overridingXmlElementTagName == null) {
        writer.beginElement("feed");
      } else {
        writer.beginElement(overridingXmlElementTagName);
      }
      if (value.entries != null) {
        List<Entry> list = value.entries;
        int listSize = list.size();
        for (int i =0; i<listSize; i++) {
          Entry item = list.get(i);
          config.getTypeAdapter(Entry.class).toXml(writer, config, item, "entry");
        }
      }
      writer.beginElement("author");
      if (value.author != null) {
        writer.beginElement("name");
        if (value.author != null) {
          writer.textContent(value.author);
        }
        writer.endElement();
      }
      writer.endElement();
      if (value.link != null) {
        config.getTypeAdapter(Link.class).toXml(writer, config, value.link, "link");
      }
      if (value.logo != null) {
        writer.beginElement("logo");
        if (value.logo != null) {
          writer.textContent(value.logo);
        }
        writer.endElement();
      }
      if (value.generator != null) {
        writer.beginElement("generator");
        if (value.generator != null) {
          writer.textContent(value.generator);
        }
        writer.endElement();
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
