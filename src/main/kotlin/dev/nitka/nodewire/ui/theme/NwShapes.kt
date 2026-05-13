package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Immutable
import dev.nitka.nodewire.ui.render.RectangleShape
import dev.nitka.nodewire.ui.render.RoundedCornerShape
import dev.nitka.nodewire.ui.render.Shape

/**
 * Reusable shape presets. Components default to [medium]; [pill] is the
 * full-rounded preset used for tags and switches.
 *
 * Note: rounded shapes paint as sharp rects until Phase 11's ShapeRenderer
 * lands (see [SurfaceRenderer]). Storing the [Shape] in style classes now
 * means no API churn when rounding starts working visually.
 */
@Immutable
data class NwShapes(
    val rect: Shape = RectangleShape,
    val small: Shape = RoundedCornerShape(2),
    val medium: Shape = RoundedCornerShape(4),
    val large: Shape = RoundedCornerShape(6),
    val pill: Shape = RoundedCornerShape(999),
)
