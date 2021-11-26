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
```kotlin
val xml: String = "<text>Hello There</text>"

val parsnip: Parsnip = Parsnip.Builder().build()
val xmlAdapter: XmlAdapter<BlackjackHand> = parsnip.adapter<BlackjackHand>()

val blackjackHand = xmlAdapter.fromJson(json)
println(blackjackHand)
```

### Serialize objects into xml
```kotlin
val blackjackHand = BlackjackHand(
    Card('6', SPADES),
    listOf(Card('4', CLUBS), Card('A', HEARTS))
  )

val parsnip: Parsnip = Parsnip.Builder().build()
val xmlAdapter: XmlAdapter<BlackjackHand> = parsnip.adapter<BlackjackHand>()

val xml: String = xmlAdapter.toJson(blackjackHand)
println(xml)
```

### Built in xml adapters
Parsnip has built-in support for reading and writing
- primitive types
- arrays, collections and lists
- Strings
- enums

It supports classes by writing them out field-by-field. Primitives will be written out as attributes by default, classes will be written out as tags.

If you have these classes:

```kotlin
class BlackjackHand(
    val hidden_card: Card,
    val visible_cards: List<Card>,
)

class Card(
    val rank: Char,
    val suit: Suit,
)

enum class Suit {
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

```xml
<BlackjackHand>
  <HiddenCard rank="6" suit="SPADES"/>
  <VisibleCard rank="4" suit="CLUBS"/>
  <VisibleCard rank="A" suit="HEARTS"/>
</BlackjackHand>
```

### Text
You can use the `@Text` annotation to read/write the text of a tag.
```kotlin
class Card {
    @Text
    var rank: Char
    var suit: Suit
}
```

```xml
<Card suit="SPADES">6</Card>
```

### Tag
Often times you only care about the contents of a tag, not any of it's attributes. You can save some nesting in your hiarchy with the `@Tag` annotation.
```kotlin
class Card {
  @Tag
  var rank: Char
  @Tag
  var suit: Suit
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

```kotlin
class Card {
  @Namespace("http://example.com", alias="ns")
  var rank: Char
}
```
will read
```xml
<Card xmlns:ns="http://example.com" rank="ignored" ns:rank="6"/>
```
as `6`.

When writing xml, the given alias will be used.
