
package com.atakmap.coremap.maps.coords;

import com.atakmap.coremap.locale.LocaleUtil;

import java.text.DecimalFormat;

public class UTMPoint
{

    protected int latZone; // [0, 19]
    protected int lngZone; // [0, 59] maps to 1-60
    protected double northing; // absolute northing from equator (Northern hemi) or from 10,000,00
    // meters south of equator (Southern Hemi)
    protected double easting; // easting from central meridian of UTM lng zone
    private static final String _LAT_ZONE_CHARS = "CDEFGHJKLMNPQRSTUVWX";
    private static final DecimalFormat _XY_FORMAT = LocaleUtil
            .getDecimalFormat("0");
    private static final double div_6d = 1d / 6d;
    private static final double div_180_pi = 180d / Math.PI;
    private static final double div_pi_180d = Math.PI / 180d;

    public UTMPoint()
    {
        lngZone = 0;
        latZone = 10;
        easting = 5000000d;
        northing = 0d;
    }

    public UTMPoint(final String zoneDescriptor, final double atEasting,
                    final double atNorthing)
    {
        lngZone = Integer.parseInt(zoneDescriptor.substring(0, 2));
        latZone = _LAT_ZONE_CHARS.indexOf(zoneDescriptor.charAt(2));
        easting = atEasting;
        northing = atNorthing;
    }

    public UTMPoint(UTMPoint utmPoint)
    {
        lngZone = utmPoint.lngZone;
        latZone = utmPoint.latZone;
        easting = utmPoint.easting;
        northing = utmPoint.northing;
    }

    public static UTMPoint decodeString(String utmZone, String easting,
                                        String northing)
    {

        if (utmZone.length() != 3)
            return null;

        try
        {
            return new UTMPoint(utmZone,
                    Double.parseDouble(LocaleUtil.getNaturalNumber(easting)),
                    Double.parseDouble(LocaleUtil.getNaturalNumber(northing)));
        } catch (NumberFormatException nfe)
        {
            return null;
        }

    }

    /**
     * Parse a valid UTM String into the appropriate UTM point.
     * A NumberFormatException may be trown if an invalid utmString is passed
     * in. Examples of valid UTM coordinates would be 17T6300844833438
     *
     * @param utmString in the appropriate format of grid zone designator band identifier
     *                  easting and northing.
     * @param geoRef    can be Ellipsoid.WGS_84
     * @param out       can be null.
     */
    public static UTMPoint decodeString(String utmString)
    {
        utmString = utmString.replace(" ", "").replace("\u200e", "");

        if (!Character.isDigit(utmString.charAt(1)))
            utmString = "0" + utmString;

        String eastingSubstr = "0";
        String northingSubstr = "0";
        if (utmString.length() > 5)
        {
            int compSize = (utmString.length() - 3) / 2;
            if (compSize > 0)
            {
                eastingSubstr = utmString.substring(3, 3 + compSize);
                eastingSubstr = eastingSubstr.toLowerCase(
                        LocaleUtil.getCurrent()).replace("m", "");
                northingSubstr = utmString.substring(3 + compSize);
                northingSubstr = northingSubstr.toLowerCase(
                        LocaleUtil.getCurrent()).replace("m", "");
            }
        }

        try
        {
            return new UTMPoint(utmString.substring(0, 3),
                    Double.parseDouble(
                            LocaleUtil.getNaturalNumber(eastingSubstr)),
                    Double.parseDouble(
                            LocaleUtil.getNaturalNumber(northingSubstr)));
        } catch (NumberFormatException nfe)
        {
            return null;
        }
    }

    public int getLngZone()
    {
        return lngZone;
    }

    public int getLatZone()
    {
        return latZone;
    }

    public String getZoneDescriptor()
    {
        if (latZone < _LAT_ZONE_CHARS.length() && latZone >= 0)
        {
            return (lngZone < 10 ? "0" : "") + lngZone
                    + _LAT_ZONE_CHARS.charAt(latZone);
        } else
            return null;
    }

    public double getNorthing()
    {
        return northing;
    }

    public double getEasting()
    {
        return easting;
    }

