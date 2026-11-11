package com.atakmap.coremap.maps.coords;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * MGRS and UTM conversions. Reference strings are from GeographicLib
 * GeoConvert (exact to a few nanometers, truncated to 1 m).
 */
public class MGRSPointTest {

    private static double[] decode(String mgrs) {
        return MGRSPoint.decodeString(mgrs, Ellipsoid.WGS_84, null)
                .toLatLng(null);
    }

    @Test
    public void testForward() {
        assertEquals("31VDC0766362738", new MGRSPoint(56.5, 1.5).toString());
        assertEquals("31VDG9442251415", new MGRSPoint(60, 2.9).toString());
        // wide Norway zone, true northing 7032505.9992
        assertEquals("32VKR6639432505",
                new MGRSPoint(63.344656283826, 4.32988630647).toString());
        // wide Svalbard zone, true northing 8737673.9995
        assertEquals("31XEH8654537673",
                new MGRSPoint(78.684344083414, 6.953943375402).toString());
        // true easting 821099.9967: inside square 21099, not 21100
        assertEquals("01LHC2109928745",
                new MGRSPoint(-16.0, -174.000000001).toString());
    }

    @Test
    public void testDecodeZone31V() {
        // the centre of 31V (3 E) used to be re-zoned to 32 by the Norway
        // rule, so 31V references decoded hundreds of km away
        double[] ll = decode("31VDC0766362738");
        assertEquals(56.5, ll[0], 1e-4);
        assertEquals(1.5, ll[1], 1e-4);
        ll = decode("31VDG9442251415");
        assertEquals(60.0, ll[0], 1e-4);
        assertEquals(2.9, ll[1], 1e-4);
    }

    @Test
    public void testTypedReferenceIsShownUnchanged() {
        String[] typed = {
                "32VJN7639779265", "32VKR1256361703", "37XDM1248708193",
                "31VDC0766362738", "18SUJ2693805973", "31VDG9442251415"
        };
        for (String s : typed) {
            double[] ll = decode(s);
            assertEquals(s, new MGRSPoint(ll[0], ll[1]).toString());
        }
    }

    @Test
    public void testFromLatLngFillsOut() {
        MutableMGRSPoint out = new MutableMGRSPoint(0d, 0d);
        MGRSPoint r = MGRSPoint.fromLatLng(Ellipsoid.WGS_84, 38.8856,
                -76.9953, out);
        assertSame(out, r);
        assertEquals("18SUJ2693805973", out.toString());
    }

    @Test
    public void testAntimeridian() {
        // 180 is the same meridian as -180, in zone 1 (there is no zone 61)
        assertEquals("01NAA6602100000", new MGRSPoint(0, 180).toString());
        assertEquals("01TBK6355387329", new MGRSPoint(45, 180).toString());
        assertEquals("01N", UTMPoint.getZoneDescriptorAt(0, 180));
    }

    @Test
    public void testUTMAccuracy() {
        UTMPoint u = UTMPoint.fromLatLng(Ellipsoid.WGS_84, 63.344656283826,
                4.32988630647, null);
        // GeographicLib values are for WGS 84 with f = 1/298.257223563.
        // Ellipsoid.WGS_84 is defined by b = 6356752.3142, which moves the
        // northing here by about 5e-5 m, so allow 1e-4 m.
        assertEquals(266394.732062, u.getEasting(), 1e-4);
        assertEquals(7032505.999214, u.getNorthing(), 1e-4);
        double[] ll = u.toLatLng(null);
        assertEquals(63.344656283826, ll[0], 1e-10);
        assertEquals(4.32988630647, ll[1], 1e-10);
    }
}
