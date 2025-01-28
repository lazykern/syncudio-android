package io.github.zyrouge.symphony.utils

import android.net.Uri
import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

class RoomConvertors {
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    @TypeConverter
    fun fromUri(value: Uri?): String? = value?.toString()

    @TypeConverter
    fun toUri(value: String?): Uri? = value?.let { Uri.parse(it) }

    @TypeConverter
    fun fromStringSet(value: Set<String>?): String? = value?.joinToString("|")

    @TypeConverter
    fun toStringSet(value: String?): Set<String> = value?.split("|")?.toSet() ?: emptySet()

    @TypeConverter
    fun serializeStringSet(value: Set<String>) = Json.encodeToString(value)

    @TypeConverter
    fun deserializeStringSet(value: String) = Json.decodeFromString<Set<String>>(value)

    @TypeConverter
    fun serializeStringList(value: List<String>) = Json.encodeToString(value)

    @TypeConverter
    fun deserializeStringList(value: String) = Json.decodeFromString<List<String>>(value)
}
