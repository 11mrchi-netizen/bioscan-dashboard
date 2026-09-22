package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.data.model.CalendarAttachmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpxSourceResolutionTest {

    @Test
    fun testResolveGpxDriveFileId_prefersRealAttachment() {
        val attachments = listOf(CalendarAttachmentRow(fileId = "abc123", mimeType = "application/gpx+xml", title = "route.gpx"))

        val resolved = resolveGpxDriveFileId(attachments, description = "https://drive.google.com/file/d/zzz999/view")

        assertEquals("abc123", resolved?.driveFileId)
        assertEquals(GpxSourceKind.CALENDAR_ATTACHMENT, resolved?.source)
    }

    @Test
    fun testResolveGpxDriveFileId_acceptsGpxTitleWithImpreciseMimeType() {
        val attachments = listOf(CalendarAttachmentRow(fileId = "abc123", mimeType = "application/octet-stream", title = "Saturday Long Run.gpx"))
        val resolved = resolveGpxDriveFileId(attachments, description = null)
        assertEquals("abc123", resolved?.driveFileId)
    }

    @Test
    fun testResolveGpxDriveFileId_fallsBackToDescriptionLink() {
        val resolved = resolveGpxDriveFileId(attachments = null, description = "Route: https://drive.google.com/file/d/zzz999/view?usp=sharing")

        assertEquals("zzz999", resolved?.driveFileId)
        assertEquals(GpxSourceKind.DESCRIPTION_FALLBACK, resolved?.source)
    }

    @Test
    fun testResolveGpxDriveFileId_ignoresNonGpxAttachmentAndFallsBack() {
        val attachments = listOf(CalendarAttachmentRow(fileId = "notes123", mimeType = "application/pdf", title = "directions.pdf"))
        val resolved = resolveGpxDriveFileId(attachments, description = "https://drive.google.com/open?id=zzz999")

        assertEquals("zzz999", resolved?.driveFileId)
        assertEquals(GpxSourceKind.DESCRIPTION_FALLBACK, resolved?.source)
    }

    @Test
    fun testResolveGpxDriveFileId_nothingResolvesIsNull() {
        assertNull(resolveGpxDriveFileId(attachments = null, description = "No route linked yet."))
    }
}
