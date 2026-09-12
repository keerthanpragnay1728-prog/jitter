package dev.molasses.data.db

import androidx.room.TypeConverter
import dev.molasses.core.model.EventType

class Converters {
    // Stored by name rather than ordinal: reordering the enum would otherwise
    // silently rewrite the meaning of every historical row.
    @TypeConverter
    fun toEventType(value: String): EventType =
        runCatching { EventType.valueOf(value) }.getOrDefault(EventType.SCROLL)

    @TypeConverter
    fun fromEventType(type: EventType): String = type.name
}
