package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.examples.stage2d.MigrationEvent;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveEvaluator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2I.2 — experiment runner.
 *
 * <p>Structured configuration: a run is (ScenarioSpec, Method, seed). No run
 * is hard-coded; the caller (smoke test / future suite) supplies a list of
 * configurations. Every run executes through {@link Stage2iEngine} (the
 * common evaluation path) and enforces the pre/post invariants:
 * <ol>
 *   <li>Live fingerprint after method search == fingerprint of the initial
 *       placement (no search mutated live state).</li>
 *   <li>Migration events count == |final − initial| changed assignments.</li>
 *   <li>Final live placement == the placement that was evaluated.</li>
 *   <li>Objectives recorded come from the shared Stage 2F evaluator
 *       evaluated on the FINAL placement (with live state = initial, so f3's
 *       migration term is |final − initial| for every method).</li>
 * </ol>
 */
public final class Stage2iRunner {

    /** One experiment configuration. */
    public static final class RunConfig {
        public final Stage2iScenario.ScenarioSpec spec;
        public final Stage2iScenario.Method method;
        public final long seed;

        public RunConfig(Stage2iScenario.ScenarioSpec spec,
                         Stage2iScenario.Method method, long seed) {
            this.spec = spec;
            this.method = method;
            this.seed = seed;
        }

        public String runKey() {
            return spec.cellId() + "|" + method.label + "|" + seed;
        }
    }

