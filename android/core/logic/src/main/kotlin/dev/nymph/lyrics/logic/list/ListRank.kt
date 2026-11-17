package dev.nymph.lyrics.logic.list

/** The base-62 digits of `fractional-indexing` 3.2.0 (`BASE_62_DIGITS`), in ASCII order. */
const val LIST_RANK_DIGITS: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

/** The longest key the server stores (`snippet_placements.list_rank varchar(64)`). */
const val LIST_RANK_MAX_LENGTH: Int = 64

private const val ZERO: Char = '0'

/** The smallest integer part (`"A"` + 26 zeros): never a key on its own. */
private val SMALLEST_INTEGER: String = "A" + ZERO.toString().repeat(26)

/**
 * A placement's position in a canvas's custom list order: a fractional index key (an exact port
 * of `fractional-indexing` 3.2.0 by rocicorp, default base-62 digits). Keys compare by plain
 * character order, which is byte order for these ASCII keys, never by locale.
 *
 * Only built by [parse] (any text `validateOrderKey` accepts that is made of the base-62 digits
 * only and at most [LIST_RANK_MAX_LENGTH] characters, like the server's `validate_rank`) or by [keyBetween] / [keysBetween] (which refuse a result over the limit with
 * [ListRankError.TooLong]), so a [ListRank] is always a key the server accepts.
 */
@JvmInline
value class ListRank private constructor(val value: String) : Comparable<ListRank> {
    override fun compareTo(other: ListRank): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        /** [text] as a key, or null when the server would refuse it (`invalid_list_rank`). */
        fun parse(text: String): ListRank? = if (isValidKey(text)) ListRank(text) else null

        /** For keys this file generated (valid by construction). */
        internal fun generated(text: String): ListRank = ListRank(text)
    }
}

/** Why no key could be made. */
sealed interface ListRankError {
    /** `a >= b`: there is no key strictly between (two devices picked the same key, say). */
    data class NotAscending(val a: ListRank, val b: ListRank) : ListRankError

    /** The key space ran out at one end (the integer part cannot grow or shrink any more). */
    data object Exhausted : ListRankError

    /** The key between is longer than the server stores ([LIST_RANK_MAX_LENGTH]); re-rank the list instead. */
    data class TooLong(val length: Int) : ListRankError
}

/** A key (or keys), or why there is none. */
sealed interface RankResult<out T> {
    data class Ok<out T>(val value: T) : RankResult<T>

    data class Failed(val error: ListRankError) : RankResult<Nothing>
}

/** The value, or null on failure. */
fun <T> RankResult<T>.getOrNull(): T? = (this as? RankResult.Ok)?.value

/** Failure inside the port; turned into a [RankResult.Failed] at the public functions. */
private class RankFailure(val error: ListRankError) : RuntimeException(null, null, false, false)

private inline fun <T> ranked(block: () -> T): RankResult<T> = try {
    RankResult.Ok(block())
} catch (failure: RankFailure) {
    RankResult.Failed(failure.error)
}

/**
 * `validateOrderKey` of 3.2.0, restricted to base-62 characters (as the server's
 * `validate_rank` is) and the server's length limit.
 */
private fun isValidKey(key: String): Boolean {
    if (key.isEmpty() || key.length > LIST_RANK_MAX_LENGTH) return false
    if (key.any { it !in LIST_RANK_DIGITS }) return false
    if (key == SMALLEST_INTEGER) return false
    val intLength = integerLength(key[0]) ?: return false
    if (intLength > key.length) return false
    return key.length == intLength || key.last() != ZERO
}

/** `getIntegerLength`: the integer part's length for its head character, or null for an invalid head. */
private fun integerLength(head: Char): Int? = when (head) {
    in 'a'..'z' -> head - 'a' + 2
    in 'A'..'Z' -> 'Z' - head + 2
    else -> null
}

/** `getIntegerPart` of a valid key. */
private fun integerPart(key: String): String = key.substring(0, checkNotNull(integerLength(key[0])))

/** `midpoint(a, b)`: a fraction strictly between [a] and [b] (null = 1). Neither ends with a zero digit. */
private fun midpoint(a: String, b: String?): String {
    if (!b.isNullOrEmpty()) {
        var n = 0
        while ((a.getOrNull(n) ?: ZERO) == b.getOrNull(n)) n++
        if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
    }
    val digitA = if (a.isNotEmpty()) LIST_RANK_DIGITS.indexOf(a[0]) else 0
    val digitB = if (b != null) LIST_RANK_DIGITS.indexOf(b.getOrElse(0) { ' ' }) else LIST_RANK_DIGITS.length
    return if (digitB - digitA > 1) {
        // Math.round(0.5 * (digitA + digitB)) for these integers.
        LIST_RANK_DIGITS[Math.floorDiv(digitA + digitB + 1, 2)].toString()
    } else if (b != null && b.length > 1) {
        b.substring(0, 1)
    } else {
        LIST_RANK_DIGITS[digitA] + midpoint(a.drop(1), null)
    }
}

