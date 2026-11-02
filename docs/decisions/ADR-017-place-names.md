# ADR-017: Place names from the platform geocoder

- Status: accepted
- Date: 2026-11-02

## Context
Tidepool's viewer (D1, D5) shows where a photo was taken in words: "18:42 · Kaçkar Mountains" under
the date, and "Ayder Yaylası, Çamlıhemşin, Rize" in Details. Photos only hold coordinates (EXIF GPS,
readable with `ACCESS_MEDIA_LOCATION`). Turning them into names needs a geocoder.

- Android's `Geocoder.getFromLocation(lat, lon, max, GeocodeListener)` (API 33+) is asynchronous
  and needs no key or library. On Google phones and Samsung it is backed by Google's location
  service, so the coordinates leave the phone. `Geocoder.isPresent()` says whether a backend exists.
- An offline gazetteer (GeoNames-style data in the APK) keeps everything on the phone but adds tens
  of megabytes and names villages poorly, which matters most for exactly the places people photograph.
- Settle's privacy policy said data goes only between the phone and the user's Drive.

The owner chose names over coordinates, as long as the cost stays small (2026-11-02).

## Decision
- Settle uses the platform `Geocoder`, in the device's language, for the item on screen only: after
  the pager settles, never for items swiped past or in the background.
- Coordinates are rounded to about 100 m (three decimals) for the cache key; nearby photos share one
  answer, kept in memory for the session (`Places`, at most 200 places). A failed request is asked
  again next time; "no name here" is kept.
- Until a name arrives, or when a photo has no location, the subtitle shows the folder instead.
- No map thumbnail: the location row opens the user's maps app at the coordinates.
- The privacy policy says that the coordinates of photos being viewed go to Google's location
  service to name the place, and nothing else does.

## Consequences
- A few hundred bytes per new place, only while the viewer is open; no battery cost in the
  background.
- Without network or a geocoder backend, the viewer still works and shows the folder.
- If a later version wants this offline or opt-out, `Places` is the one class to change; a
  setting would go with it.
