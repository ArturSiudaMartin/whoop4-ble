package dev.whoop4.bloop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original stroke icons for the bottom navigation (24×24 viewport). */
object BloopIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Home = icon("home", "M4,11 L12,4 L20,11", "M6,10 V20 H18 V10")
    val Recovery = icon("recovery", "M16.5,5.2 A8,8 0 1,1 12,4")
    val Sleep = icon("sleep", "M19,14.5 A8,8 0 1,1 9.5,5 A6.5,6.5 0 0,0 19,14.5 Z")
    val Strain = icon("strain", "M13,3 L5,13 H11 L10,21 L19,10 H13 Z")
    val Live = icon("live", "M3,12 H7 L9.5,6 L14,18 L16.5,12 H21")
    val Back = icon("back", "M15,5 L8,12 L15,19")
    /** Three sliders: device & app settings. */
    val Settings = icon(
        "settings",
        "M4,6 H20", "M4,12 H20", "M4,18 H20",
        "M9,4 V8", "M15,10 V14", "M7,16 V20",
    )
}
