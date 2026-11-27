package dev.whoop4.bloop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whoop4.bloop.app.ActivityType

/**
 * Original line icons for activity types (24×24 viewport, 1.8 stroke, same style as [BloopIcons]).
 * Keys match [ActivityType.icon]; unknown keys fall back to the heart.
 */
object ActivityIcons {
    /** A full circle as two arcs, for path strings. */
    private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy A$r,$r 0 1,0 ${cx + r},$cy A$r,$r 0 1,0 ${cx - r},$cy"

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private val heart = icon("heart", "M12,20 C5,15 3,12 3,8.5 A4.5,4.5 0 0,1 12,6.5 A4.5,4.5 0 0,1 21,8.5 C21,12 19,15 12,20 Z")

    private val byKey: Map<String, ImageVector> = mapOf(
        "run" to icon("run", c(15f, 4.5f, 1.6f), "M14,8 L11,13", "M11,13 L14,16 L13,21", "M11,13 L8,16 L4,16", "M7,10 L10,8 L14,8 L16,11 L19,11"),
        "walk" to icon("walk", c(13f, 4.5f, 1.6f), "M12.5,8 L11.5,14", "M11.5,14 L14,17 L14,21", "M11.5,14 L9,21", "M9,12 L12.5,8.5 L15,12"),
        "hike" to icon("hike", "M2,20 L9,8 L13,14 L16,10 L22,20 Z", "M7,11.5 L9,13 L11,11.5"),
        "bike" to icon("bike", c(6f, 16f, 4f), c(18f, 16f, 4f), "M6,16 L10,9 H15 L18,16", "M10,9 L12.5,16 L15,9", "M8.5,6.5 H11.5", "M10,6.5 L10,9", "M15,9 L14,6 H16.5"),
        "row" to icon("row", "M3,14 H21 L18.5,18 H5.5 Z", "M8,21 L16,5", "M14.5,3.5 L18,5.5"),
        "swim" to icon("swim", c(16f, 7f, 1.6f), "M5,12 L10,8 L14,11", "M3,16 Q5.25,14 7.5,16 T12,16 T16.5,16 T21,16", "M3,20 Q5.25,18 7.5,20 T12,20 T16.5,20 T21,20"),
        "dumbbell" to icon("dumbbell", "M6,7 V17", "M3,9.5 V14.5", "M18,7 V17", "M21,9.5 V14.5", "M6,12 H18"),
        "kettlebell" to icon("kettlebell", c(12f, 15f, 5.5f), "M8.5,11 V8 A3.5,3.5 0 0,1 15.5,8 V11"),
        "flame" to icon("flame", "M12,21 A6,6 0 0,1 6,15 C6,10 11,8 11,3 C14,5 18,9 18,15 A6,6 0 0,1 12,21 Z", "M12,21 A2.5,2.5 0 0,1 9.5,18.5 C9.5,16.5 12,15.5 12,13.5 C13.5,15 14.5,16.5 14.5,18.5 A2.5,2.5 0 0,1 12,21"),
        "yoga" to icon("yoga", c(12f, 5f, 1.6f), "M12,8.5 V14", "M6,10 L12,11.5 L18,10", "M4.5,18 L12,14 L19.5,18", "M7,20 H17"),
        "elliptical" to icon("elliptical", "M3,12 A9,5 0 1,0 21,12 A9,5 0 1,0 3,12", c(12f, 12f, 1.2f)),
        "stairs" to icon("stairs", "M3,20 H8 V15 H13 V10 H18 V5 H21"),
        "carabiner" to icon("carabiner", "M10,20 A4,4 0 0,1 6,16 V8 A4,4 0 0,1 14,8 L17,15 A4,4 0 0,1 13.5,20 Z", "M10,9 V15"),
        "glove" to icon("glove", "M7,15 V9 A5,5 0 0,1 17,9 V14 A4,4 0 0,1 13,18 H9 A2,2 0 0,1 7,16", "M7,12 H11", "M8,18 V21 H15 V18"),
        "kick" to icon("kick", c(7f, 4.5f, 1.6f), "M7.5,8 L10,14", "M10,14 L8,21", "M10,14 L20,9.5", "M4,11 L8,9 L12,9.5"),
        "football" to icon("football", c(12f, 12f, 9f), "M12,8 L15.8,10.8 L14.4,15.2 H9.6 L8.2,10.8 Z", "M12,8 V3", "M15.8,10.8 L20.6,9.2", "M14.4,15.2 L17.3,19.3", "M9.6,15.2 L6.7,19.3", "M8.2,10.8 L3.4,9.2"),
        "basketball" to icon("basketball", c(12f, 12f, 9f), "M3,12 H21", "M12,3 V21", "M5.6,5.6 Q10,12 5.6,18.4", "M18.4,5.6 Q14,12 18.4,18.4"),
        "volleyball" to icon("volleyball", c(12f, 12f, 9f), "M12,12 Q15,7 12,3", "M12,12 Q6.5,13 4.2,16.5", "M12,12 Q14.5,17 19.8,16.5"),
        "racket" to icon("racket", c(14.5f, 9.5f, 5.5f), "M10.6,13.4 L4,20", c(5.5f, 6f, 1.5f)),
        "shuttlecock" to icon("shuttlecock", "M10,17 H14 V19 A2,2 0 0,1 10,19 Z", "M10,17 L7,4 H17 L14,17", "M12,4 V17"),
        "flag" to icon("flag", "M7,21 V3 L17,7 L7,11", "M4,21 H14"),
        "snow" to icon("snow", "M12,3 V21", "M4.2,7.5 L19.8,16.5", "M4.2,16.5 L19.8,7.5", "M9.5,4.5 L12,6.5 L14.5,4.5", "M9.5,19.5 L12,17.5 L14.5,19.5"),
        "skate" to icon("skate", "M5,4 H11 V11 L18,12.5 A2.5,2.5 0 0,1 20,15 V17 H5 Z", "M4,20.5 H20", "M8,17 V20.5", "M17,17 V20.5"),
        "rollerskate" to icon("rollerskate", "M5,3 H11 V10 L18,11.5 A2.5,2.5 0 0,1 20,14 V16 H5 Z", c(8.5f, 19.5f, 1.8f), c(16.5f, 19.5f, 1.8f)),
        "skateboard" to icon("skateboard", "M2,11 Q3,13 5,13 H19 Q21,13 22,11", c(7f, 16.5f, 1.8f), c(17f, 16.5f, 1.8f)),
        "surfboard" to icon("surfboard", "M12,2 C16.5,6 16.5,18 12,22 C7.5,18 7.5,6 12,2 Z", "M12,14 V19"),
        "kayak" to icon("kayak", "M2,14 C7,18 17,18 22,14 C17,11.5 7,11.5 2,14 Z", "M6,5 L18,21", "M4.5,6.5 L7.5,3.5", "M16.5,22 L19.5,19.5"),
        "note" to icon("note", "M9,18 V5 L19,3 V16", c(6.5f, 18f, 2.5f), c(16.5f, 16f, 2.5f)),
        "stretch" to icon("stretch", c(12f, 4.5f, 1.6f), "M12,8 V15", "M6,3 L12,9.5 L18,3", "M12,15 L7,21", "M12,15 L17,21"),
        "heart" to heart,
    )

    fun of(type: ActivityType): ImageVector = byKey[type.icon] ?: heart

    val Check = icon("check", "M5,12.5 L10,17.5 L19,7")
}

/** An activity type's icon, centred in a [size] box and tinted like body text. */
@Composable
fun ActivityIcon(type: ActivityType, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Icon(ActivityIcons.of(type), contentDescription = type.label, modifier = Modifier.size(size))
    }
