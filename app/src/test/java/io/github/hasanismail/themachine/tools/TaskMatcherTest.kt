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
import io.github.hasanismail.themachine.context.TaskLine
import org.junit.Test

/** Naming a task back the way a person actually does. */
class TaskMatcherTest {

    private fun task(title: String, done: Boolean = false) =
        TaskLine(id = title.take(4), title = title, due = null, done = done)

    private val tasks = listOf(
        task("take the bins out"),
        task("call the dentist about the appointment"),
        task("buy milk"),
    )

    @Test
    fun `finds a task from a fragment of it`() {
        val found = TaskMatcher.find("the bins", tasks)
        assertThat(found).isInstanceOf(TaskMatcher.Result.One::class.java)
        assertThat((found as TaskMatcher.Result.One).task.title).isEqualTo("take the bins out")
    }

    @Test
    fun `finds a wordy task from one distinctive word`() {
        val found = TaskMatcher.find("dentist", tasks)
        assertThat((found as TaskMatcher.Result.One).task.title).contains("dentist")
    }

    @Test
    fun `reports nothing rather than guessing`() {
        assertThat(TaskMatcher.find("wash the car", tasks)).isEqualTo(TaskMatcher.Result.None)
    }

    @Test
    fun `noise words alone identify nothing`() {
        // "mark that task done" carries no subject at all; picking a task here would be a
        // coin toss with a silent cost.
        assertThat(TaskMatcher.find("that task", tasks)).isEqualTo(TaskMatcher.Result.None)
    }

    @Test
    fun `asks rather than choose between equals`() {
        val two = listOf(task("call the dentist"), task("call the doctor"))
        assertThat(TaskMatcher.find("call", two)).isInstanceOf(TaskMatcher.Result.Several::class.java)
    }

    @Test
    fun `ignores what is already done`() {
        val done = listOf(task("buy milk", done = true))
        assertThat(TaskMatcher.find("milk", done)).isEqualTo(TaskMatcher.Result.None)
    }
}