/** `incrementInteger`, or null past the largest integer. */
private fun incrementInteger(x: String): String? {
    val head = x[0]
    val digs = x.substring(1).toMutableList()
    var carry = true
    var i = digs.size - 1
    while (carry && i >= 0) {
        val d = LIST_RANK_DIGITS.indexOf(digs[i]) + 1
        if (d == LIST_RANK_DIGITS.length) {
            digs[i] = ZERO
        } else {
            digs[i] = LIST_RANK_DIGITS[d]
            carry = false
        }
        i--
    }
    if (!carry) return head + digs.joinToString("")
    if (head == 'Z') return "a$ZERO"
    if (head == 'z') return null
    val h = head + 1
    if (h > 'a') digs.add(ZERO) else digs.removeAt(digs.size - 1)
    return h + digs.joinToString("")
}

/** `decrementInteger`, or null below the smallest integer. */
private fun decrementInteger(x: String): String? {
    val head = x[0]
    val digs = x.substring(1).toMutableList()
    val last = LIST_RANK_DIGITS.last()
    var borrow = true
    var i = digs.size - 1
    while (borrow && i >= 0) {
        val d = LIST_RANK_DIGITS.indexOf(digs[i]) - 1
        if (d == -1) {
            digs[i] = last
        } else {
            digs[i] = LIST_RANK_DIGITS[d]
            borrow = false
        }
        i--
    }
    if (!borrow) return head + digs.joinToString("")
    if (head == 'a') return "Z$last"
    if (head == 'A') return null
    val h = head - 1
    if (h < 'Z') digs.add(last) else digs.removeAt(digs.size - 1)
    return h + digs.joinToString("")
}

private fun between(a: String?, b: String?): String {
    if (a == null) {
        if (b == null) return "a$ZERO"
        val ib = integerPart(b)
        val fb = b.substring(ib.length)
        if (ib == SMALLEST_INTEGER) return ib + midpoint("", fb)
        if (ib < b) return ib
        return decrementInteger(ib) ?: throw RankFailure(ListRankError.Exhausted)
    }
    if (b == null) {
        val ia = integerPart(a)
        val fa = a.substring(ia.length)
        return incrementInteger(ia) ?: (ia + midpoint(fa, null))
    }
    val ia = integerPart(a)
    val fa = a.substring(ia.length)
    val ib = integerPart(b)
    val fb = b.substring(ib.length)
    if (ia == ib) return ia + midpoint(fa, fb)
    val i = incrementInteger(ia) ?: throw RankFailure(ListRankError.Exhausted)
    if (i < b) return i
    return ia + midpoint(fa, null)
}

private fun bounded(key: String): ListRank {
    if (key.length > LIST_RANK_MAX_LENGTH) throw RankFailure(ListRankError.TooLong(key.length))
    return ListRank.generated(key)
}

private fun checkAscending(a: ListRank?, b: ListRank?) {
    if (a != null && b != null && a >= b) throw RankFailure(ListRankError.NotAscending(a, b))
}

/** `generateKeyBetween(a, b)`: a key strictly between [a] and [b] (null = the open end). */
fun keyBetween(a: ListRank?, b: ListRank?): RankResult<ListRank> = ranked {
    checkAscending(a, b)
    bounded(between(a?.value, b?.value))
}

private fun nBetween(a: String?, b: String?, n: Int): List<String> {
    if (n == 0) return emptyList()
    if (n == 1) return listOf(between(a, b))
    if (b == null) {
        var c = between(a, null)
        val result = mutableListOf(c)
        repeat(n - 1) {
            c = between(c, null)
            result += c
        }
        return result
    }
    if (a == null) {
        var c = between(null, b)
        val result = mutableListOf(c)
        repeat(n - 1) {
            c = between(null, c)
            result += c
        }
        return result.reversed()
    }
    val mid = n / 2
    val c = between(a, b)
    return nBetween(a, c, mid) + c + nBetween(c, b, n - mid - 1)
}

/**
 * `generateNKeysBetween(a, b, n)`: [n] ascending keys strictly between [a] and [b]
 * (`(null, null, n)` ranks a whole list from scratch). [n] must not be negative.
 */
fun keysBetween(a: ListRank?, b: ListRank?, n: Int): RankResult<List<ListRank>> {
    require(n >= 0) { "n must not be negative: $n" }
    return ranked {
        checkAscending(a, b)
        nBetween(a?.value, b?.value, n).map(::bounded)
    }
}

/**
 * The key that puts a new placement on top of a canvas's custom order:
 * `keyBetween(null, min(existing))`, or `keyBetween(null, null)` on an empty canvas. Stored keys
 * that are not valid are ignored; null only when the key space below the minimum is exhausted.
 */
fun topRank(existing: Iterable<String>): ListRank? = keyBetween(null, existing.mapNotNull(ListRank::parse).minOrNull()).getOrNull()

/** [n] keys above every [existing] key, ascending (several placements put on top at once, in order). */
fun topRanks(existing: Iterable<String>, n: Int): List<ListRank>? =
    keysBetween(null, existing.mapNotNull(ListRank::parse).minOrNull(), n).getOrNull()
