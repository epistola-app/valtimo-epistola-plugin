// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.evenement

import com.ritense.valtimo.epistola.evenement.RegisterParticipantDelegate.Companion.chosenDayparts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RegisterParticipantDelegateTest {
    @Test
    fun `select boxes submit an object, of which the ticked keys count`() {
        assertThat(chosenDayparts(mapOf("ochtend" to true, "middag" to false))).containsExactly("ochtend")
        assertThat(chosenDayparts(linkedMapOf("ochtend" to true, "middag" to true))).containsExactly("ochtend", "middag")
    }

    @Test
    fun `a list of keys is taken as it is`() {
        assertThat(chosenDayparts(listOf("middag"))).containsExactly("middag")
    }

    @Test
    fun `nothing ticked is no daypart`() {
        assertThat(chosenDayparts(null)).isEmpty()
        assertThat(chosenDayparts(mapOf("ochtend" to false, "middag" to false))).isEmpty()
    }

    @Test
    fun `an unreadable value is refused`() {
        assertThatThrownBy { chosenDayparts(42) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}