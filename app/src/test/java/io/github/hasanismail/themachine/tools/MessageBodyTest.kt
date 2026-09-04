/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageBodyTest {

    @Test
    fun keepsADictatedMessage() {
        assertThat(MessageBody.isTraceable("text Sarah I am on my way", "I am on my way")).isTrue()
    }

    @Test
    fun keepsAParaphrasedContraction() {
        // "I'm" heard, "I am" written: most words still line up.
        assertThat(MessageBody.isTraceable("text Dad I'm running late", "I am running late")).isTrue()
    }

    @Test
    fun dropsAnInventedBody() {
        // The bug from testing: a bare "text Dad" produced "hello how are you".
        assertThat(MessageBody.isTraceable("text Dad", "hello how are you")).isFalse()
    }

    @Test
    fun allowsATinyReply() {
        // Nothing to fabricate in "ok"; do not block it.
        assertThat(MessageBody.isTraceable("reply ok", "ok")).isTrue()
    }

    @Test
    fun dropsABodyAboutSomethingElse() {
        assertThat(MessageBody.isTraceable("message mum", "the meeting is at three")).isFalse()
    }
}
