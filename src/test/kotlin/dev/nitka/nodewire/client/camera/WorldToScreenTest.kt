package dev.nitka.nodewire.client.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure world→canvas projection. Conventions under test: MC yaw 0 → +Z
 * (south), positive pitch looks down, canvas y-down, vertical FOV.
 */
class WorldToScreenTest {

    private fun proj(wx: Double, wy: Double, wz: Double, yaw: Float = 0f, pitch: Float = 0f) =
        WorldToScreen.project(0.0, 0.0, 0.0, yaw, pitch, 90.0, 256, 256, wx, wy, wz)

    @Test
    fun `point straight ahead lands at the canvas centre`() {
        val p = proj(0.0, 0.0, 10.0)!!
        assertEquals(128f, p[0], 0.01f)
        assertEquals(128f, p[1], 0.01f)
    }

    @Test
    fun `point behind the camera rejects`() {
        assertNull(proj(0.0, 0.0, -10.0))
    }

    @Test
    fun `west is screen-right when facing south`() {
        // Facing +Z: right hand of the view = −X (west).
        val p = proj(-5.0, 0.0, 10.0)!!
        assertTrue(p[0] > 128f, "west of a south-facing camera must be right of centre, was ${p[0]}")
        assertEquals(128f, p[1], 0.01f)
    }

    @Test
    fun `up in the world is up on the canvas (smaller y)`() {
        val p = proj(0.0, 5.0, 10.0)!!
        assertTrue(p[1] < 128f, "world +Y must be above centre (y-down canvas), was ${p[1]}")
        assertEquals(128f, p[0], 0.01f)
    }

    @Test
    fun `fov edge maps to the canvas edge`() {
        // Vertical FOV 90° → at z=10 the half-height of the frustum is 10 —
        // a point 10 up sits exactly on the top edge (y = 0).
        val p = proj(0.0, 10.0, 10.0)!!
        assertEquals(0f, p[1], 0.5f)
    }

    @Test
    fun `large absolute coordinates stay precise`() {
        // Same geometry shifted to a far-away plot region (the Sable shipyard
        // case) — eye-relative math must not lose the centre.
        val p = WorldToScreen.project(
            2.0481032E7, 128.0, 2.0481033E7,
            0f, 0f, 90.0, 256, 256,
            2.0481032E7, 128.0, 2.0481033E7 + 10.0,
        )!!
        assertEquals(128f, p[0], 0.05f)
        assertEquals(128f, p[1], 0.05f)
    }
}
