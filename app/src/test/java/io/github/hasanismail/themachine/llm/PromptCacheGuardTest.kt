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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptCacheGuardTest {

    @Test
    fun reusesACacheFromTheSameBuild() {
        assertThat(PromptCacheGuard.isFresh(storedStamp = "1700000000000", currentStamp = "1700000000000")).isTrue()
    }

    @Test
    fun dropsACacheFromAnEarlierBuild() {
        assertThat(PromptCacheGuard.isFresh(storedStamp = "1699999999999", currentStamp = "1700000000000")).isFalse()
    }

    @Test
    fun dropsACacheWithNoStamp() {
        assertThat(PromptCacheGuard.isFresh(storedStamp = null, currentStamp = "1700000000000")).isFalse()
    }

    @Test
    fun refusesToReuseWhenTheInstallTimeIsUnknown() {
        // An empty current stamp means the query failed; never trust a cache blindly then.
        assertThat(PromptCacheGuard.isFresh(storedStamp = "", currentStamp = "")).isFalse()
    }
}
