package com.example.util

import com.example.BuildConfig

object VersionUtils {
    val CURRENT_APP_VERSION: String = BuildConfig.VERSION_NAME

    /**
     * Compare version strings formatted as x.y.z (e.g. "1.0.0" vs "1.0.1").
     * Returns:
     *   < 0 if a < b
     *   == 0 if a == b
     *   > 0 if a > b
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val valA = pa.getOrElse(i) { 0 }
            val valB = pb.getOrElse(i) { 0 }
            if (valA != valB) {
                return valA - valB
            }
        }
        return 0
    }
}