    /**
     * Convert a geopoint into a valid UTM coordinate.   This will only provide a valid conversion for
     * values -80.00 < x < 84.00 latitude.  For values not in that range, you will need to use UPS or
     * Universal polar stereographic coordinate system
     */
    public static UTMPoint fromGeoPoint(final GeoPoint point)
    {
        return fromLatLng(Ellipsoid.WGS_84, point.getLatitude(),
                point.getLongitude(), null);
    }

    /**
     * Convert a geopoint into a valid UTM coordinate.   This will only provide a valid conversion for
     * values -80.00 < x < 84.00 latitude.  For values not in that range, you will need to use UPS or
     * Universal polar stereographic coordinate system
     */
    public static UTMPoint fromLatLng(final Ellipsoid geoRef, final double lat,
                                      double lng, MutableUTMPoint out)
    {
        lng = normalizeLongitude(lng);
        int lngZone = (int) ((lng + 180d) / 6d) + 1;
        int latZone = (int) ((lat + 80d) / 8d);

        if (latZone > 19)
        {
            latZone = 19;
        }
        if (latZone < 0)
        {
            latZone = 0;
        }

        switch (latZone)
        {
            case 17:
                if (lng >= 0 && lng < 3)
                {
                    lngZone = 31;
                } else if (lng >= 3 && lng < 12)
                {
                    lngZone = 32;
                }
                break;
            case 19:
                if (lng >= 0)
                {
                    if (lng < 9)
                    {
                        lngZone = 31;
                    } else if (lng < 21)
                    {
                        lngZone = 33;
                    } else if (lng < 33)
                    {
                        lngZone = 35;
                    } else if (lng < 42)
                    {
                        lngZone = 37;
                    }
                }
        }
        return fromLatLng(geoRef, lat, lng, lngZone, latZone, out);
    }

    /**
     * Function converts lat/lon to UTM coordinates in the given zone, using
     * Karney (2011), "Transverse Mercator with an accuracy of a few
     * nanometers" (Krueger series to order n^6). The USGS bulletin 1532
     * series used before were off by up to ~5 cm in wide (Norway/Svalbard)
     * zones, enough to change the last digit of a 1 m MGRS reference.
     */
    static UTMPoint fromLatLng(final Ellipsoid geoRef,
                                       final double lat, final double lng, final int lngZone,
                                       final int latZone, MutableUTMPoint out)
    {

        final Krueger k = Krueger.of(geoRef);

        double lngOrigin = (lngZone - 1) * 6 - 180 + 3;
        double lambda = lng - lngOrigin;
        if (lambda < -180d || lambda >= 180d)
            lambda = normalizeLongitude(lambda);

        final double phi = lat * div_pi_180d;
        final double lam = lambda * div_pi_180d;

        final double tauPrime = k.conformalTau(Math.tan(phi));
        final double cosLambda = Math.cos(lam);
        final double xiPrime = Math.atan2(tauPrime, cosLambda);
        final double etaPrime = asinh(Math.sin(lam)
                / Math.sqrt(tauPrime * tauPrime + cosLambda * cosLambda));

        double xi = xiPrime;
        double eta = etaPrime;
        for (int j = 1; j <= 6; j++)
        {
            xi += k.alpha[j] * Math.sin(2 * j * xiPrime)
                    * Math.cosh(2 * j * etaPrime);
            eta += k.alpha[j] * Math.cos(2 * j * xiPrime)
                    * Math.sinh(2 * j * etaPrime);
        }

        double easting = K0 * k.rectifyingRadius * eta + 500000.0d;
        double northing = K0 * k.rectifyingRadius * xi;

        // southern hemisphere
        if (latZone < 10)
        {
            northing += 10000000.0d;
        }

        UTMPoint r = out;
        if (r == null)
        {
            r = new UTMPoint();
        }

        r.latZone = latZone;
        r.lngZone = lngZone;
        r.easting = easting;
        r.northing = northing;

        return r;
    }

    public GeoPoint toGeoPoint()
    {
        double[] ll = new double[2];
        toLatLng(ll);
        return new GeoPoint(ll[0], ll[1]);
    }

