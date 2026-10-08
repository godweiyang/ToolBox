package com.example.videodownloader

internal data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?
)

/** SemVer-like comparison for v1.9.26 / 1.10 / 2.0.0-beta tags. */
internal object AppVersions {
    private fun parts(value: String): List<Int> {
        val cleaned = value.trim().removePrefix("v").removePrefix("V")
        return cleaned.split('.', '-', '+').takeWhile { token -> token.all(Char::isDigit) }
            .mapNotNull(String::toIntOrNull)
    }

    fun compare(left: String, right: String): Int {
        val a = parts(left); val b = parts(right)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val diff = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(remote: String, current: String): Boolean = compare(remote, current) > 0
}
