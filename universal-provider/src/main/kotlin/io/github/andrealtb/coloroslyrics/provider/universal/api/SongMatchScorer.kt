package io.github.andrealtb.coloroslyrics.provider.universal.api

/**
 * Scores how well a search candidate names the recording that is playing.
 *
 * The core title decides which work a candidate is; edition labels only refine a core that
 * already matches, so a shared "(From The Vault) (Taylor's Version)" suffix can never lift a
 * different song above the real one. Credited artists are compared as sets, which keeps a duet
 * credited in another order from being rejected outright.
 */
object SongMatchScorer {
    private const val CORE_MATCH_FLOOR = 0.9
    private const val MIN_CONTAINMENT_RATIO = 0.5

    fun calculate(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long?,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long?,
        rankIndex: Int = 0
    ): Double {
        val cleanTargetTitle = cleanText(targetTitle)
        val cleanCandidateTitle = cleanText(candidateTitle)
        if (cleanTargetTitle.isEmpty() || cleanCandidateTitle.isEmpty()) return 0.0

        // An instrumental / karaoke upload never stands in for a sung track.
        if (!hasInstrumentalMark(targetTitle) && hasInstrumentalMark(candidateTitle)) return 0.0

        val coreTarget = cleanText(coreTitle(targetTitle))
        val coreCandidate = cleanText(coreTitle(candidateTitle))
        val titleScore = when {
            coreTarget.isNotEmpty() && coreCandidate.isNotEmpty() -> {
                val coreScore = similarity(coreTarget, coreCandidate)
                if (coreScore >= CORE_MATCH_FLOOR) {
                    // Same work name: the full title decides which edition it is.
                    coreScore * 0.7 + similarity(cleanTargetTitle, cleanCandidateTitle) * 0.3
                } else {
                    // A different work name is never rescued by shared edition wording.
                    coreScore
                }
            }
            else -> similarity(cleanTargetTitle, cleanCandidateTitle)
        }.coerceIn(0.0, 1.0)

        if (titleScore < 0.35) return 0.0

        val targetArtists = creditNames(targetArtist, targetTitle)
        val candidateArtists = creditNames(candidateArtist, candidateTitle)
        var artistScore = 0.5
        if (targetArtists.isNotEmpty() && candidateArtists.isNotEmpty()) {
            artistScore = bestArtistScore(targetArtists, candidateArtists)
            // Only a proven, complete mismatch is a hard rejection.
            if (artistScore < 0.35) return 0.0
        }

        var durationBonus = 0.0
        if (targetDurationMs != null && candidateDurationMs != null &&
            targetDurationMs > 0 && candidateDurationMs > 0
        ) {
            val diffSec = kotlin.math.abs(targetDurationMs - candidateDurationMs) / 1000
            durationBonus = when {
                diffSec <= 3 -> 0.2
                diffSec <= 8 -> 0.1
                diffSec > 35 -> -0.3
                else -> 0.0
            }
        }

        var editionPenalty = 0.0
        if (!hasAlternateTakeMark(targetTitle) && hasAlternateTakeMark(candidateTitle)) {
            editionPenalty = -0.2
        }

        // A song whose duration the player never reported must still be able to reach a confident
        // score, so the title/artist weights absorb the missing duration bonus.
        val durationKnown = targetDurationMs != null && targetDurationMs > 0L
        val titleWeight = if (durationKnown) 0.6 else 0.75
        val artistWeight = if (durationKnown) 0.2 else 0.25
        val rankBonus = when (rankIndex) {
            0 -> 0.03
            1 -> 0.02
            2 -> 0.01
            else -> 0.0
        }

        return (titleScore * titleWeight + artistScore * artistWeight +
            durationBonus + editionPenalty + rankBonus).coerceIn(0.0, 1.0)
    }

