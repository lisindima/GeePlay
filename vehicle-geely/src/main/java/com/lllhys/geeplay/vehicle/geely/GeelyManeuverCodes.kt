package com.lllhys.geeplay.vehicle.geely

object GeelyManeuverCodes {
    fun turnId(appleType: Int, backend: GeelyNavigationBackend, originalOneOsIcons: Boolean): Int {
        if (backend == GeelyNavigationBackend.ONE_OS && originalOneOsIcons) return appleType.coerceIn(0, 255)
        return when (appleType) {
            0, 51 -> 0
            1, 20 -> 102
            2, 21 -> 103
            3, 52, 53 -> 109
            4, 18, 26 -> 108
            5 -> 120
            6, 7 -> 15
            8 -> 12
            9 -> 13
            10, 24, 25, 27 -> 115
            11 -> 101
            12 -> 22
            13 -> 48
            14 -> 49
            15, 16, 17 -> 18
            19 -> 44
            22 -> 47
            23 -> 46
            in 28..44 -> appleType + 1
            45, 46 -> 45
            47 -> 106
            48 -> 107
            49 -> 104
            50 -> 105
            else -> 0
        }
    }
}
