package io.jobclaw.agent.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlanExecutionStep {

    private final PlanStep step;
    private PlanStepStatus status;
    private final List<StepEvidence> evidence;

    public PlanExecutionStep(PlanStep step) {
        this.step = step;
        this.status = PlanStepStatus.PENDING;
        this.evidence = new ArrayList<>();
    }

    public synchronized PlanStep step() {
        return step;
    }

    public synchronized PlanStepStatus status() {
        return status;
    }

    public synchronized void markRunning() {
        if (status == PlanStepStatus.PENDING || status == PlanStepStatus.FAILED) {
            status = PlanStepStatus.RUNNING;
        }
    }

    public synchronized void markCompleted() {
        status = PlanStepStatus.COMPLETED;
    }

    public synchronized void markFailed() {
        status = PlanStepStatus.FAILED;
    }

    public synchronized void addEvidence(StepEvidence item) {
        if (item != null) {
            evidence.add(item);
        }
    }

    public synchronized List<StepEvidence> evidence() {
        return Collections.unmodifiableList(new ArrayList<>(evidence));
    }
}
