package com.sansfile.app.service.custom.locality;

/**
 * Positions GPS (degrés décimaux) : validité et distance à vol d'oiseau.
 */
public final class Geo {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private Geo() {}

    /** Latitude et longitude présentes, finies et dans leurs bornes (0, 0 : position absente, pas le golfe de Guinée). */
    public static boolean valid(Double latitude, Double longitude) {
        return (
            latitude != null &&
            longitude != null &&
            Double.isFinite(latitude) &&
            Double.isFinite(longitude) &&
            Math.abs(latitude) <= 90 &&
            Math.abs(longitude) <= 180 &&
            !(latitude == 0 && longitude == 0)
        );
    }

    /** Distance à vol d'oiseau en kilomètres (formule de haversine). */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
