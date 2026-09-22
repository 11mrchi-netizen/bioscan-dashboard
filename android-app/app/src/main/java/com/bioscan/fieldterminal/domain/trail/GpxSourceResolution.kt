package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.data.model.CalendarAttachmentRow
import com.bioscan.fieldterminal.domain.parseGpxLink

// DAV-146. Primary: a real Calendar attachment naming a Drive GPX file.
// Fallback: the existing description-URL regex (domain/NextSession.kt's
// parseGpxLink, already shipped for the Map tab), tried only when no
// attachment resolves -- exactly the priority this ticket's own text asks
// for ("prefer explicit attachments... keep fuzzy matching as an optional
// fallback").

private const val GPX_MIME_TYPE = "application/gpx+xml"

enum class GpxSourceKind { CALENDAR_ATTACHMENT, DESCRIPTION_FALLBACK }

data class ResolvedGpxSource(val driveFileId: String, val source: GpxSourceKind)

fun resolveGpxDriveFileId(attachments: List<CalendarAttachmentRow>?, description: String?): ResolvedGpxSource? {
    val attachment = attachments?.firstOrNull { isGpxAttachment(it) }
    if (attachment?.fileId != null) {
        return ResolvedGpxSource(attachment.fileId, GpxSourceKind.CALENDAR_ATTACHMENT)
    }

    val fallbackFileId = parseGpxLink(description)?.let { extractDriveFileId(it) }
    return fallbackFileId?.let { ResolvedGpxSource(it, GpxSourceKind.DESCRIPTION_FALLBACK) }
}

// A real Calendar attachment sometimes carries an imprecise MIME type
// (e.g. a generic octet-stream) depending on how it was uploaded, so a
// .gpx-named title is also accepted as a real signal, not just the exact
// MIME string.
private fun isGpxAttachment(attachment: CalendarAttachmentRow): Boolean =
    attachment.mimeType == GPX_MIME_TYPE || attachment.title?.endsWith(".gpx", ignoreCase = true) == true

// Same two share-link shapes data/MapRepository.kt's own (private, class-
// scoped) extractDriveFileId() already handles -- duplicated rather than
// exposed as a shared util, since it's a small, stable regex pair rather
// than logic likely to drift between the two call sites.
private fun extractDriveFileId(url: String): String? {
    Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(url)?.let { return it.groupValues[1] }
    Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.let { return it.groupValues[1] }
    return null
}