    /**
     * Execute one run end-to-end and return the record (also appended to
     * {@code csvPath}).
     */
    public static Stage2iRunRecord runOne(RunConfig cfg, Path csvPath,
                                          boolean allowReplace)
            throws Exception {

        long t0 = System.nanoTime();

        // === Scenario (deterministic from real CSVs) ===
        Stage2iScenario.ScenarioData scenario =
                Stage2iScenario.build(cfg.spec);

        synchronized ((csvPath.toAbsolutePath() + ":engine").intern()) {
            // One engine per run; engine holds live CloudSim state
            Stage2iEngine engine = new Stage2iEngine(scenario);

            // === Shared initial placement (identical for every method) ===
            CandidatePlacement initial =
                    engine.computeInitialFfdPlacement();
            String initialFingerprint =
                    Stage2iEngine.fingerprint(initial.viewMapping());

            // Fresh hosts/VMs per run are guaranteed by scenario rebuild;
            // apply initial placement to live state (t=0 snapshot)
            engine.applyInitialPlacementToLiveState();
            String preSearchFingerprint = engine.liveFingerprint();

            // === Method search (HYPOTHETICAL) ===
            CandidatePlacement finalPlacement;
            Integer relocations = null;
            Integer searchAttempts = null;
            String notes = "";
            Stage2iProposedMethod.Result proposed = null;

            switch (cfg.method) {
                case PROPOSED: {
                    proposed = Stage2iProposedMethod.run(
                            engine, cfg.seed, cfg.spec.scale);
                    finalPlacement = proposed.finalPlacement;
                    notes = "NSSAO front1=" + proposed.front1RawSize
                            + " unique=" + proposed.uniquePlacements
                            + " stall=" + proposed.stoppedByStall;
                    break;
                }
                case B1_FFD_STATIC: {
                    Stage2iBaselines.BaselineResult r =
                            Stage2iBaselines.runB1FfdStatic(
                                    initial, engine.vms(), engine);
                    finalPlacement = r.getFinalPlacement();
                    relocations = r.getRelocations();
                    searchAttempts = r.getSearchAttempts();
                    notes = r.getNotes();
                    break;
                }
                case B2_BESTFIT_F4LS: {
                    Stage2iBaselines.BaselineResult r =
                            Stage2iBaselines.runB2BestFitF4LocalSearch(
                                    initial, engine.vms(), engine);
                    finalPlacement = r.getFinalPlacement();
                    relocations = r.getRelocations();
                    searchAttempts = r.getSearchAttempts();
                    notes = r.getNotes();
                    break;
                }
                case B3_RANDOM_RELOC: {
                    Stage2iBaselines.BaselineResult r =
                            Stage2iBaselines.runB3RandomRelocation(
                                    initial, engine.vms(), engine, cfg.seed);
                    finalPlacement = r.getFinalPlacement();
                    relocations = r.getRelocations();
                    searchAttempts = r.getSearchAttempts();
                    notes = r.getNotes();
                    break;
                }
                default:
                    throw new IllegalStateException("method " + cfg.method);
            }

            // === INVARIANT 1: search did not mutate live state ===
            String postSearchFingerprint = engine.liveFingerprint();
            if (!preSearchFingerprint.equals(postSearchFingerprint)) {
                throw new IllegalStateException(
                        "LIVE STATE MUTATED DURING SEARCH in " + cfg.runKey()
                                + " before=" + preSearchFingerprint
                                + " after=" + postSearchFingerprint);
            }

            // === INVARIANT 2 (proposed): selected placement feasible ===
            if (cfg.method == Stage2iScenario.Method.PROPOSED
                    && !engine.validate(finalPlacement).isEmpty()) {
                throw new IllegalStateException(
                        "Proposed final placement infeasible in "
                                + cfg.runKey());
            }

            // === INVARIANT 3 (baselines): final placement feasible ===
            if (cfg.method != Stage2iScenario.Method.PROPOSED
                    && !engine.validate(finalPlacement).isEmpty()) {
                throw new IllegalStateException(
                        "Baseline final placement infeasible in "
                                + cfg.runKey());
            }

            // === Physical application (migration events recorded) ===
            List<MigrationEvent> events =
                    engine.applyFinalPlacement(finalPlacement);

            // === INVARIANT 4: migration count == changed assignments ===
            int changed = Stage2iEngine.changedAssignmentCount(
                    initial.viewMapping(), finalPlacement.viewMapping());
            if (events.size() != changed) {
                throw new IllegalStateException(
                        "MIGRATION COUNT MISMATCH in " + cfg.runKey()
                                + ": events=" + events.size()
                                + " changed=" + changed);
            }

            // === INVARIANT 5: final live placement == evaluated placement ===
            String finalFingerprint = Stage2iEngine.fingerprint(
                    finalPlacement.viewMapping());
            if (!engine.liveFingerprint().equals(finalFingerprint)) {
                throw new IllegalStateException(
                        "FINAL PLACEMENT MISMATCH in " + cfg.runKey()
                                + ": live=" + engine.liveFingerprint()
                                + " expected=" + finalFingerprint);
            }

            // === COMMON EVALUATION PATH: objectives on FINAL placement ===
            // Evaluated with current state = INITIAL placement (the shared
            // starting point), so f3's migration term = |final − initial|
            // for EVERY method — identical semantics across methods.
            ObjectiveEvaluator.EvaluationResult er =
                    evaluateFinalWithMigrationSemantics(
                            engine, initial, finalPlacement, events.size());
            if (er == null) {
                throw new IllegalStateException(
                        "evaluation failed in " + cfg.runKey());
            }

            // Consistency: evaluator's migration term == actual events
            if (er.getMigrationCount() != events.size()) {
                throw new IllegalStateException(
                        "EVALUATOR MIGRATION TERM MISMATCH in "
                                + cfg.runKey() + ": evaluator="
                                + er.getMigrationCount() + " events="
                                + events.size());
            }

            long t1 = System.nanoTime();

            // === Record ===
            Stage2iRunRecord.Builder rb = new Stage2iRunRecord.Builder()
                    .dataset(cfg.spec.dataset.label)
                    .scenarioCell(cfg.spec.cellId())
                    .profile(cfg.spec.profile.name())
                    .scale(cfg.spec.scale.name())
                    .method(cfg.method.label)
                    .seed(cfg.seed)
                    .vmCount(scenario.vmCount())
                    .hostCount(scenario.hostCount())
                    .adaptiveThreshold(scenario.adaptiveThreshold)
                    .objectives(er.getObjectives())
                    .migrationCount(events.size())
                    .migrationCost(events.size()
                            * Stage2iEngine.PER_MIGRATION_COST)
                    .activeHosts(engine.liveActiveHostCount())
                    .cpuUtilization(engine.liveCpuUtilization())
                    .memoryUtilization(engine.liveMemoryUtilization())
                    .overallUtilization(engine.liveOverallUtilization())
                    .totalCoResidencyDuration(
                            engine.totalCoResidencyDuration())
                    .highThreatCoResidencyDuration(
                            engine.highThreatCoResidencyDuration())
                    .coResidencyEpisodeCount(
                            engine.coResidencyEpisodeCount())
                    .initialFingerprint(initialFingerprint)
                    .finalFingerprint(finalFingerprint)
                    .runtimeMs((t1 - t0) / 1_000_000L)
                    .notes(notes);

            if (cfg.method == Stage2iScenario.Method.PROPOSED) {
                rb.nssaoIterations(proposed.nssaoIterations)
                        .stoppedByStall(proposed.stoppedByStall)
                        .rejectedDecodes(proposed.rejectedDecodeCount)
                        .front1RawSize(proposed.front1RawSize)
                        .uniquePlacements(proposed.uniquePlacements)
                        .duplicatesRemoved(proposed.duplicatesRemoved)
                        .relocations(events.size())
                        .searchAttempts(null);
            } else {
                rb.nssaoIterations(null).stoppedByStall(null)
                        .rejectedDecodes(null).front1RawSize(null)
                        .uniquePlacements(null).duplicatesRemoved(null)
                        .relocations(relocations)
                        .searchAttempts(searchAttempts);
            }

            Stage2iRunRecord record = rb.build();
            Stage2iRunRecord.appendTo(csvPath, record, allowReplace);
            return record;
        }
    }

