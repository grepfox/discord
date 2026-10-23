package com.clean.discord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRoutingTest {
    @Test
    fun discordHostsStayInApp() {
        listOf("discord.com", "ptb.discord.com", "cdn.discordapp.com", "media.discordapp.net", "discord.gg", "newassets.hcaptcha.com")
            .forEach { assertTrue(it, MainActivity.isInternalHost(it)) }
    }

    @Test
    fun otherHostsOpenExternally() {
        listOf("youtube.com", "notdiscord.com", "discord.com.evil.io", "example.org")
            .forEach { assertFalse(it, MainActivity.isInternalHost(it)) }
    }
}
