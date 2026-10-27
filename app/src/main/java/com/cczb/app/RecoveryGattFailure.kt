package com.cczb.app

internal enum class RecoveryGattPhase { CONNECT, SERVICES, INFO, READ, WRITE }
internal enum class RecoveryGattSource { CONNECTION, SERVICES, READ, WRITE, REQUEST, CLOSE }

/** Numeric ATT errors and link errors have different namespaces, even at the same phase. */
internal class RecoveryTransportException(
    val phase: RecoveryGattPhase,
    val source: RecoveryGattSource,
    val status: Int? = null,
    val state: Int? = null
) : Exception("Recovery GATT phase=$phase source=$source status=$status state=$state")

/** A timeout, disconnect, pairing error or malformed response cannot prove peer rejection. */
internal fun Throwable.isRecoveryAuthorizationRejection(): Boolean =
    this is RecoveryTransportException && phase == RecoveryGattPhase.WRITE &&
        source == RecoveryGattSource.WRITE && status == 0x08 // ATT Insufficient Authorization
