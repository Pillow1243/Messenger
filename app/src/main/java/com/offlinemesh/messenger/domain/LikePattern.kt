package com.offlinemesh.messenger.domain

/**
 * Builds the argument of a SQL `LIKE ... ESCAPE '\\'` clause for "contains this text".
 *
 * What the user typed is data, never a pattern: `%`, `_` and the escape character itself are
 * escaped, so searching for `50%` finds "50% off" and not every message containing "50".
 *
 * Every query that receives one of these patterns must declare the same single escape character.
 * SQLite rejects an `ESCAPE` argument that is not exactly one character - at run time, on the
 * first row it compares - so the DAO queries are covered by an instrumentation test
 * (`LocalSearchInstrumentedTest`), not only by the unit test of this function.
 */
object LikePattern {
    const val ESCAPE = '\\'

    fun contains(text: String): String = "%" + escape(text) + "%"

    fun escape(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            if (c == ESCAPE || c == '%' || c == '_') append(ESCAPE)
            append(c)
        }
    }
}
