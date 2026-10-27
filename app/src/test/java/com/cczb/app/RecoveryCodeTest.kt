package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryCodeTest {
    @Test fun displayedCodeCanBeReenteredAndInvalidInputIsRejected() {
        val code = ByteArray(32) { (it + 1).toByte() }
        assertArrayEquals(code, RecoveryCode.parse(RecoveryCode.format(code)))
        assertArrayEquals(code, RecoveryCode.parse(RecoveryCode.format(code).lowercase().replace('-', ' ')))
        listOf("0".repeat(64), "1".repeat(63), "1".repeat(65), "G".repeat(64),
            "１".repeat(64), "\n" + "1".repeat(64), "1".repeat(32) + "\u200b" + "1".repeat(32)).forEach {
            assertFalse(RecoveryCode.valid(it))
        }
    }
}