    public static double[] toLatLng(Ellipsoid geoRef, int latZone, int lngZone,
                                    double easting,
                                    double northing, double[] out)
    {
        final Krueger k = Krueger.of(geoRef);

        // remove 500,000 meter offset for longitude
        double x = easting - 500000.0d;
        double y = northing;

        // southern hemi
        if (latZone < 10)
        {
            y -= 10000000.0d;
        }

        double LongOrigin = (lngZone - 1) * 6 - 180 + 3;

        // Karney (2011) eqs 15-22: Krueger series to order n^6
        final double eta = x / (K0 * k.rectifyingRadius);
        final double xi = y / (K0 * k.rectifyingRadius);

        double xiPrime = xi;
        double etaPrime = eta;
        for (int j = 1; j <= 6; j++)
        {
            xiPrime -= k.beta[j] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            etaPrime -= k.beta[j] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }

        final double sinhEtaPrime = Math.sinh(etaPrime);
        final double cosXiPrime = Math.cos(xiPrime);
        final double tauPrime = Math.sin(xiPrime)
                / Math.sqrt(sinhEtaPrime * sinhEtaPrime + cosXiPrime * cosXiPrime);

        // Newton iteration for tau = tan(latitude)
        double tau = tauPrime;
        final double e2 = k.e * k.e;
        for (int i = 0; i < 10; i++)
        {
            final double tauIPrime = k.conformalTau(tau);
            final double delta = (tauPrime - tauIPrime)
                    / Math.sqrt(1 + tauIPrime * tauIPrime)
                    * (1 + (1 - e2) * tau * tau)
                    / ((1 - e2) * Math.sqrt(1 + tau * tau));
            tau += delta;
            if (!(Math.abs(delta) > 1e-12 * Math.max(1d, Math.abs(tau))))
                break;
        }

        double lat = Math.atan(tau) * div_180_pi;
        double lon = LongOrigin
                + Math.atan2(sinhEtaPrime, cosXiPrime) * div_180_pi;

        if (out == null)
        {
            out = new double[2];
        }
        out[0] = lat;
        out[1] = lon;

        return out;
    }

    /** UTM central meridian scale factor */
    private static final double K0 = 0.9996;

    /**
     * Longitude in [-180, 180); 180 is the same meridian as -180 (zone 1).
     */
    private static double normalizeLongitude(double lng)
    {
        if (lng < -180d || lng >= 180d)
        {
            lng = ((lng + 180d) % 360d + 360d) % 360d - 180d;
        }
        return lng;
    }

    private static double asinh(double x)
    {
        double y = Math.abs(x);
        y = Math.log1p(y + y * y / (1 + Math.sqrt(1 + y * y)));
        return x < 0 ? -y : y;
    }

    private static double atanh(double x)
    {
        double y = Math.abs(x);
        y = Math.log1p(2 * y / (1 - y)) / 2;
        return x < 0 ? -y : y;
    }

    /**
     * Krueger series coefficients for one ellipsoid, Karney (2011) eqs 14,
     * 35 and 36. The last one used is cached.
     */
    private static final class Krueger
    {
        private static volatile Krueger last;

        final double semiMajorAxis;
        final double eccentricitySquared;
        final double e;
        final double rectifyingRadius;
        final double[] alpha;
        final double[] beta;

        static Krueger of(Ellipsoid geoRef)
        {
            final double a = geoRef.getSemiMajorAxis();
            final double e2 = geoRef.getFirstEccentricitySquared();
            Krueger k = last;
            if (k == null || k.semiMajorAxis != a
                    || k.eccentricitySquared != e2)
            {
                k = new Krueger(a, e2);
                last = k;
            }
            return k;
        }

