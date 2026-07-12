package com.interndra.ai.tasks

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskPlan — a structured plan with subtasks for Claude-like task execution.
 *
 * Each task has a lifecycle: PLANNED → RUNNING → COMPLETED / FAILED / CANCELLED.
 * Tasks can be PAUSED and RESUMED at any point during execution.
 */
data class TaskPlan(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val description: String = "",
    val steps: List<TaskStep>,
    val status: TaskStatus = TaskStatus.PLANNED,
    val progress: Float = 0f,           // 0.0 to 1.0
    val currentStepIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val totalDurationMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap()
) {
    /** Number of completed steps */
    val completedSteps: Int get() = steps.count { it.status == StepStatus.COMPLETED }

    /** Number of failed steps */
    val failedSteps: Int get() = steps.count { it.status == StepStatus.FAILED }

    /** Number of pending steps */
    val pendingSteps: Int get() = steps.count { it.status == StepStatus.PENDING }

    /** Is this task terminal (completed, failed, or cancelled)? */
    val isTerminal: Boolean get() = status == TaskStatus.COMPLETED ||
        status == TaskStatus.FAILED || status == TaskStatus.CANCELLED
}

data class TaskStep(
    val index: Int,
    val label: String,
    val command: String,
    val status: StepStatus = StepStatus.PENDING,
    val output: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long = 0L,
    val retryCount: Int = 0
)

enum class TaskStatus {
    PLANNED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

enum class StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}

/**
 * A single task execution step callback.
 * Called after each step completes (success or failure).
 */
typealias StepCallback = suspend (TaskStep, String) -> Unit

/**
 * TaskManager — manages task lifecycle: create, execute, pause, resume, retry, cancel.
 *
 * ## Features
 * - Sequential step execution with real-time status updates
 * - Pause/Resume — pauses after current step completes
 * - Retry — retries failed steps individually
 * - Cancel — stops at the next safe point
 * - Progress tracking — emits StateFlow for UI binding
 * - Thread-safe via Mutex (one task executes at a time)
 */
