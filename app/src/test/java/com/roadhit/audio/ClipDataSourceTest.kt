package com.roadhit.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipDataSourceTest {

    /**
     * The clip stream sends the same pinned User-Agent as the API call (final review M6), not the
     * platform default that names the device model and OS build. Media3's factory has no getter,
     * so its field is read directly; a rename in Media3 fails this test loudly.
     */
    @Test fun clipStreamSendsThePinnedUserAgent() {
        val factory = clipHttpDataSourceFactory()
        val field = factory.javaClass.getDeclaredField("userAgent").apply { isAccessible = true }
        assertEquals("RoadHit/1.0", field.get(factory))
    }
}
