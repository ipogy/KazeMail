package com.kazemail.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMailIntentAndDefaultAppTest {

    @Test
    fun parseMailtoUriParsesUtf8HeadersAndPreservesPlusAddresses() {
        val uri = "mailto:alice+work@example.com,bob@example.org" +
            "?to=ALICE+work@example.com;carol@example.net" +
            "&cc=team+ops@example.com" +
            "&bcc=audit@example.com" +
            "&subject=%E3%81%8A%E5%95%8F%E3%81%84%E5%90%88%E3%82%8F%E3%81%9B%20A%26B%3DC%2BD" +
            "&body=1%E8%A1%8C%E7%9B%AE%0D%0A2%E8%A1%8C%E7%9B%AE"

        val parsed = ExternalMailIntentHelper.parseMailtoUriString(uri)

        assertEquals(
            listOf("alice+work@example.com", "bob@example.org", "carol@example.net"),
            parsed.to,
        )
        assertEquals(listOf("team+ops@example.com"), parsed.cc)
        assertEquals(listOf("audit@example.com"), parsed.bcc)
        assertEquals("お問い合わせ A&B=C+D", parsed.subject)
        assertEquals("1行目\n2行目", parsed.body)
    }

    @Test
    fun parseMailtoUriHandlesSchemeOnlyAndRejectsNonMailtoSchemes() {
        val emptyMailto = ExternalMailIntentHelper.parseMailtoUriString("MAILTO:")
        assertTrue(emptyMailto.to.isEmpty())
        assertEquals("", emptyMailto.subject)

        val nonMailto = ExternalMailIntentHelper.parseMailtoUriString("https://example.com/?to=a@b.com")
        assertTrue(nonMailto.to.isEmpty())
        assertFalse(ExternalMailIntentHelper.isMailtoUriString("https://example.com"))
        assertTrue(ExternalMailIntentHelper.isMailtoUriString("  MailTo:test@example.com"))
    }

    @Test
    fun buildComposePayloadMergesMailtoAndIntentExtrasWithoutDuplicateRecipients() {
        val payload = ExternalMailIntentHelper.buildComposePayload(
            action = Intent.ACTION_SENDTO,
            dataUriString = "mailto:first@example.com?subject=MailtoSubject",
            extraTo = listOf("FIRST@example.com", "second@example.com"),
            extraCc = listOf("cc1@example.com; cc2@example.com"),
            extraBcc = listOf("bcc@example.com"),
            extraSubject = "FallbackSubject",
            extraBody = "共有テキスト\r\nURL: https://example.com",
        )

        assertEquals(listOf("first@example.com", "second@example.com"), payload.to)
        assertEquals(listOf("cc1@example.com", "cc2@example.com"), payload.cc)
        assertEquals(listOf("bcc@example.com"), payload.bcc)
        assertEquals("MailtoSubject", payload.subject)
        assertEquals("共有テキスト\nURL: https://example.com", payload.body)
    }

    @Test
    fun defaultMailAppClassifierTreatsResolversAsUnassignedAndDetectsSelfOrOtherApp() {
        val selfPkg = "com.kazemail.app"

        val self = DefaultMailAppHelper.classifyResolvedHandler(
            packageName = selfPkg,
            activityName = "com.kazemail.app.MainActivity",
            label = "KazeMail",
            selfPackageName = selfPkg,
        )
        assertTrue(self.isSelf)
        assertEquals(selfPkg, self.packageName)

        val aospChooser = DefaultMailAppHelper.classifyResolvedHandler(
            packageName = "android",
            activityName = "com.android.internal.app.ResolverActivity",
            label = "Android System",
            selfPackageName = selfPkg,
        )
        assertFalse(aospChooser.isSelf)
        assertNull(aospChooser.packageName)
        assertNull(aospChooser.label)

        val android14Chooser = DefaultMailAppHelper.classifyResolvedHandler(
            packageName = "com.android.intentresolver",
            activityName = "com.android.intentresolver.ResolverActivity",
            label = "Resolver",
            selfPackageName = selfPkg,
        )
        assertFalse(android14Chooser.isSelf)
        assertNull(android14Chooser.packageName)

        val gmail = DefaultMailAppHelper.classifyResolvedHandler(
            packageName = "com.google.android.gm",
            activityName = "com.google.android.gm.ConversationListActivityGmail",
            label = "Gmail",
            selfPackageName = selfPkg,
        )
        assertFalse(gmail.isSelf)
        assertEquals("com.google.android.gm", gmail.packageName)
        assertEquals("Gmail", gmail.label)
    }

    @Test
    fun defaultMailAppStatusReflectsPrimaryPartialAndUnsetStates() {
        val unset = DefaultMailAppStatus()
        assertFalse(unset.isPrimaryDefault)
        assertFalse(unset.isPartiallyDefault)

        val partialMailtoOnly = DefaultMailAppStatus(isDefaultMailto = true)
        assertFalse(partialMailtoOnly.isPrimaryDefault)
        assertTrue(partialMailtoOnly.isPartiallyDefault)

        val partialAppEmailOnly = DefaultMailAppStatus(isDefaultAppEmail = true)
        assertFalse(partialAppEmailOnly.isPrimaryDefault)
        assertTrue(partialAppEmailOnly.isPartiallyDefault)

        val fullDefault = DefaultMailAppStatus(isDefaultAppEmail = true, isDefaultMailto = true)
        assertTrue(fullDefault.isPrimaryDefault)
        assertFalse(fullDefault.isPartiallyDefault)

        val roleHeld = DefaultMailAppStatus(isEmailRoleAvailable = true, isEmailRoleHeld = true)
        assertTrue(roleHeld.isPrimaryDefault)
        assertFalse(roleHeld.isPartiallyDefault)
    }
}
