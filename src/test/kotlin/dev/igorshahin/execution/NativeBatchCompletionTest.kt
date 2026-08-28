package dev.igorshahin.execution

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.OutputStream

class NativeBatchCompletionTest : BasePlatformTestCase() {
    private class NativeHandler(private val result: Int = 0) : ProcessHandler() {
        override fun destroyProcessImpl() = notifyProcessTerminated(result)
        override fun detachProcessImpl() = notifyProcessDetached()
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream? = null
        fun finishNormally() = notifyProcessTerminated(result)
    }

    fun testFlutterSuccessfulProcessDestructionDoesNotCancelRemainingFiles() {
        val handler = NativeHandler()
        var destroyed = false
        handler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                destroyed = willBeDestroyed
            }
        })
        handler.startNotify()
        handler.destroyProcess() // Flutter's successful completion path observed in sandbox.
        assertTrue(destroyed)
        assertEquals(0, handler.exitCode)
        assertNull(handler.getUserData(ProcessHandler.TERMINATION_REQUESTED))
        assertTrue(TestExecutionService.canContinueBatch(handler, handler.exitCode!!))
    }

    fun testNativeStopCancelsRemainingFilesEvenIfProcessExitsZero() {
        val handler = NativeHandler()
        handler.startNotify()
        // Set by IDEA 253 ExecutionManagerImpl.Companion.stopProcess before destroying it.
        handler.putUserData(ProcessHandler.TERMINATION_REQUESTED, true)
        handler.destroyProcess()
        assertEquals(0, handler.exitCode)
        assertFalse(TestExecutionService.canContinueBatch(handler, handler.exitCode!!))
    }

    fun testFailedNativeFileDoesNotAdvanceTheQueue() {
        val handler = NativeHandler(1)
        handler.startNotify()
        handler.finishNormally()
        assertFalse(TestExecutionService.canContinueBatch(handler, handler.exitCode!!))
    }

    fun testOrdinarySuccessfulExitAdvancesTheQueue() {
        val handler = NativeHandler()
        handler.startNotify()
        handler.finishNormally()
        assertTrue(TestExecutionService.canContinueBatch(handler, handler.exitCode!!))
    }
}