    /**
     * Evaluate the FINAL placement with f1–f4 semantics identical to a
     * Stage 2F evaluation where the CURRENT state is the INITIAL placement
     * (i.e., f3's migration term = |final − initial|).
     *
     * <p>Implementation: we temporarily remove the final placement's guest
     * list changes by evaluating on a COPY of the scenario hosts with the
     * initial placement applied, so the evaluator's live-read migration
     * counter sees initial state. This is a pure read-path construction —
     * the engine's real live state is untouched.
     */
    private static ObjectiveEvaluator.EvaluationResult
    evaluateFinalWithMigrationSemantics(
            Stage2iEngine engine,
            CandidatePlacement initial,
            CandidatePlacement finalPlacement,
            int actualMigrationCount) {

        // Rebuild shadow hosts (fresh instances, same descriptors) with the
        // INITIAL placement applied, so the evaluator's internal migration
        // counting (candidate vs live) reproduces |final − initial|.
        List<org.cloudbus.cloudsim.Host> shadowHosts = new ArrayList<>();
        Map<Integer, org.cloudbus.cloudsim.Host> shadowById =
                new LinkedHashMap<>();
        for (org.cloudbus.cloudsim.Host h : engine.hosts()) {
            double mips = 0;
            for (org.cloudbus.cloudsim.Pe pe : h.getPeList()) {
                mips += pe.getMips();
            }
            org.cloudbus.cloudsim.Host shadow =
                    Stage2iScenario.createHost(h.getId(),
                            (int) Math.round(mips), h.getRam());
            shadowHosts.add(shadow);
            shadowById.put(shadow.getId(), shadow);
        }
        for (Map.Entry<Integer, Integer> e
                : initial.viewMapping().entrySet()) {
            shadowById.get(e.getValue()).getGuestList()
                    .add(findVm(engine.vms(), e.getKey()));
        }

        ObjectiveEvaluator shadowEvaluator = new ObjectiveEvaluator(
                new org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel(
                        0.1, 0.3),
                Stage2iEngine.PER_HOST_COST,
                Stage2iEngine.PER_MIGRATION_COST);
        return shadowEvaluator.evaluate(
                new CandidatePlacement(finalPlacement.viewMapping(),
                        shadowHosts),
                engine.vms(), engine.threatMap());
    }

    private static org.cloudbus.cloudsim.Vm findVm(
            List<org.cloudbus.cloudsim.Vm> vms, int vmId) {
        for (org.cloudbus.cloudsim.Vm vm : vms) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalArgumentException("VM not found: " + vmId);
    }

    private Stage2iRunner() {
    }
}
