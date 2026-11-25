package com.cascadiacollections.bauhaus.ui

import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import org.junit.Test

class PreviewMaxWidthTest {

    @Test
    fun `a wide artwork on a phone held sideways is narrowed until it fits`() {
        // The content area of a Pixel 10 in landscape is about 299 dp tall.
        val width = previewMaxWidth(viewportHeight = 299.dp, aspectRatio = 1.5f)

        assertThat(width).isEqualTo(340.5.dp)
        // Card height: the artwork at that width, plus the padding and caption.
        assertThat(width / 1.5f + PREVIEW_CHROME_HEIGHT).isEqualTo(299.dp)
        assertThat(width).isLessThan(CONTENT_MAX_WIDTH)
    }

    @Test
    fun `an upright phone keeps the full column width for ordinary artworks`() {
        // About 411 x 760 dp of content; the column is 379 dp wide inside its padding.
        assertThat(previewMaxWidth(viewportHeight = 760.dp, aspectRatio = 1.5f)).isGreaterThan(379.dp)
        assertThat(previewMaxWidth(viewportHeight = 760.dp, aspectRatio = 0.75f)).isGreaterThan(379.dp)
    }

    @Test
    fun `a tall artwork on an upright phone is narrowed until it fits`() {
        assertThat(previewMaxWidth(viewportHeight = 760.dp, aspectRatio = 0.4f)).isEqualTo(275.2.dp)
    }

    @Test
    fun `the preview never shrinks below its minimum width`() {
        assertThat(previewMaxWidth(viewportHeight = 299.dp, aspectRatio = 0.4f)).isEqualTo(MIN_PREVIEW_WIDTH)
        assertThat(previewMaxWidth(viewportHeight = 40.dp, aspectRatio = 1.5f)).isEqualTo(MIN_PREVIEW_WIDTH)
    }
}
