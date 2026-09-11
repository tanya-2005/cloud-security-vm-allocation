package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.examples.stage2d.MigrationEvent;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;
import org.cloudbus.cloudsim.examples.stage2g.NssaoConfig;
import org.cloudbus.cloudsim.examples.stage2g.NssaoOptimizer;
import org.cloudbus.cloudsim.examples.stage2h.InitialPlacementStrategy;
import org.cloudbus.cloudsim.examples.stage2h.ParetoSolutionSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2I.2 — proposed-method runner (NSSAO pipeline via public APIs).
 *
 * <p>Executes the frozen Stage 2F/2G/2H pipeline INSIDE the shared engine:
 * FFD initial placement (applied to live state by the engine), NSSAO
 * optimization on hypothetical candidates only (Stage 2G optimizer, unchanged),
 * deterministic final-solution selection (Stage 2H
 * {@link ParetoSolutionSelector}: lexicographic min (f2, f3, f1, f4) with
 * index tie-break after placement deduplication), then the engine applies
 * the final placement physically (Stage 2D service).
 *
 * <p>NO Stage 2B–2H file is modified: the proposed method is composed from
 * the existing public classes. The one adaptation vs Stage 2H's orchestrator
 * is that the scenario's adaptive threshold (not the NssaoConfig default)
 * parameterizes the tracker — the engine owns the tracker for ALL methods,
 * so the threshold is identical everywhere (fairness rule).
 */
public final class Stage2iProposedMethod {

    private Stage2iProposedMethod() {
    }

    public static final class Result {
        public final CandidatePlacement finalPlacement;
        public final ObjectiveValues objectives;
        public final int nssaoIterations;
        public final boolean stoppedByStall;
        public final int rejectedDecodeCount;
        public final int front1RawSize;
        public final int uniquePlacements;
        public final int duplicatesRemoved;
        public final List<MigrationEvent> migrationEvents;

        Result(CandidatePlacement finalPlacement, ObjectiveValues objectives,
               NssaoOptimizer.RunResult runResult,
               ParetoSolutionSelector.SelectionResult selection,
               List<MigrationEvent> migrationEvents) {
            this.finalPlacement = finalPlacement;
            this.objectives = objectives;
            this.nssaoIterations = runResult.getIterationsExecuted();
            this.stoppedByStall = runResult.isStoppedByStall();
            this.rejectedDecodeCount = runResult.getRejectedDecodeCount();
            this.front1RawSize = selection.getFront1RawSize();
            this.uniquePlacements = selection.getUniquePlacements();
            this.duplicatesRemoved = selection.getDuplicatesRemoved();
            this.migrationEvents = migrationEvents;
        }
    }

    /**
     * Run the proposed pipeline on the engine's already-applied initial
     * placement.
     *
     * @param engine   shared engine with initial placement applied
     * @param seed     scenario seed for NSSAO
     * @param scale    unused placeholder for future scale-dependent settings;
     *                 M/L both use Stage 2G defaults per Stage 2I.1 §4.5
     */
    public static Result run(Stage2iEngine engine, long seed,
                             Stage2iScenario.Scale scale) throws Exception {
        List<org.cloudbus.cloudsim.Host> hosts = engine.hosts();
        List<org.cloudbus.cloudsim.Vm> vms = engine.vms();
        CandidatePlacement initial = engine.initialPlacement();

        // Movable set = all VM IDs, ascending (Stage 2H convention)
        List<Integer> movable = new ArrayList<>();
        for (org.cloudbus.cloudsim.Vm vm : vms) {
            movable.add(vm.getId());
        }
        movable.sort(Integer::compareTo);

        // Stage 2G defaults (Stage 2I.1 §4.5: no parameter sweep)
        NssaoConfig config = NssaoConfig.builder()
                .seed(seed)
                .populationSize(40)
                .maxIterations(10)
                .stallLimit(3)
                .build();

        NssaoOptimizer optimizer = new NssaoOptimizer(
                hosts, vms, movable, initial.viewMapping(),
                engine.threatMap(), engine.evaluatorRef(), config);
        NssaoOptimizer.RunResult runResult = optimizer.run();

        ParetoSolutionSelector selector = new ParetoSolutionSelector();
        ParetoSolutionSelector.SelectionResult selection = selector.select(runResult);

        CandidatePlacement finalPlacement = selection.getSelectedPlacement();

        // Feasibility gate before the runner's physical application
        // (defensive; NSSAO candidates are feasibility-checked already)
        if (!engine.validate(finalPlacement).isEmpty()) {
            throw new IllegalStateException(
                    "NSSAO-selected placement infeasible: "
                            + engine.validate(finalPlacement));
        }

        // NOTE: physical application is the RUNNER's job (identical for
        // every method). This method is search + selection only.

        ObjectiveValues objectives = engine.evaluate(initial)
                .getObjectives(); // informational only; runner recomputes

        return new Result(finalPlacement, objectives, runResult, selection,
                java.util.Collections.emptyList());
    }
}
