# parsnip
A modern XML library for Java

**Warning!** The parser is quite buggy at the moment and only works with the simplest of xml. 

## Why parsnip?
- Fast streaming parser based on [okio](https://github.com/square/okio).
- Simple modern api similar to [gson](https://github.com/google/gson) and [moshi](https://github.com/square/moshi).

## Why not parsnip?
- UTF-8 only
- Not a validating parser
- Doesn't support custom entities

### Parsing into objects
```java
String xml = ...;

Parsnip xml = new Parsnip.Builder().build();
XmlAdapter<BlackjackHand> xmlAdapter = xml.adapter(BlackjackHand.class);

BlackjackHand blackjackHand = xmlAdapter.fromXml(xml);
```

### Serialize objects into xml
```java
BlackjackHand blackjackHand = new BlackjackHand(
    new Card('6', SPADES),
    Arrays.asList(new Card('4', CLUBS), new Card('A', HEARTS)));

Parsnip xml = new Parsnip.Builder().build();
XmlAdapter<BlackjackHand> xmlAdapter = xml.adapter(BlackjackHand.class);

String xml = xmlAdapter.toXml(blackjackHand);
```

### Built in xml adapters
Parsnip has built-in support for reading and writing
- primative types
- arrays, collections and lists
- Strings
- enums

It supports classes by writing them out field-by-field. Primaitves will be written out as attributes by default, classes will be written out as tags.

If you have these classes:
```java
class BlackjackHand {
  public final Card hiddenCard;
  public final List<Card> visibleCards;
  ...
}

class Card {
  public final char rank;
  public final Suit suit;
  ...
}

enum Suit {
  CLUBS, DIAMONDS, HEARTS, SPADES;
}
```

Parsnip will read and write this xml:
```xml
<BlackjackHand>
  <hiddenCard rank="6" suit="SPADES"/>
  <Card rank="4" suit="CLUBS"/>
  <Card rank="A" suit="HEARTS"/>
</BlackjackHand>
```

### Custom naming
You can customzie the names of tags and attributes with `@SerializedName()`. The above example will look a little better as such:
```java
class BlackjackHand {
  @SerializedName("HiddenCard")
  public final Card hiddenCard;
  @SerializedName("VisibleCard")
  public final List<Card> visibleCards;
  ...
}

class Card {
  public final char rank;
  public final Suit suit;
  ...
}

enum Suit {
  CLUBS, DIAMONDS, HEARTS, SPADES;
}
```

```xml
<BlackjackHand>
  <HiddenCard rank="6" suit="SPADES"/>
  <VisibleCard rank="4" suit="CLUBS"/>
  <VisibleCard rank="A" suit="HEARTS"/>
</BlackjackHand>
```

### Text
You can use the `@Text` annotation to read/write the text of a tag.
```java
class Card {
  @Text
  public final char rank;
  public final Suit suit;
}
```
```xml
<Card suit="SPADES">6</Card>
```

### Tag
Often times you only care about the contents of a tag, not any of it's attributes. You can save some nesting in your hiarchy with the `@Tag` annotation.
```java
class Card {
  @Tag
  public final char rank;
  @Tag
  public final Suit suit;
}
```
```xml
<Card>
 <rank>6</rank>
 <suit>SPADES</suid>
</Card>
```

### Namespace
By default, any namespace on an element will be ignored. If you want to enforce a namespace, you can use the `@Namespace` annotation.

```java
class Card {
  @Namespace("http://example.com", alias="ns")
  public final char rank;
}
```
will read
```xml
<Card xmlns:ns="http://example.com" rank="ignored" ns:rank="6"/>
```
as `6`.

When writing xml, the given alias will be used.
