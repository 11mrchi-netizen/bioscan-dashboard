package com.bioscan.fieldterminal.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// java.time as ISO strings, for what the app keeps on disk (TODAY's page, kept for the day: ui/screens/today/TodayStore).

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object LocalDateTimeSerializer : KSerializer<java.time.LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.time.LocalDateTime = java.time.LocalDateTime.parse(decoder.decodeString())
}

object ZonedDateTimeSerializer : KSerializer<java.time.ZonedDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.ZonedDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.time.ZonedDateTime = java.time.ZonedDateTime.parse(decoder.decodeString())
}

object OffsetDateTimeSerializer : KSerializer<java.time.OffsetDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.OffsetDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.time.OffsetDateTime = java.time.OffsetDateTime.parse(decoder.decodeString())
}

object DurationSerializer : KSerializer<java.time.Duration> {
    override val descriptor = PrimitiveSerialDescriptor("Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.Duration) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.time.Duration = java.time.Duration.parse(decoder.decodeString())
}

object YearMonthSerializer : KSerializer<java.time.YearMonth> {
    override val descriptor = PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.YearMonth) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.time.YearMonth = java.time.YearMonth.parse(decoder.decodeString())
}

object ZoneIdSerializer : KSerializer<java.time.ZoneId> {
    override val descriptor = PrimitiveSerialDescriptor("ZoneId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.ZoneId) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): java.time.ZoneId = java.time.ZoneId.of(decoder.decodeString())
}
