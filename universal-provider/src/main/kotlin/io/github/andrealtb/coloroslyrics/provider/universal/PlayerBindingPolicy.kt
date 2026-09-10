package io.github.andrealtb.coloroslyrics.provider.universal

object PlayerBindingPolicy {
    val OFFICIAL_BLOCKED = setOf(
        "com.heytap.music",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.kugou.android.lite",
        "com.netease.cloudmusic",
        "com.hihonor.cloudmusic"
    )

    val SPECIAL_BLOCKED = setOf(
        "cn.kuwo.player"
    )

    val BLOCKED: Set<String> = OFFICIAL_BLOCKED + SPECIAL_BLOCKED

    fun isBlocked(packageName: String): Boolean = packageName in BLOCKED

    fun blockReason(packageName: String): String? = when (packageName) {
        in OFFICIAL_BLOCKED -> "禁用（已有官方 Provider）"
        in SPECIAL_BLOCKED -> "禁用（需专项适配）"
        else -> null
    }

    fun sanitize(packages: Set<String>): Set<String> = packages.filterNot(::isBlocked).toSet()
}
