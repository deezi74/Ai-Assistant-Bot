package dev.pocketagent.core

import dev.pocketagent.core.places.GeoPoint
import dev.pocketagent.core.places.OpeningHours
import dev.pocketagent.core.places.PlacesClient
import dev.pocketagent.core.tools.PlacesTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PlacesTest {
    // 2026-11-02 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    @Test
    fun parsesCommonOpeningHours() {
        val h = OpeningHours.parse("Mo-Fr 09:00-17:00; Sa 10:00-14:00; Su off")!!
        assertEquals("open now, closes 5pm", h.stateAt(at(2, 12)).detail)
        assertEquals("closed now, opens 9am today", h.stateAt(at(2, 7)).detail)
        assertEquals("closed now, opens 10am tomorrow", h.stateAt(at(6, 18)).detail) // Friday evening
        assertEquals("closed now, opens 9am Monday", h.stateAt(at(7, 15)).detail) // Saturday after close
        assertTrue(OpeningHours.parse("24/7")!!.stateAt(at(3, 3)).open)
    }

    @Test
    fun handlesSplitShiftsCommasAndMidnight() {
        val h = OpeningHours.parse("Mo-Th 11:30-14:00,17:00-22:00, Fr-Sa 17:00-02:00")!!
        assertFalse(h.stateAt(at(2, 15)).open)
        assertEquals("open now, closes 10pm", h.stateAt(at(2, 18)).detail)
        assertEquals("open now, closes 2am", h.stateAt(at(7, 1, 30)).detail) // Saturday 1:30 from Friday night
        assertNull(OpeningHours.parse("Jan-Mar Mo 10:00-12:00"))
    }

    @Test
    fun placesToolReportsDistanceDriveTimeAndHours() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search") -> MockResponse().setBody(
                        """[{"name":"Luigi's Pizza","display_name":"Luigi's Pizza, 1 Main St, Springfield, USA","lat":"40.0100","lon":"-75.0000",
                           "type":"restaurant","extratags":{"opening_hours":"24/7","phone":"+1 555 0100"}}]"""
                    )
                    path.startsWith("/route") -> MockResponse().setBody("""{"routes":[{"distance":1800.0,"duration":300.0}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val client = PlacesClient(nominatimBase = base, osrmBase = base, meteoBase = base)
        val tool = PlacesTool(client, { GeoPoint(40.0, -75.0) }, { true })
        val r = tool.execute(buildJsonObject { put("query", JsonPrimitive("pizza")) })
        server.shutdown()
        assertFalse(r.output, r.isError)
        assertTrue(r.output, r.output.contains("Luigi's Pizza"))
        assertTrue(r.output, r.output.contains("1.1 mi"))
        assertTrue(r.output, r.output.contains("5 min drive"))
        assertTrue(r.output, r.output.contains("open 24 hours"))
    }
}
