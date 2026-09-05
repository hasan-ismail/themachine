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

/**
 * Asking for the list, and the far more dangerous business of not mistaking anything else
 * for it.
 *
 * A false positive here is worse than a miss: it would swallow "remind me to buy milk" and
 * read the list back instead of writing anything down.
 */
class TaskQuestionTest {

    @Test
    fun `recognises the ways people ask`() {
        for (asked in listOf(
            "what do I have to do",
            "what are my reminders",
            "do I have any reminders",
            "read my list",
            "what's on my list",
            "any tasks",
            "what have I got to do",
        )) {
            assertThat(TaskQuestion.isAskingForList(asked)).isTrue()
        }
    }

    @Test
    fun `never swallows a new reminder`() {
        // The failure that would matter: these mention reminders and tasks, and every one
        // of them is an instruction to write something down.
        for (asked in listOf(
            "remind me to buy milk",
            "remind me about my tasks tomorrow",
            "add a reminder to call mum",
            "set a reminder for six",
            "new task water the plants",
        )) {
            assertThat(TaskQuestion.isAskingForList(asked)).isFalse()
        }
    }

    @Test
    fun `finds the task named as finished`() {
        assertThat(TaskQuestion.completionTarget("mark the bins done")).isEqualTo("bins")
        assertThat(TaskQuestion.completionTarget("I did the washing up")).contains("washing")
        assertThat(TaskQuestion.completionTarget("tick off buy milk as done")).contains("buy milk")
    }

    @Test
    fun `never completes when asked to create`() {
        // The dangerous confusion: this contains every finishing word and is an
        // instruction to write something down.
        assertThat(TaskQuestion.completionTarget("remind me to mark the report done")).isNull()
        assertThat(TaskQuestion.completionTarget("set a reminder that I am done at six")).isNull()
    }

    @Test
    fun `is not a completion without a finishing word`() {
        assertThat(TaskQuestion.completionTarget("the bins")).isNull()
        assertThat(TaskQuestion.completionTarget("what do I have to do")).isNull()
    }

    @Test
    fun `leaves everything else to the model`() {
        for (asked in listOf(
            "set a timer for ten minutes",
            "what time is it",
            "who painted the Mona Lisa",
            "text Sara I'll be late",
            "what does this say",
        )) {
            assertThat(TaskQuestion.isAskingForList(asked)).isFalse()
        }
    }
}