        private Krueger(double a, double e2)
        {
            semiMajorAxis = a;
            eccentricitySquared = e2;
            e = Math.sqrt(e2);
            final double f = e2 / (1 + Math.sqrt(1 - e2));
            final double n = f / (2 - f);
            final double n2 = n * n, n3 = n * n2, n4 = n * n3, n5 = n * n4,
                    n6 = n * n5;
            rectifyingRadius = a / (1 + n)
                    * (1 + n2 / 4 + n4 / 64 + n6 / 256);
            alpha = new double[] {
                    0,
                    n / 2 - 2d / 3 * n2 + 5d / 16 * n3 + 41d / 180 * n4
                            - 127d / 288 * n5 + 7891d / 37800 * n6,
                    13d / 48 * n2 - 3d / 5 * n3 + 557d / 1440 * n4
                            + 281d / 630 * n5 - 1983433d / 1935360 * n6,
                    61d / 240 * n3 - 103d / 140 * n4 + 15061d / 26880 * n5
                            + 167603d / 181440 * n6,
                    49561d / 161280 * n4 - 179d / 168 * n5
                            + 6601661d / 7257600 * n6,
                    34729d / 80640 * n5 - 3418889d / 1995840 * n6,
                    212378941d / 319334400 * n6
            };
            beta = new double[] {
                    0,
                    n / 2 - 2d / 3 * n2 + 37d / 96 * n3 - 1d / 360 * n4
                            - 81d / 512 * n5 + 96199d / 604800 * n6,
                    1d / 48 * n2 + 1d / 15 * n3 - 437d / 1440 * n4
                            + 46d / 105 * n5 - 1118711d / 3870720 * n6,
                    17d / 480 * n3 - 37d / 840 * n4 - 209d / 4480 * n5
                            + 5569d / 90720 * n6,
                    4397d / 161280 * n4 - 11d / 504 * n5
                            - 830251d / 7257600 * n6,
                    4583d / 161280 * n5 - 108847d / 3991680 * n6,
                    20648693d / 638668800 * n6
            };
        }

        /** Tangent of the conformal latitude, Karney (2011) eqs 7 and 9 */
        double conformalTau(double tau)
        {
            final double sigma = Math.sinh(e * atanh(e * tau
                    / Math.sqrt(1 + tau * tau)));
            return tau * Math.sqrt(1 + sigma * sigma)
                    - sigma * Math.sqrt(1 + tau * tau);
        }
    }

    public static String getZoneDescriptorAt(double lat, double lng)
    {
        lng = normalizeLongitude(lng);
        int lngZone = (int) ((lng + 180d) * div_6d) + 1;
        int latZone = (int) ((lat + 80d) / 8d);

        if (latZone > 19)
        {
            latZone = 19;
        }
        if (latZone < 0)
        {
            latZone = 0;
        }

        return (lngZone < 10 ? "0" : "") + lngZone
                + _LAT_ZONE_CHARS.charAt(latZone);
    }

    public static int getLatZoneIndex(char zoneChar)
    {
        return _LAT_ZONE_CHARS.indexOf(zoneChar);
    }

    public static double getZoneLatitude(int latZoneIndex)
    {
        return -80d + (latZoneIndex * 8d);
    }

    public static double getZoneLongitude(int zoneNumber)
    {
        return -180 + (zoneNumber - 1) * 6d;
    }

    public static double getZoneLongitudeSpan(int zoneNumber,
                                              int latZoneIndex)
    {
        return 6d;
    }

    public static double getZoneLatitudeSpan(int latZoneIndex)
    {
        return latZoneIndex == 19 ? 12d : 8d;
    }

    public double[] toLatLng(Ellipsoid geoRef, double[] out)
    {
        return toLatLng(geoRef, latZone, lngZone, easting, northing, out);
    }

    public double[] toLatLng(double[] out)
    {
        return toLatLng(Ellipsoid.WGS_84, out);
    }

    public String toString()
    {
        return (lngZone < 10 ? "0" : "") + lngZone +
                _LAT_ZONE_CHARS.charAt(latZone) +
                " " +
                _XY_FORMAT.format(Math.floor(easting)) +
                "mE " +
                _XY_FORMAT.format(Math.floor(northing)) +
                "mN";
    }

    public String getEastingDescriptor()
    {
        return _XY_FORMAT.format(Math.floor(easting));
    }

    public String getNorthingDescriptor()
    {
        return _XY_FORMAT.format(Math.floor(northing));
    }

}