class TaskManager(
    private val scope: CoroutineScope,
    private val stepExecutor: suspend (command: String) -> TaskStepResult
) {
    companion object {
        private const val TAG = "TaskManager"
    }

    // ── State ──────────────────────────────────────────────────────────
    private val _activeTask = MutableStateFlow<TaskPlan?>(null)
    val activeTask: StateFlow<TaskPlan?> = _activeTask.asStateFlow()

    private val _taskHistory = MutableStateFlow<List<TaskPlan>>(emptyList())
    val taskHistory: StateFlow<List<TaskPlan>> = _taskHistory.asStateFlow()

    private val executionMutex = Mutex()
    private var currentJob: Job? = null
    private var pauseRequested = false
    private var cancelRequested = false

    /** Whether a task is currently executing */
    val isExecuting: Boolean get() = currentJob?.isActive == true

    /** Whether execution is paused */
    val isPaused: Boolean get() = _activeTask.value?.status == TaskStatus.PAUSED

    // ── Task Creation ─────────────────────────────────────────────────

    /**
     * Create a new TaskPlan from a title, description, and list of step commands.
     */
    fun createTask(
        title: String,
        description: String = "",
        steps: List<Pair<String, String>> = emptyList(),  // Pair<label, command>
        metadata: Map<String, String> = emptyMap()
    ): TaskPlan {
        val taskSteps = steps.mapIndexed { i, (label, cmd) ->
            TaskStep(index = i, label = label, command = cmd)
        }
        return TaskPlan(
            title = title,
            description = description,
            steps = taskSteps,
            metadata = metadata
        )
    }

    /**
     * Create a TaskPlan from an existing list of TaskSteps.
     */
    fun createTaskFromSteps(
        title: String,
        description: String = "",
        steps: List<TaskStep>,
        metadata: Map<String, String> = emptyMap()
    ): TaskPlan {
        return TaskPlan(
            title = title,
            description = description,
            steps = steps.mapIndexed { i, step -> step.copy(index = i) },
            metadata = metadata
        )
    }

    // ── Execution ─────────────────────────────────────────────────────

    /**
     * Start executing a task plan. Runs steps sequentially.
     * Emits updates to [activeTask] StateFlow after each step.
     */
    fun execute(task: TaskPlan, onStepComplete: StepCallback? = null) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Task already executing — ignoring")
            return
        }

        cancelRequested = false
        pauseRequested = false
        val runningTask = task.copy(
            status = TaskStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        _activeTask.value = runningTask

        currentJob = scope.launch {
            executionMutex.withLock {
                var current = runningTask
                val startMs = System.currentTimeMillis()

                for (i in current.currentStepIndex until current.steps.size) {
                    // Check cancellation
                    if (cancelRequested) {
                        val cancelled = current.copy(
                            status = TaskStatus.CANCELLED,
                            completedAt = System.currentTimeMillis(),
                            totalDurationMs = System.currentTimeMillis() - startMs
                        )
                        finishTask(cancelled)
                        return@withLock
                    }

                    // Check pause
                    if (pauseRequested) {
                        val paused = current.copy(
                            status = TaskStatus.PAUSED,
                            currentStepIndex = i
                        )
                        _activeTask.value = paused
                        // Wait until resumed or cancelled
                        while (pauseRequested && !cancelRequested) {
                            delay(200)
                        }
                        if (cancelRequested) {
                            val cancelled = paused.copy(
                                status = TaskStatus.CANCELLED,
                                completedAt = System.currentTimeMillis(),
                                totalDurationMs = System.currentTimeMillis() - startMs
                            )
                            finishTask(cancelled)
                            return@withLock
                        }
                        // Resumed — update status
                        current = paused.copy(status = TaskStatus.RUNNING)
                        _activeTask.value = current
                    }

                    val step = current.steps[i]
                    val stepStart = System.currentTimeMillis()

                    // Mark step as running
                    val runningStep = step.copy(
                        status = StepStatus.RUNNING,
                        startedAt = stepStart
                    )
                    current = current.copy(
                        steps = current.steps.toMutableList().apply { set(i, runningStep) },
                        currentStepIndex = i
                    )
                    _activeTask.value = current

                    // Execute the step
                    try {
                        val result = withTimeout(120_000L) {
                            stepExecutor(step.command)
                        }

                        val stepEnd = System.currentTimeMillis()
                        val completedStep = if (result.success) {
                            runningStep.copy(
                                status = StepStatus.COMPLETED,
                                output = result.output,
                                completedAt = stepEnd,
                                durationMs = stepEnd - stepStart
                            )
                        } else {
                            runningStep.copy(
                                status = StepStatus.FAILED,
                                output = result.output,
                                error = result.error,
                                completedAt = stepEnd,
                                durationMs = stepEnd - stepStart
                            )
                        }

                        current = current.copy(
                            steps = current.steps.toMutableList().apply { set(i, completedStep) },
                            progress = (i + 1).toFloat() / current.steps.size
                        )
                        _activeTask.value = current

                        onStepComplete?.invoke(completedStep, result.output)

                    } catch (e: TimeoutCancellationException) {
                        val failedStep = runningStep.copy(
                            status = StepStatus.FAILED,
                            error = "Step timed out after 2 minutes",
                            completedAt = System.currentTimeMillis(),
                            durationMs = System.currentTimeMillis() - stepStart
                        )
                        current = current.copy(
                            steps = current.steps.toMutableList().apply { set(i, failedStep) },
                            progress = (i + 1).toFloat() / current.steps.size
                        )
                        _activeTask.value = current
                        onStepComplete?.invoke(failedStep, "Timed out")
                    } catch (e: CancellationException) {
                        throw e // Re-throw for proper cancellation
                    } catch (e: Exception) {
                        val failedStep = runningStep.copy(
                            status = StepStatus.FAILED,
                            error = e.message ?: "Unknown error",
                            completedAt = System.currentTimeMillis(),
                            durationMs = System.currentTimeMillis() - stepStart
                        )
                        current = current.copy(
                            steps = current.steps.toMutableList().apply { set(i, failedStep) },
                            progress = (i + 1).toFloat() / current.steps.size
                        )
                        _activeTask.value = current
                        onStepComplete?.invoke(failedStep, e.message ?: "")
                    }
                }

                // All steps done — determine final status
                val allOk = current.steps.all { it.status == StepStatus.COMPLETED }
                val finalStatus = if (allOk) TaskStatus.COMPLETED else TaskStatus.FAILED
                val completed = current.copy(
                    status = finalStatus,
                    completedAt = System.currentTimeMillis(),
                    totalDurationMs = System.currentTimeMillis() - startMs,
                    progress = 1f
                )
                finishTask(completed)
            }
        }
    }

    private fun finishTask(task: TaskPlan) {
        _activeTask.value = task
        val history = _taskHistory.value.toMutableList()
        history.add(0, task)
        if (history.size > 50) history.removeAt(history.size - 1)
        _taskHistory.value = history
    }

    // ── Controls ──────────────────────────────────────────────────────

    /** Pause execution after the current step completes. */
    fun pause() {
        val task = _activeTask.value ?: return
        if (task.status != TaskStatus.RUNNING) return
        pauseRequested = true
        Log.i(TAG, "Pause requested — will pause after current step")
    }

    /** Resume a paused task. */
    fun resume() {
        val task = _activeTask.value ?: return
        if (task.status != TaskStatus.PAUSED) return
        pauseRequested = false
        Log.i(TAG, "Resuming task execution")
    }

    /** Retry a specific failed step. */
    fun retryStep(stepIndex: Int) {
        val task = _activeTask.value ?: return
        if (!task.isTerminal) return
        val step = task.steps.getOrNull(stepIndex) ?: return
        if (step.status != StepStatus.FAILED) return

        // Reset the step and all subsequent steps
        val resetSteps = task.steps.mapIndexed { i, s ->
            if (i >= stepIndex) s.copy(
                status = StepStatus.PENDING,
                output = null,
                error = null,
                startedAt = null,
                completedAt = null,
                durationMs = 0L,
                retryCount = if (i == stepIndex) s.retryCount + 1 else s.retryCount
            ) else s
        }
        val restarted = task.copy(
            steps = resetSteps,
            status = TaskStatus.RUNNING,
            progress = stepIndex.toFloat() / task.steps.size,
            currentStepIndex = stepIndex,
            completedAt = null,
            totalDurationMs = 0L
        )
        execute(restarted)
    }

    /** Retry the entire task from the beginning. */
    fun retryAll() {
        val task = _activeTask.value ?: return
        if (!task.isTerminal) return
        val resetSteps = task.steps.map { it.copy(
            status = StepStatus.PENDING, output = null, error = null,
            startedAt = null, completedAt = null, durationMs = 0L
        )}
        val restarted = task.copy(
            steps = resetSteps,
            status = TaskStatus.RUNNING,
            progress = 0f,
            currentStepIndex = 0,
            completedAt = null,
            totalDurationMs = 0L
        )
        execute(restarted)
    }

    /** Cancel execution immediately. */
    fun cancel() {
        val task = _activeTask.value ?: return
        if (task.isTerminal) return
        cancelRequested = true
        currentJob?.cancel()
        Log.i(TAG, "Task cancellation requested")
    }

    /** Clear the current task (only if terminal). */
    fun clear() {
        if (_activeTask.value?.isTerminal == true) {
            _activeTask.value = null
        }
    }

    /** Clear task history. */
    fun clearHistory() {
        _taskHistory.value = emptyList()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    fun getTask(id: String): TaskPlan? =
        _taskHistory.value.find { it.id == id } ?: _activeTask.value?.takeIf { it.id == id }

    fun getHistory(): List<TaskPlan> = _taskHistory.value
}

/**
 * Result of executing a single task step.
 */
data class TaskStepResult(
    val success: Boolean,
    val output: String = "",
    val error: String = ""
)
