package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaTest {
    private val athlete = """{"id":1,"bikes":[
        {"id":"b1","primary":false,"name":"Gravel","distance":512345.6},
        {"id":"b2","primary":true,"name":"","distance":1000000.0}]}"""

    @Test fun readsBikesInKilometres() {
        val bikes = StravaApi.bikes(athlete)
        assertEquals(listOf("Gravel", "b2"), bikes.map { it.name }) // a nameless bike shows its id
        assertEquals(512.3456, bikes[0].km, 0.0001)
        assertEquals(emptyList<StravaBike>(), StravaApi.bikes("""{"id":1}"""))
        assertEquals(emptyList<StravaBike>(), StravaApi.bikes("not json"))
    }

    @Test fun picksChosenThenPrimaryThenFirst() {
        val bikes = StravaApi.bikes(athlete)
        assertEquals("b1", StravaApi.pick(bikes, "b1")!!.id)
        assertEquals("b2", StravaApi.pick(bikes, "gone")!!.id)
        assertEquals("b2", StravaApi.pick(bikes, null)!!.id)
        assertNull(StravaApi.pick(emptyList(), null))
    }

    @Test fun tokensAndLoginAddress() {
        assertEquals(Tokens("a", "r", 99), StravaApi.tokens("""{"access_token":"a","refresh_token":"r","expires_at":99}"""))
        assertNull(StravaApi.tokens("""{"message":"Bad Request"}"""))
        val url = StravaApi.authUrl("123", "s t")
        assertTrue(url.startsWith("https://www.strava.com/oauth/mobile/authorize?client_id=123&"))
        assertTrue("redirect_uri=freehub%3A%2F%2Flocalhost" in url)
        assertTrue("scope=read%2Cprofile%3Aread_all" in url)
        assertTrue(url.endsWith("state=s+t"))
    }
}
