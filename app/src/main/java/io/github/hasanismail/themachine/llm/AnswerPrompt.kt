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

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The prompt a question is answered with, once the small model has decided it is one.
 *
 * Free text, no grammar: the point of escalating is a reply in words, and the larger
 * model is good at those. Ordered like the tool prompt — everything that does not change
 * first, the clock and the question last — so its cached prefix survives between
 * questions.
 */
object AnswerPrompt {

    private const val TURN_START = "<start_of_turn>"
    private const val TURN_END = "<end_of_turn>"

    fun build(
        question: String,
        adminName: String,
        userContext: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        append(TURN_START).appendLine("user")
        // The line to walk: answer what it reliably knows, decline what it cannot know
        // offline rather than invent it. A blanket "if you don't know, say so" makes a 4B
        // take the cheaper way out and refuse even the Mona Lisa; naming the categories it
        // must NOT guess at — live, changing, countable-but-unknown — keeps the refusals
        // where they belong. Told it is offline so it declines current affairs plainly
        // instead of explaining it cannot browse.
        appendLine(
            "You are $adminName's assistant on their phone, offline. Answer general-knowledge " +
                "questions — established facts, definitions, arithmetic — from your own knowledge, " +
                "in ONE short plain sentence. But if a correct answer would need live or changing " +
                "information you cannot have offline — weather, news, prices, scores, or how many " +
                "of something there are — say you cannot know that offline instead of guessing. " +
                "Never invent a number you are not sure of. Do not mention searching or browsing. " +
                "You are only answering in words here: you cannot send a message, place a call, " +
                "set an alarm or otherwise act on the phone from this reply, so if you are asked " +
                "to do one of those, say you cannot do that here — never claim you did it.",
        )
        // The instruction alone does not stop a 4B inventing "25,000 buildings" or a game
        // score: it does not know that it does not know, so it fills the blank. Three
        // contrasts — a fact answered, a live figure and an unknowable count both declined
        // — give it the pattern to match. Deliberately not the benchmark's own questions,
        // so the honesty test still measures whether the pattern generalises.
        appendLine()
        appendLine("Examples:")
        appendLine("what is the capital of Japan -> Tokyo is the capital of Japan.")
        appendLine("what is the price of gold today -> I can't know current prices offline.")
        appendLine("how many cars are in London -> I can't know a count like that offline.")
        if (userContext.isNotBlank()) {
            appendLine()
            appendLine("About $adminName:")
            appendLine(userContext.take(CONTEXT_BUDGET))
        }
        appendLine()
        appendLine("Now: ${now.format(NOW_FORMAT)}")
        appendLine("Question: $question")
        appendLine(TURN_END)
        append(TURN_START).appendLine("model")
    }

    /**
     * One sentence. Asked for two the 4B filled the second with whatever came to hand —
     * "a leap year has an extra month of February", and an unprompted opinion about the
     * user's brother — so the room for it is gone. It is also the answer being read
     * aloud, where a second sentence is a second thing to sit through.
     */
    const val MAX_TOKENS = 44

    private const val CONTEXT_BUDGET = 800
    private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")
}
