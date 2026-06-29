package biz.cesena.packride4.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import biz.cesena.packride4.routing.RouteInstruction
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "saved_routes")
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val destinationLat: Double,
    val destinationLon: Double,
    val distanceMeters: Double,
    val durationMillis: Long,
    val pointsJson: String,       // serialized List<Pair<Double,Double>>
    val instructionsJson: String, // serialized List<RouteInstruction>
    val savedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun serializePoints(points: List<Pair<Double, Double>>): String =
            JSONArray().also { arr ->
                points.forEach { (lat, lon) ->
                    arr.put(JSONObject().put("lat", lat).put("lon", lon))
                }
            }.toString()

        fun deserializePoints(json: String): List<Pair<Double, Double>> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getDouble("lat") to o.getDouble("lon")
            }
        }

        fun serializeInstructions(instructions: List<RouteInstruction>): String =
            JSONArray().also { arr ->
                instructions.forEach { instr ->
                    arr.put(JSONObject()
                        .put("text", instr.text)
                        .put("distanceMeters", instr.distanceMeters)
                        .put("timeMillis", instr.timeMillis)
                        .put("sign", instr.sign)
                        .put("modifier", instr.modifier)
                        .put("exitNumber", instr.exitNumber)
                        .put("turnAngle", if (instr.turnAngle.isNaN()) JSONObject.NULL else instr.turnAngle))
                }
            }.toString()

        fun deserializeInstructions(json: String): List<RouteInstruction> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RouteInstruction(
                    text = o.getString("text"),
                    distanceMeters = o.getDouble("distanceMeters"),
                    timeMillis = o.getLong("timeMillis"),
                    sign = o.getInt("sign"),
                    modifier = o.optString("modifier", ""),
                    exitNumber = o.optInt("exitNumber", 0),
                    turnAngle = if (o.has("turnAngle") && !o.isNull("turnAngle")) o.getDouble("turnAngle") else Double.NaN,
                )
            }
        }
    }
}
