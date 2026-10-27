package com.cczb.app

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class RecoveryGattFailureTest {
    @Test fun explicitWriteAuthorizationRejectionIsEvidence() {
        assertTrue(RecoveryTransportException(RecoveryGattPhase.WRITE, RecoveryGattSource.WRITE, 8)
            .isRecoveryAuthorizationRejection())
    }

    @Test fun sameNumericLinkErrorDuringWriteIsNotAttRejection() {
        for (phase in RecoveryGattPhase.entries) for (status in listOf(0, 8, 19, 133, 147)) {
            assertFalse(RecoveryTransportException(phase, RecoveryGattSource.CONNECTION, status, 0)
                .isRecoveryAuthorizationRejection())
        }
    }

    @Test fun otherAttFailuresCannotProveInvalidProofOrReplayWasRejected() {
        for (status in listOf(0, 1, 5, 13, 15, 133, 147)) {
            assertFalse(RecoveryTransportException(RecoveryGattPhase.WRITE, RecoveryGattSource.WRITE, status)
                .isRecoveryAuthorizationRejection())
        }
        for (source in listOf(RecoveryGattSource.READ, RecoveryGattSource.REQUEST, RecoveryGattSource.CLOSE)) {
            assertFalse(RecoveryTransportException(RecoveryGattPhase.WRITE, source, 8)
                .isRecoveryAuthorizationRejection())
        }
    }

    @Test fun localErrorsAndWrappedFailuresAreInconclusive() {
        val errors = listOf(TimeoutException(), CancellationException(), IllegalArgumentException(),
            IllegalStateException(RecoveryTransportException(RecoveryGattPhase.WRITE, RecoveryGattSource.WRITE, 8)))
        errors.forEach { assertFalse(it.isRecoveryAuthorizationRejection()) }
    }
}
