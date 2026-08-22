# decimal128-text-codec-kotlin

Thin BID128 ↔ text codec for IEEE 754-2019 decimal128, for Kotlin Multiplatform.

Copied from `decimal128-kotlin-legacy/decimal128-bid-string-codec`. One file, one
`object`, no runtime dependencies.

## API

```kotlin
import com.decimal128.textcodec.Decimal128BidTextCodec

// BID128 bits -> text
val s: String = Decimal128BidTextCodec.bid128ToString(hi, lo)

// text -> BID128 bits; returns null on success, an error message on failure
val bits = LongArray(2)
val err: String? = Decimal128BidTextCodec.bid128Parse(bits, "1.23E+45")
```

BID128 values are a pair of `Long`s: index 0 is the high 64 bits of the
encoding, index 1 the low 64.

## Scope

BID only — the DPD interchange format is not supported. Rounding, where
required, is `roundTiesToEven` and is not configurable. No arithmetic, no field
extraction, no value-type wrapper.

For the full decimal128 implementation see `decimal128-kotlin` at
<https://decimal128.com>.

## Targets

jvm, macosArm64, macosX64, linuxX64, iosArm64, iosSimulatorArm64, js(node).

## Tests

```
./gradlew jvmTest macosArm64Test jsNodeTest
```

`TestBid128ToString` and `TestHexBid128ToString` use `com.decimal128:decimal128-kotlin`
as an independent encode oracle. That is a **test-only** dependency; the
published artifact has none.

## License

MIT.
