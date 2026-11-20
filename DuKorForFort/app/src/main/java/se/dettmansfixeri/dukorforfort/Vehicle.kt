package se.dettmansfixeri.dukorforfort

/**
 * Fordon man kan välja i appen.
 *
 * [codes] är Trafikverkets fordonskoder (dataproduktspecifikation Hastighetsgräns) som
 * fordonet omfattas av – inklusive överordnade begrepp som "bil" (10),
 * "motordrivna fordon" (170) och "fordon" (40).
 *
 * [heavy] = totalvikt över 3,5 ton; avvikelser "fordon över X ton" räknas då som gällande.
 */
enum class Vehicle(
    val label: String,
    val codes: Set<Int>,
    val heavy: Boolean = false,
    /** Generell högsta hastighet enligt trafikförordningen 4 kap. 20 §, utan släp. */
    private val cap: Int? = null,
) {
    PERSONBIL("Personbil", setOf(210, 10, 170, 40)),
    HUSBIL("Husbil över 3,5 ton", setOf(49, 10, 170, 40), heavy = true),
    MOTORCYKEL("Motorcykel", setOf(150, 170, 40)),
    LATT_LASTBIL("Lätt lastbil", setOf(100, 90, 10, 170, 40)),
    TUNG_LASTBIL("Tung lastbil", setOf(280, 90, 10, 170, 40), heavy = true),
    BUSS_BALTE("Buss med bälten", setOf(20, 10, 170, 40), heavy = true, cap = 100),
    BUSS("Buss utan bälten", setOf(20, 10, 170, 40), heavy = true, cap = 90),
    MOPED("Moped klass I", setOf(130, 120, 170, 40), cap = 45),
    A_TRAKTOR("A-traktor/EPA", setOf(270, 170, 40), cap = 30);

    /**
     * Fordonets egen högsta hastighet på en väg skyltad [signed] km/h.
     * Tung lastbil får köra 90 på motorväg och motortrafikled, annars 80. Vägtypen finns
     * inte i datan, så vägar skyltade 100 eller mer räknas som motorväg/motortrafikled.
     */
    fun cap(signed: Int, trailer: Boolean): Int? {
        var c = if (this == TUNG_LASTBIL) (if (signed >= 100) 90 else 80) else cap
        if (trailer) c = minOf(c ?: TRAILER_MAX, TRAILER_MAX)
        return c
    }

    /** Fordonskoder inklusive "motordrivet fordon med släp" (160) när släp är tillkopplat. */
    fun codes(trailer: Boolean): Set<Int> = if (trailer) codes + 160 else codes

    companion object {
        const val TRAILER_MAX = 80
        fun fromName(name: String?): Vehicle = entries.firstOrNull { it.name == name } ?: PERSONBIL
    }
}
