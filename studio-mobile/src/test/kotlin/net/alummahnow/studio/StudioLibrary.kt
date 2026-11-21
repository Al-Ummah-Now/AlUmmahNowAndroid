package net.alummahnow.studio

import net.alummahnow.fixtures.BrandChannel

/**
 * The one channel library every Studio frame shows (store, framed and feature-state frames): the brand channel's
 * published videos, one draft and one scheduled video, from the fixture every viewer and Studio module shares
 * ([BrandChannel]), so titles, views, dates and art agree with Home and Watch. Counts agree with the lists.
 */
internal object StudioLibrary {
    /** Newest first, all uploaded in September (before the October analytics). */
    val published = BrandChannel.studioPublished

    val draft = BrandChannel.studioDraft

    /** Goes out in December, after the October analytics window and after the frames' own date. */
    val scheduled = BrandChannel.studioScheduled

    /** Published, scheduled, drafts, processing, failed. */
    val counts = BrandChannel.counts

    val comments = BrandChannel.comments

    val analytics = BrandChannel.analytics
}
