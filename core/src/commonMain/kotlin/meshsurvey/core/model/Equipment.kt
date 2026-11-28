package meshsurvey.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class AntennaType { INTERNAL, EXTERNAL }
enum class Placement { INDOOR, OUTDOOR }
enum class Mounting { VEHICLE, HANDHELD, PORTABLE, STATIONARY }

/**
 * An optional, reusable description of the user's equipment and installation. Every field is **entered by
 * the user**; null means unknown and stays unknown. What the radio itself reports (model, firmware, region,
 * preset, transmit power, frequency) is not stored here but recorded with each survey session, so the two
 * are never confused.
 */
data class EquipmentProfile(
    val id: String,
    val name: String,
    val radioMake: String? = null,
    val radioModel: String? = null,
    val chipset: String? = null,
    val antennaMake: String? = null,
    val antennaModel: String? = null,
    val antennaGainDbi: Double? = null,
    val antennaType: AntennaType? = null,
    val placement: Placement? = null,
    val mounting: Mounting? = null,
    val antennaHeightM: Double? = null,
    val feedlineType: String? = null,
    val feedlineLengthM: Double? = null,
    val enclosure: String? = null,
    val notes: String? = null,
    val createdMs: Long = 0,
    val updatedMs: Long = 0,
    /** Device that created the profile; null = this device. */
    val originDevice: String? = null,
) {
    /** One-line description for lists: only known values. */
    fun summary(): String = listOfNotNull(
        listOfNotNull(radioMake, radioModel).joinToString(" ").ifBlank { null },
        listOfNotNull(antennaMake, antennaModel).joinToString(" ").ifBlank { null }?.let { "antenna $it" },
        antennaGainDbi?.let { "${fmt(it)} dBi" },
        antennaType?.name?.lowercase(), placement?.name?.lowercase(), mounting?.name?.lowercase(),
        antennaHeightM?.let { "${fmt(it)} m high" },
    ).joinToString(" · ").ifBlank { "no details entered" }

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/** JSON form of the user-entered fields (stored in the database and in export bundles). */
object EquipmentCodec {
    fun encode(p: EquipmentProfile): String = toJson(p).toString()

    fun toJson(p: EquipmentProfile): JsonObject = buildJsonObject {
        put("id", p.id); put("name", p.name)
        put("radio_make", p.radioMake); put("radio_model", p.radioModel); put("chipset", p.chipset)
        put("antenna_make", p.antennaMake); put("antenna_model", p.antennaModel); put("antenna_gain_dbi", p.antennaGainDbi)
        put("antenna_type", p.antennaType?.name); put("placement", p.placement?.name); put("mounting", p.mounting?.name)
        put("antenna_height_m", p.antennaHeightM); put("feedline_type", p.feedlineType); put("feedline_length_m", p.feedlineLengthM)
        put("enclosure", p.enclosure); put("notes", p.notes); put("created_ms", p.createdMs); put("updated_ms", p.updatedMs)
    }

    fun decode(text: String, originDevice: String? = null): EquipmentProfile? =
        runCatching { fromJson(Json.parseToJsonElement(text).jsonObject, originDevice) }.getOrNull()

    fun fromJson(o: JsonObject, originDevice: String? = null): EquipmentProfile? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()?.ifEmpty { null }
        fun d(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
        fun l(k: String) = d(k)?.toLong()
        val id = s("id") ?: return null
        return EquipmentProfile(
            id = id, name = s("name") ?: "Unnamed profile",
            radioMake = s("radio_make"), radioModel = s("radio_model"), chipset = s("chipset"),
            antennaMake = s("antenna_make"), antennaModel = s("antenna_model"), antennaGainDbi = d("antenna_gain_dbi"),
            antennaType = s("antenna_type")?.let { runCatching { AntennaType.valueOf(it) }.getOrNull() },
            placement = s("placement")?.let { runCatching { Placement.valueOf(it) }.getOrNull() },
            mounting = s("mounting")?.let { runCatching { Mounting.valueOf(it) }.getOrNull() },
            antennaHeightM = d("antenna_height_m"), feedlineType = s("feedline_type"), feedlineLengthM = d("feedline_length_m"),
            enclosure = s("enclosure"), notes = s("notes"), createdMs = l("created_ms") ?: 0, updatedMs = l("updated_ms") ?: 0,
            originDevice = originDevice,
        )
    }
}
