package com.cczb.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e masterAddress AA:BB:CC:DD:EE:FF. Queries only; no start, retry or pump-test calls. */
@RunWith(AndroidJUnit4::class)
class TaskReconciliationDeviceTest {
    @Test fun existingPhoneHistoryRestoresAcrossRealBleReconnectAndOutputRemainsOff() {
        val address = InstrumentationRegistry.getArguments().getString("masterAddress")
        assumeNotNull(address)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: MasterControlViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))
                .get(MasterControlViewModel::class.java)
        }
        try {
            val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
            instrumentation.runOnMainSync { model.connect(MasterDevice(device, 0, "PUMP_MASTER")) }
            val restored = model.ui.value
            assertTrue("phone history must remain readable", restored.historyHealthy)
            assertNotNull("this opt-in test requires an existing ledger task", restored.task)
            assertTrue("saved evidence: ${restored.taskReconciliationDetails}", restored.taskResultsComplete)
            assertFalse(restored.taskNeedsReconciliation)
            assertTrue(restored.flows.values.all { it.receivedAt == 0L })
            val originalTask = restored.task
            Log.i("TaskReconciliationQA", "restored job=${originalTask?.job} complete=${restored.taskResultsComplete} " +
                "ml=${restored.volume?.ml} pulses=${restored.flows.values.map { it.pulses }}")
            fun awaitCurrentState() {
                val deadline = System.nanoTime() + 60_000_000_000L
                while (System.nanoTime() < deadline) {
                    val ui = model.ui.value
                    if (ui.connected && ui.statusFresh && ui.localFresh && ui.inventoryFresh) return
                    Thread.sleep(200)
                }
                fail("read-only sync timeout: ${model.ui.value.message}")
            }
            awaitCurrentState()
            val synced = model.ui.value
            assertFalse("master output must be confirmed off", synced.local!!.pumpCommand)
            assertFalse(synced.local.testActive)
            assertEquals(originalTask, synced.task)
            assertTrue(synced.taskResultsComplete)
            assertTrue("other start gate: ${synced.startBlockReason}", synced.canStart)
            instrumentation.runOnMainSync { model.manager.disconnect() }
            instrumentation.runOnMainSync { model.connect(MasterDevice(device, 0, "PUMP_MASTER")) }
            assertTrue(model.ui.value.taskResultsComplete)
            awaitCurrentState()
            val reconnected = model.ui.value
            assertEquals(originalTask, reconnected.task)
            assertTrue(reconnected.taskResultsComplete)
            assertTrue("reconnect gate: ${reconnected.startBlockReason}", reconnected.canStart)
            assertFalse(reconnected.local!!.pumpCommand)
            assertFalse(reconnected.local.testActive)
            Log.i("TaskReconciliationQA", "reconnected job=${originalTask?.job} complete=true canStart=true " +
                "pump_command=0 phase=${reconnected.local.phase} epoch=${reconnected.hello?.epoch}")
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }
}
