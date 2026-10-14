package com.acmeaom.myradar.sdkdemo

import com.acmeaom.myradar.sdk.LatLng
import com.acmeaom.myradar.sdkdemo.FlightTracker.Query
import com.acmeaom.myradar.sdkdemo.data.AdsbFlightDataService
import com.acmeaom.myradar.sdkdemo.data.Airlines
import com.acmeaom.myradar.sdkdemo.data.GreatCircle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLogicTest {

    @Test
    fun parsesQueries() {
        assertEquals(Query.Flight("UAL1"), Query.parse("ua 1"))
        assertEquals(Query.Flight("DAL100"), Query.parse("DAL100"))
        assertEquals(Query.SingleAirport("SFO"), Query.parse(" sfo "))
        assertEquals(Query.AirportPair("SFO", "LAX"), Query.parse("SFO-LAX"))
        assertEquals(Query.AirportPair("KSFO", "KJFK"), Query.parse("ksfo to kjfk"))
        assertNull(Query.parse("  "))
    }

    @Test
    fun convertsFlightNumbersToCallsigns() {
        assertEquals("UAL1", Airlines.callsign("UA 1"))
        assertEquals("JBU413", Airlines.callsign("b6413"))
        assertEquals("AAL123", Airlines.callsign("AAL123"))
        assertEquals("ZZ9", Airlines.callsign("ZZ9"))
        assertEquals("UA 1", AdsbFlightDataService.spacedFlightNumber("UA1", "UA"))
        assertEquals("B6 413", AdsbFlightDataService.spacedFlightNumber("B6413", "B6"))
        assertEquals("UA 1", AdsbFlightDataService.spacedFlightNumber("UA1", null))
    }

    @Test
    fun greatCircleBasics() {
        val sfo = LatLng(37.619, -122.375)
        val jfk = LatLng(40.6413, -73.7781)
        assertEquals(2245.0, GreatCircle.distanceNauticalMiles(sfo, jfk), 15.0)
        val mid = GreatCircle.interpolate(sfo, jfk, 0.5)
        assertEquals(GreatCircle.distanceNauticalMiles(sfo, mid), GreatCircle.distanceNauticalMiles(mid, jfk), 0.5)
        assertEquals(65, GreatCircle.path(sfo, jfk).size)
        assertEquals(90.0, GreatCircle.initialBearing(LatLng(0.0, 0.0), LatLng(0.0, 10.0)), 1e-6)
    }

    @Test
    fun parsesAircraftRecord() {
        val record = JSONObject("""{"hex":"a1b2c3","flight":"UAL1    ","lat":37.0,"lon":-122.0,"alt_baro":"ground","gs":12.5}""")
        val state = AdsbFlightDataService.parseAircraft(record)!!
        assertEquals("UAL1", state.callsign)
        assertTrue(state.onGround)
        assertNull(state.altitudeFeet)
        assertNull(AdsbFlightDataService.parseAircraft(JSONObject("""{"hex":"x"}""")))
    }

    @Test
    fun traceKeepsOnlyLatestLegFromTakeoffRoll() {
        val json = JSONObject(
            """{"timestamp":1000,"trace":[
              [0,1,1,"ground",0,0,0],
              [10,2,2,5000,300,0,0],
              [20,3,3,"ground",0,0,0],
              [30,4,4,"ground",0,0,0],
              [40,5,5,"ground",0,0,0],
              [50,6,6,1000,150,0,0],
              [60,7,7,3000,200,0,0]]}""",
        )
        val points = AdsbFlightDataService.parseTrace(json)
        // A new leg starts at the first airborne fix after the aircraft was on the ground.
        assertEquals(listOf(6.0, 7.0), points.map { it.coordinate.latitude })
    }
}
