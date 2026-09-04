/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * "What time is it in Berlin" is as exactly knowable as "what time is it".
 *
 * It was going to the language model, which is how it came back as read_screen and the
 * assistant recited the notification shade. The phone carries the whole zone database.
 */
class LocalAnswersZoneTest {

    private val noon = LocalDateTime.of(2026, 9, 4, 12, 0)

    @Test
    fun `answers the time in another city`() {
        val answer = LocalAnswers.of("what time is it in Berlin", noon)
        assertThat(answer).isNotNull()
        assertThat(answer).contains("Berlin")
    }

    @Test
    fun `still answers the plain question`() {
        assertThat(LocalAnswers.of("what time is it", noon)).isEqualTo("It is 12:00 PM.")
    }

    @Test
    fun `leaves a place it does not know to the model`() {
        assertThat(LocalAnswers.of("what time is it in Rivendell", noon)).isNull()
    }

    @Test
    fun `is not a general question answerer`() {
        assertThat(LocalAnswers.of("who painted the Mona Lisa", noon)).isNull()
        assertThat(LocalAnswers.of("set a timer for ten minutes", noon)).isNull()
    }
}
