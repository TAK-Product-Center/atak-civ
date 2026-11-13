
package com.atakmap.coremap.conversions;


import gov.tak.test.KernelJniTest;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

public class EGM96Test extends KernelJniTest {

    @Test
    public void getOffset() {

        // limited by the precision of https://earth-info.nga.mil/GandG/update/index.php?dir=wgs84&action=egm96-geoid-calc
        Assert.assertEquals(-32.894001, EGM96.getOffset(42, -76), .001);
        Assert.assertEquals(10.717000, EGM96.getOffset(-42, -76), .001);
        Assert.assertEquals(20.927000, EGM96.getOffset(-42, 76), .001);
        Assert.assertEquals(-37.044998, EGM96.getOffset(42, 76), .001);
        Assert.assertEquals(17.162000, EGM96.getOffset(0, 0), .001);
        Assert.assertEquals(23.99, EGM96.getOffset(-22.99630, 134.99740), .001);
        Assert.assertEquals(39.534, EGM96.getOffset(22.99630, 134.99740), .001);

        Assert.assertTrue(Double.isNaN(EGM96.getOffset(91, 361)));
    }

    @Test
    public void getOffsetWestOfPrimeMeridian() {
        // 0.25 W to 0 used to interpolate against the column at 180 E
        // (extrapolating by a factor of ~719, errors of km); the geoid is
        // continuous across 0, but really slopes by up to ~1.25 m over 0.25
        // degrees (0.59 m at 40.4 N between 0.24 W and 0.01 E)
        for (double lat : new double[] { 51.4778, 0, 40.4, -33.9 }) {
            Assert.assertEquals("jump at 0: " + lat, EGM96.getOffset(lat, 0.001),
                    EGM96.getOffset(lat, -0.001), 0.05);
            double east = EGM96.getOffset(lat, 0.01);
            for (double lon : new double[] { -0.24, -0.1, -0.01 }) {
                Assert.assertEquals("test: " + lat + ", " + lon, east,
                        EGM96.getOffset(lat, lon), 2.0);
            }
            Assert.assertEquals(EGM96.getOffset(lat, -0.1),
                    EGM96.getOffset(lat, 359.9), .001);
        }
        Assert.assertEquals(EGM96.getOffset(10, 10), EGM96.getOffset(10, 370), .001);
        Assert.assertEquals(EGM96.getOffset(10, 10), EGM96.getOffset(10, -350), .001);
        Assert.assertTrue(Double.isNaN(EGM96.getOffset(10, Double.NaN)));
    }

    @Test
    public void get() {
        Assert.assertEquals(50, EGM96.getHAE(new GeoPoint(40, 100, 50)), .001);
        Assert.assertEquals(150, EGM96.getHAE(new GeoPoint(40, 100, 150)),
                .001);
        Assert.assertTrue(Double
                .isNaN(EGM96.getHAE(new GeoPoint(40, 100, GeoPoint.UNKNOWN))));

        Assert.assertEquals(105.321998, EGM96.getMSL(new GeoPoint(40, 100, 50)),
                .001);
        Assert.assertEquals(205.321998,
                EGM96.getMSL(new GeoPoint(40, 100, 150)), .001);
        Assert.assertTrue(Double
                .isNaN(EGM96.getMSL(new GeoPoint(40, 100, GeoPoint.UNKNOWN))));
    }

    @Test
    public void checkMslGdit() {
        // https://earth-info.nga.mil/GandG/update/wgs84/apps/egm84-96_geoid_calc_readme.pdf
        // Geoid heights can be used to convert between orthometric heights (approximately mean sea level)
        // and ellipsoid heights according to the formula
        // h   =   H   +   N
        // h = WGS 84 Ellipsoid height  H = Orthometric height N = WGS 84 Geoid height
        //
        //   hae = msl + offset
        //   hae - offset = msl

        GeoPoint p = new GeoPoint(-22.99630, 134.99740, 0);
        Assert.assertEquals(-23.99, EGM96.getMSL(p), 0.001);

        p = new GeoPoint(22.99630, 134.99740, 0);
        Assert.assertEquals(-39.534, EGM96.getMSL(p), 0.001);

    }

}
