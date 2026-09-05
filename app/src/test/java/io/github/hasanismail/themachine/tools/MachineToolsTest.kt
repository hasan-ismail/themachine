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

class MachineToolsTest {

    @Test
    fun `tool names are unique`() {
        val names = MachineTools.all.map { it.name }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `the prompt stays short enough for a small model to read it`() {
        // Not a style preference. At roughly 620 prompt tokens Gemma 3 1B stopped
        // choosing between tools and began repeating the first worked example whatever
        // it was asked, and the only symptom was wrong answers. Words are a rough proxy
        // for tokens, deliberately budgeted well under where that began.
        val prompt = io.github.hasanismail.themachine.llm.PromptBuilder.build(
            transcript = "set a timer for ten minutes",
            tools = MachineTools.all,
            adminName = "Hasan",
            userContext = "",
        )
        // 280, not 300. Measured on device: at 297 words — about 620 tokens — every reply
        // came back as the first example verbatim, which is exactly the figure
        // PromptBuilder's own comment names. A ceiling the prompt can reach while already
        // broken is not a ceiling, so this sits below the cliff rather than on it.
        assertThat(prompt.split(Regex("""\s+""")).size).isLessThan(280)
    }

    @Test
    fun `every declared tool has a required argument it can be identified by`() {
        // A tool whose arguments are all optional can be emitted as an empty object,
        // which is fine, but one that needs a subject must say so or the executor will
        // have nothing to act on.
        val needsSubject = listOf(
            MachineTools.CREATE_REMINDER,
            MachineTools.SEND_MESSAGE,
            MachineTools.CALL_CONTACT,
            MachineTools.OPEN_APP,
            MachineTools.TAP_TEXT,
            MachineTools.SCROLL,
            MachineTools.NAVIGATE,
            MachineTools.ANSWER,
        )
        for (name in needsSubject) {
            val tool = MachineTools.all.single { it.name == name }
            assertThat(tool.params.any { it.required }).isTrue()
        }
    }
}
