package se.dettmansfixeri.dukorforfort

import kotlin.math.*

/**
 * WGS84 (GPS) -> SWEREF 99 TM, Gauss-Krüger enligt Lantmäteriets formler.
 * Returnerar (east, north) i meter.
 */
object Sweref99 {
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101
    private const val LON0 = 15.0
    private const val K0 = 0.9996
    private const val FN = 0.0
    private const val FE = 500000.0

    fun fromWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        val e2 = F * (2.0 - F)
        val n = F / (2.0 - F)
        val aRoof = A / (1.0 + n) * (1.0 + n * n / 4.0 + n.pow(4) / 64.0)

        val cA = e2
        val cB = (5.0 * e2 * e2 - e2.pow(3)) / 6.0
        val cC = (104.0 * e2.pow(3) - 45.0 * e2.pow(4)) / 120.0
        val cD = (1237.0 * e2.pow(4)) / 1260.0

        val b1 = n / 2.0 - 2.0 * n * n / 3.0 + 5.0 * n.pow(3) / 16.0 + 41.0 * n.pow(4) / 180.0
        val b2 = 13.0 * n * n / 48.0 - 3.0 * n.pow(3) / 5.0 + 557.0 * n.pow(4) / 1440.0
        val b3 = 61.0 * n.pow(3) / 240.0 - 103.0 * n.pow(4) / 140.0
        val b4 = 49561.0 * n.pow(4) / 161280.0

        val phi = Math.toRadians(lat)
        val dl = Math.toRadians(lon) - Math.toRadians(LON0)
        val s = sin(phi)
        val phiStar = phi - s * cos(phi) * (cA + cB * s * s + cC * s.pow(4) + cD * s.pow(6))
        val xi = atan(tan(phiStar) / cos(dl))
        val eta = atanh(cos(phiStar) * sin(dl))

        val north = K0 * aRoof * (xi +
                b1 * sin(2 * xi) * cosh(2 * eta) +
                b2 * sin(4 * xi) * cosh(4 * eta) +
                b3 * sin(6 * xi) * cosh(6 * eta) +
                b4 * sin(8 * xi) * cosh(8 * eta)) + FN
        val east = K0 * aRoof * (eta +
                b1 * cos(2 * xi) * sinh(2 * eta) +
                b2 * cos(4 * xi) * sinh(4 * eta) +
                b3 * cos(6 * xi) * sinh(6 * eta) +
                b4 * cos(8 * xi) * sinh(8 * eta)) + FE
        return east to north
    }
}
