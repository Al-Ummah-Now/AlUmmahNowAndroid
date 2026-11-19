package net.alummahnow.design

import net.alummahnow.design.format.avatarInitial
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as Apple's AvatarInitialTests. */
class AvatarInitialTest {
    @Test fun initialsSkipTheArabicArticle() {
        assertEquals("أ", avatarInitial("الأمة الآن"))
        assertEquals("ح", avatarInitial("حلقة التفسير"))
        assertEquals("ا", avatarInitial("ال"))
        assertEquals("A", avatarInitial("Al Ummah Now"))
        assertEquals("T", avatarInitial("tafsir circle"))
        assertEquals("", avatarInitial(""))
    }
}
