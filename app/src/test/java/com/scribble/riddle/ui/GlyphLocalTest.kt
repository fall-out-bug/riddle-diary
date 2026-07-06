package com.scribble.riddle.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphLocalTest {
    @Test
    fun first_glyph_at_start_is_zero() {
        assertEquals(0f, glyphLocal(0f, 0, 4), 0f)
    }

    @Test
    fun third_glyph_halfway_at_progress_0_625() {
        assertEquals(0.5f, glyphLocal(0.625f, 2, 4), 0.0001f)
    }

    @Test
    fun clamps_before_glyph_window() {
        assertEquals(0f, glyphLocal(0.1f, 3, 4), 0f)
    }

    @Test
    fun full_progress_completes_last_glyph() {
        assertEquals(1f, glyphLocal(1f, 3, 4), 0f)
    }
}
