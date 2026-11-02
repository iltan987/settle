package com.iltan.settle.data.media

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a photo was taken, in words: [name] for the viewer's subtitle ("Kaçkar Mountains"),
 * [region] after it in Details, and [address] for Details' location row.
 */
data class Place(val name: String, val region: String?, val address: String)

/** "Ayder Yaylası, Çamlıhemşin, Rize": the neighbourhood, district and province. */
private const val ADDRESS_PARTS = 3

/** The place an address describes, from the neighbourhood up to the province. */
fun placeOf(address: Address): Place? {
    val parts =
        listOfNotNull(
                address.subLocality,
                address.locality,
                address.subAdminArea,
                address.adminArea,
                address.countryName,
            )
            .filter { it.isNotBlank() }
            .distinct()
    val name = parts.firstOrNull() ?: return null
    return Place(
        name = name,
        region = parts.getOrNull(1)?.let { address.adminArea?.takeIf { it != name } ?: it },
        address = parts.take(ADDRESS_PARTS).joinToString(", "),
    )
}

/**
 * Names places with the platform's geocoder, which on the phone is Google's location service: the
 * coordinates of the photo on screen leave the phone, nothing else does. Nearby photos share one
 * answer, kept for the session, so a burst of shots costs one request.
 */
class Places(private val context: Context) {
    private val lock = Mutex()
    private val known = LinkedHashMap<String, Deferred<Place?>>(16, 0.75f, true)

    suspend fun place(latitude: Double, longitude: Double, locale: Locale): Place? {
        if (!Geocoder.isPresent()) return null
        // About 100 m: the same place for every photo of a walk around one spot.
        val key = "%.3f,%.3f,%s".format(Locale.ROOT, latitude, longitude, locale.toLanguageTag())
        val (answer, mine) =
            lock.withLock {
                known[key]?.let { it to false }
                    ?: CompletableDeferred<Place?>().also { known[key] = it }.to(true)
            }
        if (!mine) return answer.await()
        val result = withTimeoutOrNull(TIMEOUT_MILLIS) { lookUp(latitude, longitude, locale) }
        lock.withLock {
            // A failed request is asked again next time; an answer, even "nothing here", is kept.
            if (result == null) known.remove(key)
            while (known.size > MAX_PLACES) known.remove(known.keys.first())
        }
        val place = result?.getOrNull()
        (answer as CompletableDeferred).complete(place)
        return place
    }

    /** Null when the geocoder failed; a null place when it knows no name for the spot. */
    private suspend fun lookUp(latitude: Double, longitude: Double, locale: Locale) =
        suspendCancellableCoroutine<Result<Place?>?> { continuation ->
            Geocoder(context, locale)
                .getFromLocation(
                    latitude,
                    longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val place = addresses.firstOrNull()?.let(::placeOf)
                            if (continuation.isActive) continuation.resume(Result.success(place))
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w("Places", "Geocoder failed: $errorMessage")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
        }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val MAX_PLACES = 200
    }
}
