package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import dev.pocketagent.core.places.GeoPoint
import dev.pocketagent.core.places.PlacesClient
import kotlinx.serialization.json.JsonObject
import java.util.Locale

private val bothModes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

/** Where the phone is. [locate] returns null when location is off or not granted. */
class LocationTool(
    private val locate: suspend () -> GeoPoint?,
    private val places: PlacesClient,
    private val enabled: () -> Boolean
) : Tool {
    override val name = "location"
    override val description = "Get the user's current location (address and coordinates). Use for local briefs, weather and anything \"near me\"."
    override val argumentsSignature = "{}"
    override val modes = bothModes
    override fun isAvailable(): Boolean = enabled()

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val here = locate() ?: return ToolResult.error(NO_LOCATION)
        val address = here.label.ifBlank { runCatching { places.reverse(here) }.getOrNull().orEmpty() }
        return ToolResult(
            buildString {
                if (address.isNotBlank()) append("Address: ").append(address).append('\n')
                append(String.format(Locale.US, "Coordinates: %.5f, %.5f", here.lat, here.lon))
            }
        )
    }

    companion object {
        const val NO_LOCATION = "location is unavailable. Ask the user to turn on Location sharing in Settings ▸ Connectors ▸ Location, or to name a place."
    }
}

/** Finds nearby businesses with distance, drive time and open/closed status. */
class PlacesTool(
    private val places: PlacesClient,
    private val locate: suspend () -> GeoPoint?,
    private val imperial: () -> Boolean
) : Tool {
    override val name = "places"
    override val description =
        "Find places (restaurants, shops, gas stations, a business by name) near the user or near a named place, with distance, drive time and whether they are open now. " +
            "Use action \"distance\" for how far an address or place is."
    override val argumentsSignature = """{"action"?: "search"|"distance", "query": string, "near"?: string (place name, default: user's location), "limit"?: int}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.string("query") ?: arguments.string("to") ?: arguments.string("place") ?: return ToolResult.error("missing \"query\"")
        val action = arguments.string("action")?.lowercase(Locale.US) ?: "search"
        val nearName = arguments.string("near")
        val origin = try {
            if (nearName != null) places.geocode(nearName) ?: return ToolResult.error("could not find \"$nearName\"") else locate()
        } catch (e: Exception) {
            return ToolResult.error("map lookup failed: ${e.message}")
        } ?: return ToolResult.error(LocationTool.NO_LOCATION)
        val units = imperial()
        return try {
            if (action == "distance") distance(origin, query, units) else search(origin, query, arguments.int("limit") ?: 5, units, nearName)
        } catch (e: Exception) {
            ToolResult.error("map lookup failed: ${e.message}")
        }
    }

    private suspend fun search(origin: GeoPoint, query: String, limit: Int, imperial: Boolean, nearName: String?): ToolResult {
        val found = places.search(query, origin, limit.coerceIn(1, 10))
        if (found.isEmpty()) return ToolResult("No places matching \"$query\" found near ${nearName ?: "the user"}. Try a broader term or web_search.")
        val out = StringBuilder("Places matching \"$query\" near ${nearName ?: "you"} (closest first):\n")
        found.forEachIndexed { i, p ->
            val meters = PlacesClient.distanceMeters(origin, p.point)
            val route = if (i < 3 && meters < 150_000) places.drive(origin, p.point) else null
            out.append(i + 1).append(". ").append(p.name)
            if (p.category.isNotBlank()) out.append(" (").append(p.category).append(')')
            out.append(" · ").append(PlacesClient.formatDistance(route?.meters ?: meters, imperial))
            if (route != null) out.append(", ").append(PlacesClient.formatDuration(route.seconds)).append(" drive")
            if (meters < 2_000) out.append(", about ").append(PlacesClient.formatDuration(meters / 1.35)).append(" walk")
            PlacesClient.openLine(p)?.let { out.append(" · ").append(it) }
            if (p.rating != null) out.append(" · ★").append(p.rating).append(p.ratingCount?.let { " ($it)" }.orEmpty())
            out.append('\n')
            if (p.address.isNotBlank()) out.append("   ").append(p.address).append('\n')
            p.phone?.let { out.append("   phone ").append(it).append('\n') }
            if (p.openNow == null && p.openingHours == null && p.hoursText.isEmpty()) out.append("   hours unknown\n")
        }
        out.append(if (found.any { it.openNow != null }) "Source: Google Places." else "Source: OpenStreetMap; hours can be out of date.")
        return ToolResult(out.toString().trim())
    }

    private suspend fun distance(origin: GeoPoint, target: String, imperial: Boolean): ToolResult {
        val dest = places.search(target, origin, 1).firstOrNull()?.point ?: places.geocode(target)
            ?: return ToolResult.error("could not find \"$target\"")
        val straight = PlacesClient.distanceMeters(origin, dest)
        val route = places.drive(origin, dest)
        return ToolResult(
            buildString {
                append("\"").append(target).append("\" is ").append(PlacesClient.formatDistance(straight, imperial)).append(" away in a straight line")
                if (route != null) {
                    append(", ").append(PlacesClient.formatDistance(route.meters, imperial)).append(" by road (about ")
                        .append(PlacesClient.formatDuration(route.seconds)).append(" driving without traffic)")
                }
                append('.')
            }
        )
    }
}

/** Current conditions and today's forecast for the user's location or a named place. */
class WeatherTool(
    private val places: PlacesClient,
    private val locate: suspend () -> GeoPoint?,
    private val imperial: () -> Boolean
) : Tool {
    override val name = "weather"
    override val description = "Current weather and today/tomorrow forecast for the user's location or a named place."
    override val argumentsSignature = """{"place"?: string}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val placeName = arguments.string("place") ?: arguments.string("near") ?: arguments.string("city")
        return try {
            val point = if (placeName != null) places.geocode(placeName) ?: return ToolResult.error("could not find \"$placeName\"") else locate()
                ?: return ToolResult.error(LocationTool.NO_LOCATION)
            ToolResult("Weather for ${placeName ?: "the user's location"}:\n" + places.weather(point, imperial()) + "\nSource: Open-Meteo.")
        } catch (e: Exception) {
            ToolResult.error("weather lookup failed: ${e.message}")
        }
    }
}
