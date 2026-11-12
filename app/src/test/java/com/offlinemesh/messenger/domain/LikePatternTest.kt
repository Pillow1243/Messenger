package com.offlinemesh.messenger.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LikePatternTest {

    @Test
    fun plainTextIsOnlyWrapped() {
        assertEquals("%hello world%", LikePattern.contains("hello world"))
    }

    @Test
    fun wildcardsBecomeLiterals() {
        assertEquals("%50\\% off%", LikePattern.contains("50% off"))
        assertEquals("%a\\_b%", LikePattern.contains("a_b"))
    }

    @Test
    fun theEscapeCharacterIsEscapedFirst() {
        // Otherwise `\%` typed by the user would turn back into a wildcard.
        assertEquals("%C:\\\\dir%", LikePattern.contains("C:\\dir"))
        assertEquals("%\\\\\\%%", LikePattern.contains("\\%"))
    }

    @Test
    fun theEscapeIsOneCharacter() {
        assertEquals(1, LikePattern.ESCAPE.toString().length)
    }
}
