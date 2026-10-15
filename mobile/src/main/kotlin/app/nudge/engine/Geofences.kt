package app.nudge.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.nudge.core.Status
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.data.Store
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Keeps one Play Services geofence per saved place that a waiting reminder cares about. */
object Geofences {
    private const val TAG = "NudgeGeo"
    private var registered: Set<String> = emptySet()

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // Play Services fills in the event
    )

    fun neededPlaceIds(): Set<String> = Store.reminders.value.mapNotNull { r ->
        val t = when (r.status) {
            Status.WAITING -> r.trigger
            Status.SNOOZED -> r.snoozeTrigger
            else -> null
        }
        (t as? Trigger.Target)?.takeIf { it.kind == TargetKind.PLACE }?.targetId
    }.toSet()

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun sync(ctx: Context, force: Boolean = false) {
        val places = neededPlaceIds().mapNotNull { Store.target(it) }.filter { it.lat != null && it.lng != null }
        val key = places.map { "${it.id}:${it.lat}:${it.lng}:${it.radiusM}" }.toSet()
        if (!force && key == registered) return
        if (!hasPermission(ctx)) { Log.w(TAG, "no background location permission"); return }
        val client = LocationServices.getGeofencingClient(ctx)
        val p = pi(ctx)
        client.removeGeofences(p).addOnCompleteListener {
            registered = emptySet()
            if (places.isEmpty()) return@addOnCompleteListener
            val fences = places.map {
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat!!, it.lng!!, it.radiusM.coerceAtLeast(100f))
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(0) // already inside doesn't count as arriving
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(req, p)
                    .addOnSuccessListener { registered = key }
                    .addOnFailureListener { e -> Log.e(TAG, "addGeofences failed", e) }
            } catch (e: SecurityException) {
                Log.e(TAG, "geofence permission", e)
            }
        }
    }
}
