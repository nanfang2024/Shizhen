package com.framepick.app.worker

import com.framepick.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMimePolicyTest {
    @Test
    fun `octet stream MP3 is saved as system audio`() {
        assertEquals(
            "audio/mpeg",
            DownloadMimePolicy.choose(
                mediaType = MediaType.AUDIO.name,
                extension = "mp3",
                responseType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun `specific server audio MIME is preserved`() {
        assertEquals(
            "audio/mp4",
            DownloadMimePolicy.choose(
                mediaType = MediaType.AUDIO.name,
                extension = "m4a",
                responseType = "audio/mp4; charset=binary",
            ),
        )
    }
}
