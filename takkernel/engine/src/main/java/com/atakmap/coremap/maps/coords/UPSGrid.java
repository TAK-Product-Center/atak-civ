package com.atakmap.coremap.maps.coords;

/**
 * Universal Polar Stereographic (UPS) for MGRS north of 84 N and south of
 * 80 S (bands Y and Z in the north, A and B in the south): polar
 * stereographic with scale 0.994 at the pole and a false easting and northing
 * of 2,000,000 m. The forward and inverse use the conformal latitude as in
 * Karney (2011), "Transverse Mercator with an accuracy of a few nanometers".
 */
final class UPSGrid
{
    static final double K0 = 0.994;
    static final double FALSE_EN = 2000000d;
    static final String BANDS = "ABYZ";

    /** 100km column letters for bands A, B, Y and Z */
    private static final String[] COLUMNS = {
            "JKLPQRSTUXYZ", "ABCFGHJKLPQR", "RSTUXYZ", "ABCFGHJ"
    };
    /** 100km row letters for the south (A, B) and north (Y, Z) */
    private static final String[] ROWS = {
            "ABCDEFGHJKLMNPQRSTUVWXYZ", "ABCDEFGHJKLMNP"
    };
    /** first 100km column index (easting / 100km) of bands A, B, Y, Z */
    private static final int[] COLUMN_START = {
            8, 20, 13, 20
    };
    /** first 100km row index (northing / 100km) in the south and north */
    private static final int[] ROW_START = {
            8, 13
    };

    private UPSGrid()
    {
    }

    /** UPS is used at and north of 84 N and south of 80 S */
    static boolean isPolar(double lat)
    {
        return lat >= 84d || lat < -80d;
    }

    static boolean isBand(char c)
    {
        return BANDS.indexOf(Character.toUpperCase(c)) >= 0;
    }

    static int bandIndex(char band)
    {
        return BANDS.indexOf(band);
    }

    static boolean isNorth(char band)
    {
        return band == 'Y' || band == 'Z';
    }

    /** @return { easting, northing } */
    static double[] forward(Ellipsoid geoRef, double lat, double lng)
    {
        final double e = Math.sqrt(geoRef.getFirstEccentricitySquared());
        final double a = geoRef.getSemiMajorAxis();
        final boolean north = lat >= 0;
        final double phi = Math.toRadians(Math.min(90d, Math.abs(lat)));
        final double lam = Math.toRadians(lng);
        final double tauPrime = conformalTau(Math.tan(phi), e);
        // t = tan(pi/4 - chi/2) = sqrt(1 + tau'^2) - tau', without cancellation
        final double t = Double.isInfinite(tauPrime) ? 0d
                : 1d / (Math.sqrt(1 + tauPrime * tauPrime) + tauPrime);
        final double rho = 2 * a * K0 * t / c(e);
        final double x = FALSE_EN + rho * Math.sin(lam);
        final double y = north ? FALSE_EN - rho * Math.cos(lam)
                : FALSE_EN + rho * Math.cos(lam);
        return new double[] {
                x, y
        };
    }

    /** @return { latitude, longitude } */
    static double[] inverse(Ellipsoid geoRef, boolean north, double easting,
            double northing, double[] out)
    {
        final double e = Math.sqrt(geoRef.getFirstEccentricitySquared());
        final double a = geoRef.getSemiMajorAxis();
        final double x = easting - FALSE_EN;
        final double y = north ? FALSE_EN - northing : northing - FALSE_EN;
        final double rho = Math.hypot(x, y);
        double lat = 90d;
        double lng = 0d;
        if (rho > 0)
        {
            final double t = rho * c(e) / (2 * a * K0);
            // tau' = tan(chi) = (1 - t^2) / (2 t)
            lat = Math.toDegrees(Math.atan(
                    tauFromConformal((1 - t * t) / (2 * t), e)));
            lng = Math.toDegrees(Math.atan2(x, y));
        }
        if (!north)
            lat = -lat;
        if (out == null)
            out = new double[2];
        out[0] = lat;
        out[1] = lng;
        return out;
    }

    static char band(boolean north, double easting)
    {
        return BANDS.charAt((north ? 2 : 0) + (easting >= FALSE_EN ? 1 : 0));
    }

    static char column(char band, int columnIndex)
    {
        final int b = bandIndex(band);
        final String cols = COLUMNS[b];
        return cols.charAt(Math.max(0,
                Math.min(cols.length() - 1, columnIndex - COLUMN_START[b])));
    }

    static char row(char band, int rowIndex)
    {
        final int n = isNorth(band) ? 1 : 0;
        final String rows = ROWS[n];
        return rows.charAt(Math.max(0,
                Math.min(rows.length() - 1, rowIndex - ROW_START[n])));
    }

    /** @return 100km column index, or -1 if the letter is not in the band */
    static int columnIndex(char band, char column)
    {
        final int b = bandIndex(band);
        final int i = b < 0 ? -1 : COLUMNS[b].indexOf(column);
        return i < 0 ? -1 : i + COLUMN_START[b];
    }

    /** @return 100km row index, or -1 if the letter is not in the band */
    static int rowIndex(char band, char row)
    {
        final int n = isNorth(band) ? 1 : 0;
        final int i = ROWS[n].indexOf(row);
        return i < 0 ? -1 : i + ROW_START[n];
    }

    private static double c(double e)
    {
        return Math.sqrt(Math.pow(1 + e, 1 + e) * Math.pow(1 - e, 1 - e));
    }

    private static double conformalTau(double tau, double e)
    {
        if (Double.isInfinite(tau))
            return tau;
        final double sigma = Math.sinh(
                e * atanh(e * tau / Math.sqrt(1 + tau * tau)));
        return tau * Math.sqrt(1 + sigma * sigma)
                - sigma * Math.sqrt(1 + tau * tau);
    }

    private static double tauFromConformal(double tauPrime, double e)
    {
        if (Double.isInfinite(tauPrime))
            return tauPrime;
        final double e2 = e * e;
        double tau = tauPrime / (1 - e2);
        for (int i = 0; i < 10; i++)
        {
            final double tauI = conformalTau(tau, e);
            final double delta = (tauPrime - tauI)
                    / Math.sqrt(1 + tauI * tauI)
                    * (1 + (1 - e2) * tau * tau)
                    / ((1 - e2) * Math.sqrt(1 + tau * tau));
            tau += delta;
            if (!(Math.abs(delta) > 1e-12 * Math.max(1d, Math.abs(tau))))
                break;
        }
        return tau;
    }

    private static double atanh(double x)
    {
        double y = Math.abs(x);
        y = Math.log1p(2 * y / (1 - y)) / 2;
        return x < 0 ? -y : y;
    }
}
