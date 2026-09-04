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

import io.github.hasanismail.themachine.tools.ParamType
import io.github.hasanismail.themachine.tools.Tool
import io.github.hasanismail.themachine.tools.ToolCall
import io.github.hasanismail.themachine.tools.ToolGrammar
import io.github.hasanismail.themachine.tools.ToolParam
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * FunctionGemma-270M's own surface form, taken from the chat template inside its GGUF
 * rather than guessed.
 *
 * It does not emit JSON. Tools are declared to it as
 * `<start_function_declaration>declaration:name{...}<end_function_declaration>` and it
 * replies with `<start_function_call>call:name{key:value}<end_function_call>`, where
 * strings are wrapped in `<escape>` markers and numbers are bare. Arguments are ordered
 * by key, because the template sorts them and that is what the model saw in training.
 *
 * Matching this exactly is the whole point: given a JSON-shaped prompt the model stopped
 * reading the request altogether and repeated the first example it was shown.
 */
object FunctionGemmaDialect : PromptDialect {

    override val id: String = "functiongemma"

    private const val DECL_START = "<start_function_declaration>"
    private const val DECL_END = "<end_function_declaration>"
    private const val CALL_START = "<start_function_call>"
    private const val CALL_END = "<end_function_call>"
    private const val ESC = "<escape>"

    private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")