    /**
     * The work name without edition labels: bracket groups and trailing "- Remastered" style
     * annotations that name a pressing rather than the song are removed, everything else is kept.
     */
    fun coreTitle(title: String): String {
        var text = title
        text = BRACKET_GROUP.replace(text) { match ->
            val inner = match.groupValues[1]
            if (isEditionLabel(inner)) "" else match.value
        }
        TRAILING_DASH.find(text)?.let { match ->
            if (isEditionLabel(match.groupValues[1])) text = text.substring(0, match.range.first)
        }
        return text.trim().ifEmpty { title.trim() }
    }

    private fun isEditionLabel(value: String): Boolean =
        EDITION_LABEL_REGEX.containsMatchIn(value)

    fun hasInstrumentalMark(title: String): Boolean =
        INSTRUMENTAL_TITLE_REGEX.containsMatchIn(title)

    /**
     * Checks whether bracketed or trailing edition clauses mark an alternate take (Live, Remix,
     * Acoustic, Demo, Cover). Plain words inside the core title itself (such as "Live Forever",
     * "Demons", or "Discover") are part of the work name, never an alternate-take tag.
     */
    fun hasAlternateTakeMark(title: String): Boolean {
        if (BRACKET_GROUP.findAll(title).any { ALTERNATE_TAKE_REGEX.containsMatchIn(it.groupValues[1]) }) {
            return true
        }
        val trailing = TRAILING_DASH.find(title)?.groupValues?.getOrNull(1) ?: return false
        return ALTERNATE_TAKE_REGEX.containsMatchIn(trailing)
    }

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        val contained = longer.contains(shorter) &&
            shorter.length >= 2 &&
            shorter.length.toDouble() / longer.length >= MIN_CONTAINMENT_RATIO
        return if (contained) 0.85 else diceCoefficient(left, right)
    }

    private fun bestArtistScore(target: Set<String>, candidate: Set<String>): Double {
        if (target.any { it in candidate }) return 1.0
        var best = 0.0
        for (left in target) {
            for (right in candidate) {
                val score = similarity(left, right)
                if (score > best) best = score
            }
        }
        return best
    }

    /** Credited names from the artist field plus the guests named inside the title. */
    private fun creditNames(artist: String, title: String): Set<String> {
        val names = linkedSetOf<String>()
        CREDIT_SPLIT.split(artist).forEach { part ->
            val name = cleanText(part)
            if (name.isNotEmpty()) names.add(name)
        }
        FEATURE_GROUP.findAll(title).forEach { match ->
            CREDIT_SPLIT.split(match.groupValues[1]).forEach { part ->
                val name = cleanText(part)
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }

    private fun cleanText(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.length < 2 || s2.length < 2) return 0.0
        val pairs1 = (0 until s1.length - 1).map { s1.substring(it, it + 2) }.toSet()
        val pairs2 = (0 until s2.length - 1).map { s2.substring(it, it + 2) }.toSet()
        val intersection = pairs1.intersect(pairs2).size
        return (2.0 * intersection) / (pairs1.size + pairs2.size)
    }

    private val PURE_MUSIC_PATTERNS = listOf(
        "纯音乐",
        "没有填词",
        "请欣赏",
        "instrumental",
        "no lyrics",
        "off vocal",
        "karaoke"
    )

    private val CREDIT_PREFIXES = listOf(
        "作词", "作曲", "编曲", "制作人", "混音", "母带", "吉他", "贝斯", "鼓", "和声", "合声",
        "录音", "出品", "监制", "统筹", "编程", "词曲",
        "written by", "produced by", "arranged by", "composed by", "mixed by",
        "mastered by", "vocals by", "lyrics by", "music by", "op:", "sp:"
    )

    /**
     * Chinese credit rows such as "词：周杰伦" / "曲：周杰伦" only name credits when a colon
     * follows the label, so a lyric line that merely starts with 词 is still a lyric.
     */
    private val SHORT_CREDIT_LABEL = Regex(
        """^(词曲|词|曲|编曲|制作人?|合声|和声|监制|出品|统筹|编程|吉他|贝斯|鼓|录音|混音|母带|op|sp)\s*:"""
    )

    /**
     * A real song may mention "instrumental" in a line, so the pure-music marker only counts while
     * the lyrics still look empty; three or more sung lines settle the question.
     */
    fun isInstrumentalOrCreditsOnly(lrcText: String): Boolean {
        if (lrcText.isBlank()) return true
        var lyricalCount = 0
        var pureMusicHit = false
        for (line in lrcText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || METADATA_LINE.matches(trimmed)) continue
            val textOnly = trimmed
                .replace(TIMESTAMP, "")
                .replace(WORD_STAMP, "")
                .trim()
            if (textOnly.isEmpty()) continue
            val lower = textOnly.lowercase()
            if (isPureMusicLine(lower)) pureMusicHit = true
            if (!isCreditLine(lower)) {
                lyricalCount++
                if (lyricalCount >= 3) return false
            }
        }
        return pureMusicHit || lyricalCount < 2
    }

    private fun isPureMusicLine(lower: String): Boolean =
        lower.length <= 24 && PURE_MUSIC_PATTERNS.any { lower.contains(it) }

    private fun isCreditLine(lower: String): Boolean {
        val normalized = lower.replace("：", ":")
        if (SHORT_CREDIT_LABEL.containsMatchIn(normalized)) return true
        return CREDIT_PREFIXES.any { prefix ->
            normalized.startsWith(prefix) || normalized.startsWith("$prefix:")
        }
    }

    private val TIMESTAMP = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val WORD_STAMP = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
    private val METADATA_LINE = Regex("""^\[[a-zA-Z]{2,6}:.*]$""")

    private val BRACKET_GROUP = Regex("""[(\[【（]\s*([^)\]】）]*)\s*[)\]】）]""")
    private val TRAILING_DASH = Regex("""\s*[-–—]\s*([^-–—]{0,40})$""")

    private val EDITION_LABEL_REGEX = Regex(
        """\b(?:version|ver\.?|vault|deluxe|explicit|clean|re-?master(?:ed)?|live|acoustic|demo|remix|mix|edit|session|reprise|radio|extended|club|bonus|edition|feat\.?|ft\.?|featuring|with|instrumental|inst\.?|karaoke|off\s+vocal|backing\s+track)\b|伴奏|纯音乐|純音樂|卡拉OK|现场|現場|翻唱|重制|重製|重录|重錄|母带|母帶|试听|試聽|演唱会|演唱會|电台|電台|(?:现场|現場|伴奏|纯音乐|純音樂|混音|重制|重製|重录|重錄|母带|母帶|试听|試聽|演唱会|演唱會|电台|電台|粤语|粵語|国语|國語|英语|英語|日语|日語|韩语|韓語|中文|英文|完整|录音室|錄音室|剧场|劇場|电影|電影|电视|電視|动画|動畫|游戏|遊戲|主题|主題|片头|片頭|片尾|插曲|推广|推廣|纪念|紀念|特别|特別|限定|豪华|豪華|典藏|数字|數字|黑胶|黑膠)版""",
        RegexOption.IGNORE_CASE
    )

    private val INSTRUMENTAL_TITLE_REGEX = Regex(
        """\b(?:instrumental|karaoke|off\s+vocal|backing\s+track|inst)\b\.?|伴奏|纯音乐|純音樂|卡拉OK""",
        RegexOption.IGNORE_CASE
    )

    private val ALTERNATE_TAKE_REGEX = Regex(
        """\b(?:live|remix|acoustic|demo|session|cover)\b|现场|現場|演唱会|演唱會|翻唱""",
        RegexOption.IGNORE_CASE
    )

    private val CREDIT_SPLIT = Regex(
        """\s*(?:[/;,\u3001]|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    private val FEATURE_GROUP = Regex(
        """[(\[【（]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]】）]+)[)\]】）]""",
        RegexOption.IGNORE_CASE
    )
}
