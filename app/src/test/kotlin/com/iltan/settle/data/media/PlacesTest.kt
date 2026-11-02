package com.iltan.settle.data.media

import android.location.Address
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlacesTest {

    private fun address(
        subLocality: String? = null,
        locality: String? = null,
        subAdminArea: String? = null,
        adminArea: String? = null,
        country: String? = null,
    ) =
        Address(Locale.ROOT).apply {
            this.subLocality = subLocality
            this.locality = locality
            this.subAdminArea = subAdminArea
            this.adminArea = adminArea
            this.countryName = country
        }

    @Test
    fun namesTheNeighbourhoodWithItsProvince() {
        val place =
            placeOf(
                address(
                    subLocality = "Ayder Yaylası",
                    subAdminArea = "Çamlıhemşin",
                    adminArea = "Rize",
                    country = "Türkiye",
                )
            )

        assertEquals(Place("Ayder Yaylası", "Rize", "Ayder Yaylası, Çamlıhemşin, Rize"), place)
    }

    @Test
    fun aCityThatIsItsOwnProvinceIsNamedOnce() {
        val place =
            placeOf(address(subLocality = "Moda", locality = "İstanbul", adminArea = "İstanbul"))

        assertEquals(Place("Moda", "İstanbul", "Moda, İstanbul"), place)
    }

    @Test
    fun onlyACountryStillNamesThePlace() {
        assertEquals(Place("Iceland", null, "Iceland"), placeOf(address(country = "Iceland")))
    }

    @Test
    fun anAddressWithoutNamesIsNoPlace() {
        assertNull(placeOf(address(locality = " ")))
    }
}
