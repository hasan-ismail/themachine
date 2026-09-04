/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.settings.MachineSettings
import io.github.hasanismail.themachine.tools.MachineTools
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real phrases from Hasan's own query log, and what they must not do again.
 *
 * Every one of these was recorded going somewhere wrong on the S26 Ultra. The common
 * cause was read_screen: its description invited "answer a question about them", and a 1B
 * reads a tool list as a bag of words, so the broadest invitation won every time — "what
 * time is it in Berlin", "hello, hear me" and "beep" all resolved to read_screen.
 *
 * These run the real model on the device, so they are slow and they are the only check
 * that the wording actually works rather than merely reading well.
 */
@RunWith(AndroidJUnit4::class)
class RoutingRegressionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun installed() = ModelRegistry(context).byRole(ModelRole.LLM)
        .filter { ModelStorage(context).quickState(it) == ModelState.Ready }
        .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }

    private fun route(utterance: String): String? {
        val asset = installed() ?: return null
        val dialect = PromptDialect.forModel(asset.fileName)
        val prompt = dialect.buildPrompt(
            transcript = utterance,
            tools = MachineTools.all,
            adminName = MachineSettings.DEFAULT_ADMIN_NAME,
            userContext = "",
        )
        val completion = runBlocking {
            engine.generate(prompt, dialect.grammar(MachineTools.all), stopAt = dialect.answerMarker)
        }
        val call = dialect.parse(completion.text)
        Log.i(TAG, "ROUTE [$utterance] -> ${call?.tool} ${call?.arguments} (${completion.millis} ms)")
        return call?.tool
    }

    @Test
    fun aTimerIsATimerAndNotAReminder() {
        assumeTrue("No language model installed", ready)
        // Recorded as create_reminder with hour=30. The word "timer" was not in
        // set_timer's description at all, while "time" sat in create_reminder's.
        assertThat(route("set a 30 minute timer")).isEqualTo(MachineTools.SET_TIMER)
        assertThat(route("timer for ten minutes")).isEqualTo(MachineTools.SET_TIMER)
    }

    @Test
    fun aReminderIsStillAReminder() {
        assumeTrue("No language model installed", ready)
        assertThat(route("remind me to call mum at six")).isEqualTo(MachineTools.CREATE_REMINDER)
    }

    @Test
    fun theScreenToolDoesNotSwallowEverything() {
        assumeTrue("No language model installed", ready)
        // All four were recorded resolving to read_screen on the S26 Ultra.
        for (utterance in listOf(
            "what time is it in Berlin",
            "hello, hear me",
            "beep",
            "set a 30 minute timer",
        )) {
            assertThat(route(utterance)).isNotEqualTo(MachineTools.READ_SCREEN)
        }
    }

    @Test
    fun readingTheScreenStillWorks() {
        assumeTrue("No language model installed", ready)
        assertThat(route("what does this say")).isEqualTo(MachineTools.READ_SCREEN)
    }

    private companion object {
        const val TAG = "TheMachine"
        val engine = LlamaEngine(InstrumentationRegistry.getInstrumentation().targetContext)

        val ready: Boolean = run {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storage = ModelStorage(context)
            val assets = ModelRegistry(context).byRole(ModelRole.LLM)
                .filter { storage.quickState(it) == ModelState.Ready }
            val asset = assets.firstOrNull { it.isDefault } ?: assets.firstOrNull()
            asset != null && runBlocking { engine.load(storage.target(asset)) }
        }

        @JvmStatic
        @AfterClass
        fun freeTheModel() = engine.unload()
    }
}