    override fun buildPrompt(
        transcript: String,
        tools: List<Tool>,
        adminName: String,
        userContext: String,
        now: LocalDateTime,
    ): String = buildString {
        // System turn: instructions, then one declaration per tool, then end of turn.
        append("<start_of_turn>system\n")
        append("You are $adminName's assistant on their phone. Now: ${now.format(NOW_FORMAT)}.")
        if (userContext.isNotBlank()) {
            append(" About $adminName: ")
            append(userContext.replace('\n', ' ').take(CONTEXT_BUDGET))
        }
        for (tool in tools) {
            append(DECL_START)
            append(declaration(tool))
            append(DECL_END)
        }
        append("<end_of_turn>\n")

        append("<start_of_turn>user\n")
        append(transcript)
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    /** `declaration:name{description:<escape>…<escape>,parameters:{…}}` */
    private fun declaration(tool: Tool): String = buildString {
        append("declaration:").append(tool.name)
        append("{description:").append(ESC).append(tool.description).append(ESC)
        if (tool.params.isNotEmpty()) {
            append(",parameters:{properties:{")
            append(
                tool.params.sortedBy { it.name }.joinToString(",") { property(it) },
            )
            append("},")
            val required = tool.params.filter { it.required }
            if (required.isNotEmpty()) {
                append("required:[")
                append(required.joinToString(",") { "$ESC${it.name}$ESC" })
                append("],")
            }
            append("type:").append(ESC).append("OBJECT").append(ESC).append("}")
        }
        append("}")
    }

    private fun property(param: ToolParam): String = buildString {
        append(param.name).append(":{description:").append(ESC).append(param.description).append(ESC)
        append(",type:").append(ESC).append(param.type.declared()).append(ESC)
        if (param.type == ParamType.ENUM && param.values.isNotEmpty()) {
            append(",enum:[").append(param.values.joinToString(",") { "$ESC$it$ESC" }).append("]")
        }
        append("}")
    }

    private fun ParamType.declared(): String = when (this) {
        ParamType.STRING, ParamType.ENUM -> "STRING"
        ParamType.INTEGER -> "INTEGER"
        ParamType.BOOLEAN -> "BOOLEAN"
    }

    /**
     * Constrains output to one well-formed call.
     *
     * Arguments appear in alphabetical order because the template sorts them; emitting
     * them in declaration order would be a shape the model never saw.
     */
    // Includes the closing brace this dialect's own parser requires. Stopping at the
    // opening one produced a fragment the regex could never match, so every question
    // failed to parse instead of being escalated.
    override val answerMarker: String = "call:answer{}"

    override fun grammar(tools: List<Tool>): String = buildString {
        appendLine("root ::= " + tools.indices.joinToString(" | ") { "call$it" })
        appendLine()

        tools.forEachIndexed { index, tool ->
            // Alphabetical, because the chat template sorts arguments and that is the
            // order the model was trained to emit them in.
            val sorted = tool.params.sortedBy { it.name }
            appendLine("""call$index ::= "$CALL_START" "call:${tool.name}{" h$index-0 "}$CALL_END"""")

            sorted.forEachIndexed { position, param ->
                val emitted = """"${param.name}:" v$index-$position"""
                val next = position + 1
                // Same chain as the JSON dialect, and for the same reason: a repeatable
                // group lets one argument be emitted over and over until the token limit.
                if (param.required) {
                    appendLine("h$index-$position ::= $emitted t$index-$next")
                    appendLine("""t$index-$position ::= "," $emitted t$index-$next""")
                } else {
                    appendLine("h$index-$position ::= $emitted t$index-$next | h$index-$next")
                    appendLine("""t$index-$position ::= "," $emitted t$index-$next | t$index-$next""")
                }
            }
            appendLine("h$index-${sorted.size} ::= \"\"")
            appendLine("t$index-${sorted.size} ::= \"\"")

            sorted.forEachIndexed { position, param ->
                val rule = "v$index-$position"
                appendLine(
                    when (param.type) {
                        // Bounded exactly as the JSON dialect bounds it. This read
                        // "integer" and ignored param.range entirely, so an hour could be
                        // any number at all: a real reminder was filed at hour 30.
                        ParamType.INTEGER ->
                            "$rule ::= " + (param.range?.let { ToolGrammar.boundedRule(it) } ?: "integer")

                        ParamType.BOOLEAN -> "$rule ::= boolean"

                        ParamType.ENUM -> "$rule ::= " + param.values.joinToString(" | ") {
                            """"$ESC$it$ESC""""
                        }

                        ParamType.STRING -> "$rule ::= escaped"
                    },
                )
            }
        }
        // An explicit allow-list rather than [^<]: GBNF gives '<' its own meaning for
        // token references, and a bare one inside a character class takes the parser
        // down a path it cannot return from.
        appendLine("""escaped ::= "$ESC" [a-zA-Z0-9 .,'!?@/:_+()&-]* "$ESC"""")
        appendLine("""integer ::= "-"? ("0" | [1-9] [0-9]{0,9})""")
        appendLine("""boolean ::= "true" | "false"""")
    }

    // Both braces escaped: Android's ICU engine rejects a bare '}' as a dangling
    // quantifier, where the JVM's own engine had quietly tolerated it.
    private val CALL_PATTERN = Regex("""call:([a-z_]+)\{(.*)\}""", RegexOption.DOT_MATCHES_ALL)

    override fun parse(raw: String): ToolCall? {
        val match = CALL_PATTERN.find(raw) ?: return null
        val name = match.groupValues[1]
        val body = match.groupValues[2]
        if (body.isBlank()) return ToolCall(name, emptyMap())

        val arguments = LinkedHashMap<String, String>()
        // Split on commas that separate pairs, not commas inside an escaped string.
        for (piece in splitPairs(body)) {
            val colon = piece.indexOf(':')
            if (colon <= 0) continue
            val key = piece.substring(0, colon).trim()
            val value = piece.substring(colon + 1).trim().removeSurrounding(ESC)
            if (key.isNotEmpty() && value.isNotEmpty()) arguments[key] = value
        }
        return ToolCall(name, arguments)
    }

    private fun splitPairs(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            if (body.startsWith(ESC, index)) {
                depth = if (depth == 0) 1 else 0
                current.append(ESC)
                index += ESC.length
                continue
            }
            val c = body[index]
            if (c == ',' && depth == 0) {
                out.add(current.toString())
                current.clear()
            } else {
                current.append(c)
            }
            index++
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private const val CONTEXT_BUDGET = 400
}
