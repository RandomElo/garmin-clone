package fr.foulee.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/** A map that only shows: touches go to the page, so it never blocks the scroll. */
private class StaticMapView(context: Context) : MapView(context) {
    override fun onTouchEvent(event: MotionEvent?) = false
    override fun dispatchTouchEvent(ev: MotionEvent?) = false
}

/** Start (green) and finish (red) dots. */
private class EndsOverlay(private val start: GeoPoint, private val end: GeoPoint, private val startColor: Int, private val endColor: Int) : Overlay() {
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        for ((point, color) in listOf(start to startColor, end to endColor)) {
            val p = projection.toPixels(point, null)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 14f, white)
            paint.color = color
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f, paint)
        }
    }
}

/** OpenStreetMap behind the GPS track. Tiles come from tile.openstreetmap.org, so the credit below the map is required. */
@Composable
fun RouteMap(track: List<Pair<Double, Double>>, modifier: Modifier = Modifier, compact: Boolean = false) {
    val pal = LocalPal.current
    val context = LocalContext.current
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")
        }
    }
    val points = remember(track) { track.map { GeoPoint(it.first, it.second) } }
    // key: a different track means a different run, so a new map (the overlays are set once, at creation)
    Column(modifier) {
        androidx.compose.runtime.key(track.size, track.firstOrNull(), track.lastOrNull()) { AndroidView(
            modifier = if (compact) Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)) else Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
            factory = { ctx ->
                StaticMapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    overlays.add(Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = pal.accent.toArgb()
                        outlinePaint.strokeWidth = if (compact) 6f else 9f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    })
                    overlays.add(EndsOverlay(points.first(), points.last(), pal.good.toArgb(), pal.bad.toArgb()))
                    // The box is only measured after the first layout: fit the track then
                    addOnFirstLayoutListener { _, _, _, _, _ ->
                        zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, if (compact) 24 else 60)
                    }
                }
            },
            onRelease = { it.onDetach() },
        ) }
        // Thumbnails share one credit at the bottom of their page
        if (!compact) Muted("© contributeurs OpenStreetMap", Modifier.padding(top = 4.dp), size = 11)
    }
}
