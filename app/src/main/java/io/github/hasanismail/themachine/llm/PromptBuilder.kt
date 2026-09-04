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

import io.github.hasanismail.themachine.tools.Tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the prompt the model sees.
 *
 * Compactness is the whole design here, for two measured reasons. Prefill dominates
 * latency: a verbose tool listing ran to roughly 1600 tokens and cost 8–28 seconds on
 * device before a single output token appeared. And a 1B model asked to read a long
 * specification simply stops discriminating — the first version of this prompt made it
 * answer every request with the same tool.
 *
 * So: one line per tool, arguments named but not explained, and three worked examples.
 * The examples do more work than any amount of prose, because they show the exact
 * output shape rather than describing it. The grammar enforces the shape anyway, which
 * is precisely why the prompt does not need to.
 *
 * The current date and time are stated explicitly because the model has no clock, and
 * one left to guess will confidently invent a date.
 */
object PromptBuilder {

    private const val TURN_START = "<start_of_turn>"
    private const val TURN_END = "<end_of_turn>"

    // adminName and userContext stay in the signature: PromptDialect defines it, and both
    // still matter to AnswerPrompt. Routing simply must not see them.
    @Suppress("UnusedParameter")
    fun build(
        transcript: String,
        tools: List<Tool>,
        adminName: String,
        userContext: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        append(TURN_START).appendLine("user")
        // The user's name is deliberately NOT here. It plays no part in choosing a tool, and
        // a name that is also an ordinary word - "Admin", the default - dragged "text me" to
        // set_alarm and cut routing from 86% to 57% on device. The name still personalises
        // the spoken answer; see AnswerPrompt. (Fix from ijazshare/assistant.)
        appendLine("Pick one tool for this request. Output JSON only.")
        appendLine()

        // Everything from here to the clock line is byte-identical between requests, and
        // the engine reuses the cached keys and values for exactly that leading run. The
        // volatile parts — the time, the user's notes, the request itself — are therefore
        // kept to the end: a timestamp near the top invalidated the whole prefix each
        // time the minute rolled over, which cost a full 13-second prefill.
        for (tool in tools) {
            append(tool.name)
            if (tool.params.isNotEmpty()) {
                append("(")
                append(tool.params.joinToString(",") { if (it.required) it.name else "${it.name}?" })
                append(")")
            }
            append(" = ")
            // The whole description, not just its first sentence. Truncating here read
            // as a harmless economy and was anything but: the second sentence is where
            // each tool says which phrases belong to it, so cutting it lost "seconds is
            // the TOTAL seconds", "what does this say" and "what did I miss" — and the
            // model duly sent all three to the wrong tool.
            appendLine(tool.description)
        }
        appendLine()

        appendLine("Examples:")
        // Few and deliberately unalike. A 1B model given a long list stops choosing and
        // starts copying: at roughly 620 prompt tokens every reply came back as the
        // first example verbatim, whatever had been asked.
        appendLine("""alarm at 7 -> {"tool":"set_alarm","arguments":{"hour":7,"minute":0}}""")
        // send_message rather than a second set_alarm example. set_alarm is the tool the
        // model over-predicts when the prompt tips, so it needs the least help, and the
        // prompt has no room to grow: past roughly 620 tokens every reply comes back as the
        // first example verbatim. So this buys the messaging shape - verb, recipient, body -
        // at no extra length. (Fix from ijazshare/assistant.)
        appendLine(
            """text Sara I'll be late -> """ +
                """{"tool":"send_message","arguments":{"recipient":"Sara","body":"I'll be late"}}""",
        )
        appendLine("""timer for three minutes -> {"tool":"set_timer","arguments":{"minutes":3}}""")
        // With a time on it, since a reminder without one was the only example and the
        // model duly filed "at 6pm" as hour 6 — a notification twelve hours early.
        appendLine(
            """remind me to take the bins out at 8pm -> """ +
                """{"tool":"create_reminder","arguments":{"task":"take the bins out","hour":20}}""",
        )
        appendLine("""open Spotify -> {"tool":"open_app","arguments":{"app":"Spotify"}}""")
        appendLine("""what does this say -> {"tool":"read_screen"}""")
        // Teaches the category, not the phrase: asked to arrange something out in the
        // world, the model had been filing it as a reminder and inventing a time to
        // fire it at, which is worse than saying no.
        appendLine(
            """order me a pizza -> """ +
                """{"tool":"unsupported","arguments":{"reason":"I cannot order things."}}""",
        )
        // Without this the model reliably sent "scroll down" to navigate, which is the
        // one pair of tools whose descriptions alone did not separate them.
        appendLine("""scroll down -> {"tool":"scroll","arguments":{"direction":"down"}}""")
        // A general question. Without it every "how many", "who", "how far" and "what time
        // in <city>" landed on read_screen - the only question-shaped example in the list -
        // and the assistant recited the screen instead of answering. Exactly one, because a
        // 1B stops discriminating and starts copying once the prompt grows too long.
        // (Fix from ijazshare/assistant.)
        appendLine(
            """who painted the Mona Lisa -> """ +
                """{"tool":"answer","arguments":{"text":"Leonardo da Vinci."}}""",
        )
        appendLine()

        // The user's context is deliberately NOT here either. It personalises an *answer*,
        // but for *tool choice* it is noise: injecting the tasks file flipped "text me ..."
        // to set_alarm and cut send_message from 6/8 to 4/8. Routing stays minimal - tools,
        // examples, the request. The context still reaches AnswerPrompt.

        appendLine("Now: ${now.format(NOW_FORMAT)}")
        appendLine("Request: $transcript")
        appendLine(TURN_END)
        append(TURN_START).appendLine("model")
    }

    private val NOW_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")
}
