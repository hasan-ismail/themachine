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
import io.github.hasanismail.themachine.tools.MachineTools
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finds which GBNF construct llama.cpp rejects.
 *
 * The parser reports only "failed to parse grammar" with no rule and no position, so
 * the only practical way to locate a bad construct is to hand it candidates and see
 * which ones come back. Kept as a permanent test: it is also the regression guard that
 * the shipped grammar still parses against the pinned llama.cpp.
 */
@RunWith(AndroidJUnit4::class)
class GrammarBisectTest {

    private val engine: LlamaEngine by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LlamaEngine(context).also { engine ->
            val storage = ModelStorage(context)
            ModelRegistry(context).byRole(ModelRole.LLM)
                .filter { storage.quickState(it) == ModelState.Ready }
                .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }
                ?.let { runBlocking { engine.load(storage.target(it)) } }
        }
    }

    private fun accepts(label: String, grammar: String): Boolean {
        val ok = runBlocking { engine.validateGrammar(grammar) }
        Log.i("TheMachine", "GBNF ${if (ok) "OK  " else "FAIL"}  $label")
        return ok
    }

    private fun accepts(label: String, grammar: String, text: String): Boolean {
        val ok = runBlocking { engine.grammarAccepts(grammar, text) }
        Log.i("TheMachine", "ACCEPT ${if (ok) "OK  " else "FAIL"}  $label")
        return ok
    }

    /**
     * The grammar must admit every tool, not merely parse.
     *
     * Parsing and accepting are different claims and only the second is the one the
     * sampler depends on. When every reply came back as set_alarm with nonsense
     * arguments, the grammar was parsing perfectly well — it simply did not allow any
     * other tool through, and nothing short of this test said so.
     */
    // What the model says when nothing constrains it. Not an assertion so much as a
    // control: if the unconstrained reply is sensible then any nonsense in the
    // constrained one belongs to the grammar or the sampler, and if it is nonsense too
    // then the fault is upstream of both.
    @Test
    fun unconstrainedOutputIsLegible() {
        assumeTrue(engine.isLoaded)
        for (utterance in listOf("open Spotify", "set a timer for ten minutes", "go back")) {
            val prompt = PromptBuilder.build(
                transcript = utterance,
                tools = MachineTools.all,
                adminName = "Hasan",
                userContext = "",
            )
            val free = runBlocking { engine.generate(prompt, grammar = "") }
            Log.i("TheMachine", "FREE [$utterance] -> ${free.text.take(160)}")
            val bound = runBlocking { engine.generate(prompt, MachineTools.grammar) }
            Log.i("TheMachine", "BOUND [$utterance] -> ${bound.text.take(160)}")
        }
    }

    @Test
    fun theGrammarAdmitsEveryTool() {
        assumeTrue(engine.isLoaded)
        val grammar = MachineTools.grammar
        val samples = mapOf(
            MachineTools.SET_ALARM to
                """{"tool":"set_alarm","arguments":{"hour":7,"minute":0}}""",
            MachineTools.SET_TIMER to """{"tool":"set_timer","arguments":{"minutes":10}}""",
            MachineTools.SHOW_ALARMS to """{"tool":"show_alarms"}""",
            MachineTools.CREATE_REMINDER to
                """{"tool":"create_reminder","arguments":{"task":"buy milk"}}""",
            MachineTools.OPEN_APP to """{"tool":"open_app","arguments":{"app":"Spotify"}}""",
            MachineTools.READ_SCREEN to """{"tool":"read_screen"}""",
            MachineTools.TAKE_SCREENSHOT to """{"tool":"take_screenshot"}""",
            MachineTools.SCROLL to """{"tool":"scroll","arguments":{"direction":"down"}}""",
            MachineTools.NAVIGATE to """{"tool":"navigate","arguments":{"target":"back"}}""",
            MachineTools.READ_NOTIFICATIONS to """{"tool":"read_notifications"}""",
            MachineTools.ANSWER to """{"tool":"answer","arguments":{"text":"Leonardo da Vinci."}}""",
        )
        val rejected = samples.filterNot { (tool, text) -> accepts(tool, grammar, text) }.keys
        assertThat(rejected).isEmpty()
    }

    @Test
    fun optionalArgumentsMayBeOmittedButNotRepeated() {
        assumeTrue(engine.isLoaded)
        val grammar = MachineTools.grammar
        // Every optional argument absent, and every one present, must both be legal.
        assertThat(accepts("bare alarm", grammar, """{"tool":"set_alarm","arguments":{"hour":7}}"""))
            .isTrue()
        assertThat(
            accepts(
                "full alarm",
                grammar,
                """{"tool":"set_alarm","arguments":{"hour":7,"minute":5,"label":"gym"}}""",
            ),
        ).isTrue()
        // The repeat that ran a reply to the token limit must now be impossible.
        assertThat(
            accepts(
                "repeated minute",
                grammar,
                """{"tool":"set_alarm","arguments":{"hour":7,"minute":5,"minute":5}}""",
            ),
        ).isFalse()
    }

    @Test
    fun anHourOutsideTheClockCannotBeGenerated() {
        assumeTrue(engine.isLoaded)
        val grammar = MachineTools.grammar
        assertThat(accepts("hour 9", grammar, """{"tool":"set_alarm","arguments":{"hour":9}}"""))
            .isTrue()
        assertThat(accepts("hour 12", grammar, """{"tool":"set_alarm","arguments":{"hour":12}}"""))
            .isTrue()
        // 16 was what came back for "half past six in the evening"; it is now unsayable.
        assertThat(accepts("hour 24", grammar, """{"tool":"set_alarm","arguments":{"hour":24}}"""))
            .isFalse()
        assertThat(accepts("hour 0", grammar, """{"tool":"set_alarm","arguments":{"hour":0}}"""))
            .isTrue()
        assertThat(
            accepts("minute 75", grammar, """{"tool":"set_alarm","arguments":{"hour":7,"minute":75}}"""),
        ).isFalse()
    }

    @Test
    fun theShippedGrammarParses() {
        assumeTrue(engine.isLoaded)

        // Narrow from the simplest possible grammar upward, so the first failure names
        // the construct rather than the whole file.
        assertThat(accepts("literal only", """root ::= "hello"""")).isTrue()
        assertThat(accepts("alternation", """root ::= "a" | "b"""")).isTrue()
        assertThat(accepts("reference", "root ::= a\na ::= \"x\"")).isTrue()
        assertThat(
            accepts("escaped quotes", """root ::= "{\"tool\":\"x\"}""""),
        ).isTrue()
        assertThat(accepts("group star", """root ::= "a" ("," "b")*""")).isTrue()
        assertThat(
            accepts(
                "json string rule",
                "root ::= string\n" +
                    """string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""""",
            ),
        ).isTrue()
        assertThat(
            accepts("integer rule", "root ::= integer\n" + """integer ::= "-"? ("0" | [1-9] [0-9]{0,9})"""),
        ).isTrue()

        // FunctionGemma's format is built from <angle-bracket> markers and GBNF gives
        // '<' its own meaning, so this isolates whether such a literal is usable at all.
        accepts("angle literal", "root ::= \"<abc>\"")

        // Then each tool on its own, so a single bad tool is named.
        for (tool in MachineTools.all) {
            val single = io.github.hasanismail.themachine.tools.ToolGrammar.build(listOf(tool))
            assertThat(accepts("tool ${tool.name}", single)).isTrue()
        }

        // Finally the real thing, in both dialects — a malformed grammar crashes the
        // process deep inside the sampler, so it is worth naming here instead.
        assertThat(accepts("full tool grammar (json)", MachineTools.grammar)).isTrue()
        for (tool in MachineTools.all) {
            assertThat(
                accepts("fg tool ${tool.name}", FunctionGemmaDialect.grammar(listOf(tool))),
            ).isTrue()
        }
        assertThat(
            accepts("full tool grammar (functiongemma)", FunctionGemmaDialect.grammar(MachineTools.all)),
        ).isTrue()
    }
}
