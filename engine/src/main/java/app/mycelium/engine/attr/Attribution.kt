package app.mycelium.engine.attr

import java.security.MessageDigest

/** Pure attribution rules. Precedence: uid_dns / uid_tcp -> guess_fg -> unknown. */
object Attribution {
    const val AID_DNS = 1051

    data class Result(val uid: Int?, val pkg: String?, val how: String, val fg: Int?) {
        fun row() = mapOf("package" to pkg, "how" to how, "fg" to fg)
    }

    /** Uids that never name a real app: the DNS resolver, -1 and INVALID_UID (also -1). */
    fun usableUid(uid: Int) = uid >= 0 && uid != AID_DNS

    /**
     * [uidPkg]: package from the connection owner uid, or null if the lookup failed.
     * [foreground]: current foreground package, or null. [fgKnown]: usage access is granted and tracking runs.
     */
    fun decide(uid: Int, uidPkg: String?, uidHow: String, foreground: String?, fgKnown: Boolean): Result = when {
        uidPkg != null -> Result(uid, uidPkg, uidHow, if (fgKnown) (if (uidPkg == foreground) 1 else 0) else null)
        foreground != null -> Result(null, foreground, "guess_fg", 1)
        else -> Result(null, null, "unknown", null)
    }

    /** First 16 hex chars of sha256(package). */
    fun seed(pkg: String): String =
        MessageDigest.getInstance("SHA-256").digest(pkg.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
