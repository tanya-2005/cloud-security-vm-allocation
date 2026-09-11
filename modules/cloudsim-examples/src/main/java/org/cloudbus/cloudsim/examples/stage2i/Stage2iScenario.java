package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.examples.stage2b.EntityVmMapping;
import org.cloudbus.cloudsim.examples.stage2b.ThreatRecord;
import org.cloudbus.cloudsim.examples.stage2b.ThreatScoreCsvReader;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2e.AdaptiveThreatThreshold;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stage 2I.2 — deterministic scenario construction.
 *
 * <p>Implements the Stage 2I.1 approved scenario rules with NO synthetic
 * threat scores: every VM's score is a real entity score from the Python
 * pipeline outputs ({@code azure_to_azure_scores.csv} /
 * {@code alibaba_to_alibaba_scores.csv}), sliced by the approved profiles.
 *
 * <h3>Profile slicing (Stage 2I.1 §4.1, concrete instantiation)</h3>
 * Entities are sorted by (score asc, entityId asc) — a total deterministic
 * order. With N_ds = dataset size and V = the profile's VM count:
 * <ul>
 *   <li><b>A (low):</b> first ceil(0.4·V_ref) entities of the sorted list,
 *       where V_ref is the scale's reference VM count (M = 24).</li>
 *   <li><b>B (moderate):</b> middle pool = ranks [round(0.3·N_ds),
 *       round(0.7·N_ds)]; take the first V entities of that pool.</li>
 *   <li><b>C (high):</b> last ceil(0.4·V_ref) entities (kept in ascending
 *       order).</li>
 *   <li><b>D (co-residency):</b> middle pool as in B, but V = 4× the scale's
 *       reference VM count (M: 32 VMs on the 8 M hosts). If the pool is
 *       smaller than V it is expanded symmetrically (one rank below, one
 *       above, alternating) until V or dataset exhaustion.</li>
 *   <li><b>E (resource):</b> middle pool as in B with V = scale reference
 *       count; the host fleet is solved deterministically so that aggregate
 *       VM demand is 85–95% of aggregate host capacity.</li>
 * </ul>
 * Where a dataset cannot supply V entities, the largest available count is
 * used and recorded (vmCount column); this only affects Alibaba D/L cells.
 *
 * <h3>Host fleets (Stage 2I.1 §4.2/§4.3)</h3>
 * Two host classes, identical to the Stage 2H validation classes:
 * LARGE = 1 PE × 2000 MIPS, 4096 RAM; SMALL = 1 PE × 1000 MIPS, 2048 RAM.
 * Fleet order is all LARGE hosts first, then SMALL (host IDs 0..n−1) —
 * this order matters for FFD and is fixed. Scale fleets:
 * S = 1L+2S (3 hosts), M = 2L+6S (8 hosts), L = 4L+12S (16 hosts).
 * Profile E solves its own fleet (fewest hosts, then fewest LARGE, with
 * capacity ratio in [0.85, 0.95]).
 *
 * <h3>VM resources</h3>
 * Every experiment VM is 250 MIPS / 256 RAM (1 PE). This size is chosen so
 * profile D is feasible at the approved host counts (32×250 = 8000 MIPS on
 * the 10000-MIPS M fleet = 80%). Identical for every method (fairness).
 *
 * <h3>Adaptive threshold</h3>
 * Computed per scenario from the scenario slice's score population via the
 * Stage 2E {@link AdaptiveThreatThreshold} defaults (tailWeight 0.5, bounds
 * [0.5, 0.9]). It is a scenario property — identical for all methods.
 */
public final class Stage2iScenario {

    // === Datasets ===
    public enum Dataset {
        AZURE("azure", "azure_to_azure_scores.csv"),
        ALIBABA("alibaba", "alibaba_to_alibaba_scores.csv");

        public final String label;
        public final String csvFile;

        Dataset(String label, String csvFile) {
            this.label = label;
            this.csvFile = csvFile;
        }
    }

    // === Threat-pressure profiles (Stage 2I.1 §4.1) ===
    public enum Profile { A, B, C, D, E }

    // === Scales (Stage 2I.1 §4.2) ===
    public enum Scale {
        S(8, 3), M(24, 8), L(48, 16);

        public final int referenceVmCount;
        public final int hostCount;

        Scale(int referenceVmCount, int hostCount) {
            this.referenceVmCount = referenceVmCount;
            this.hostCount = hostCount;
        }
    }

    // === Methods ===
    public enum Method {
        PROPOSED("proposed_nssao"),
        B1_FFD_STATIC("b1_ffd_static"),
        B2_BESTFIT_F4LS("b2_bestfit_f4_local_search"),
        B3_RANDOM_RELOC("b3_random_feasible_relocation");

        public final String label;

        Method(String label) {
            this.label = label;
        }
    }

    // === Fixed resource constants (identical for every method) ===
    public static final int VM_MIPS = 250;
    public static final int VM_RAM = 256;
    public static final int VM_BW = 1000;
    public static final long VM_SIZE = 1000L;
    private static final int LARGE_MIPS = 2000;
    private static final int LARGE_RAM = 4096;
    private static final int SMALL_MIPS = 1000;
    private static final int SMALL_RAM = 2048;
    private static final long HOST_STORAGE = 1_000_000L;
    private static final long HOST_BW = 1_000_000L;

    private static final double E_RATIO_MIN = 0.85;
    private static final double E_RATIO_MAX = 0.95;

    /** A/C slice size uses the M-scale reference count (2I.1 §4.1). */
    private static final int V_REF = Scale.M.referenceVmCount;

    // === Scenario cell specification ===
    public static final class ScenarioSpec {
        public final Dataset dataset;
        public final Profile profile;
        public final Scale scale;

        public ScenarioSpec(Dataset dataset, Profile profile, Scale scale) {
            this.dataset = dataset;
            this.profile = profile;
            this.scale = scale;
        }

        public String cellId() {
            return profile + "-" + dataset.label + "-" + scale.name();
        }
    }

    // === Built scenario (immutable once constructed) ===
    public static final class ScenarioData {
        public final ScenarioSpec spec;
        public final List<Host> hosts;
        public final List<Vm> vms;
        public final Map<Integer, VmThreatState> threatMap;
        public final double adaptiveThreshold;
        public final int entityPoolSize;
        public final boolean poolExpanded;

        ScenarioData(ScenarioSpec spec, List<Host> hosts, List<Vm> vms,
                     Map<Integer, VmThreatState> threatMap,
                     double adaptiveThreshold, int entityPoolSize,
                     boolean poolExpanded) {
            this.spec = spec;
            this.hosts = hosts;
            this.vms = vms;
            this.threatMap = threatMap;
            this.adaptiveThreshold = adaptiveThreshold;
            this.entityPoolSize = entityPoolSize;
            this.poolExpanded = poolExpanded;
        }

        public int vmCount() {
            return vms.size();
        }

        public int hostCount() {
            return hosts.size();
        }
    }

    // === CSV cache ===
    private static final Map<String, List<ThreatRecord>> CSV_CACHE =
            new LinkedHashMap<>();

    /**
     * Resolve the directory holding the threat-score CSVs. Search order:
     * system property {@code stage2i.data.dir}, then {@code project/outputs}
     * relative to the CWD, then {@code ../project/outputs} (running from the
     * cloudsim repo), then {@code ../../project/outputs}.
     */
    public static Path resolveDataDir() {
        String prop = System.getProperty("stage2i.data.dir");
        if (prop != null && !prop.isEmpty()) {
            return Paths.get(prop);
        }
        for (String candidate : new String[] {
                "project/outputs", "../project/outputs", "../../project/outputs"}) {
            Path p = Paths.get(candidate);
            if (Files.exists(p.resolve(Dataset.AZURE.csvFile))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "Cannot locate threat-score CSVs. Set -Dstage2i.data.dir=<dir with "
                        + Dataset.AZURE.csvFile + ">");
    }

    private static synchronized List<ThreatRecord> loadDataset(Dataset dataset)
            throws IOException {
        String key = dataset.name();
        if (CSV_CACHE.containsKey(key)) {
            return CSV_CACHE.get(key);
        }
        Path csv = resolveDataDir().resolve(dataset.csvFile);
        List<ThreatRecord> records = new ThreatScoreCsvReader(csv).read();
        CSV_CACHE.put(key, records);
        return records;
    }

    /**
     * Build a scenario deterministically from the real score CSVs.
     */
    public static ScenarioData build(ScenarioSpec spec) throws IOException {
        List<ThreatRecord> all = loadDataset(spec.dataset);

        // Deterministic total order: score asc, then entityId asc
        List<ThreatRecord> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingDouble(ThreatRecord::getThreatScore)
                .thenComparing(ThreatRecord::getEntityId)
                .thenComparing(ThreatRecord::getEntityType));

        int nDs = sorted.size();
        int lo = (int) Math.round(0.3 * nDs);
        int hi = (int) Math.round(0.7 * nDs);
        // Middle pool (inclusive bounds, clamped)
        lo = Math.max(0, Math.min(lo, nDs - 1));
        hi = Math.max(lo, Math.min(hi, nDs - 1));

        List<ThreatRecord> slice;
        int requested;
        boolean expanded = false;

        switch (spec.profile) {
            case A: {
                requested = (int) Math.ceil(0.4 * V_REF);
                requested = Math.min(requested, nDs);
                slice = new ArrayList<>(sorted.subList(0, requested));
                break;
            }
            case C: {
                requested = (int) Math.ceil(0.4 * V_REF);
                requested = Math.min(requested, nDs);
                slice = new ArrayList<>(
                        sorted.subList(nDs - requested, nDs));
                break;
            }
            case B:
            case D:
            case E: {
                requested = spec.profile == Profile.D
                        ? dProfileVmCount(spec.scale)
                        : spec.scale.referenceVmCount;
                List<ThreatRecord> pool =
                        new ArrayList<>(sorted.subList(lo, hi + 1));
                if (pool.size() < requested) {
                    // Symmetric expansion around the middle pool
                    expanded = true;
                    int l = lo - 1;
                    int h = hi + 1;
                    boolean addLow = true;
                    while (pool.size() < requested
                            && (l >= 0 || h <= nDs - 1)) {
                        if (addLow && l >= 0) {
                            pool.add(0, sorted.get(l));
                            l--;
                        } else if (h <= nDs - 1) {
                            pool.add(sorted.get(h));
                            h++;
                        } else if (l >= 0) {
                            pool.add(0, sorted.get(l));
                            l--;
                        }
                        addLow = !addLow;
                    }
                }
                requested = Math.min(requested, pool.size());
                slice = new ArrayList<>(pool.subList(0, requested));
                break;
            }
            default:
                throw new IllegalStateException("Unhandled profile " + spec.profile);
        }

        if (slice.isEmpty()) {
            throw new IllegalStateException(
                    "Empty slice for " + spec.cellId());
        }

        // === Host fleet ===
        List<int[]> fleet = spec.profile == Profile.E
                ? solveEFleet(slice.size() * VM_MIPS)
                : standardFleet(spec.scale, slice.size());

        List<Host> hosts = new ArrayList<>();
        int hostId = 0;
        for (int[] f : fleet) {
            hosts.add(createHost(hostId++, f[0], f[1]));
        }

        // === VMs + threat states (one VM per entity, VM ID = slice index) ===
        EntityVmMapping mapping = new EntityVmMapping(0);
        mapping.buildFrom(slice);

        List<Vm> vms = new ArrayList<>();
        Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();
        for (ThreatRecord record : slice) {
            int vmId = mapping.getMetadata(record.toCompositeKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Mapping missing for entity " + record.getEntityId()))
                    .getCloudSimVmId();
            vms.add(createVm(vmId));
            threatMap.put(vmId, new VmThreatState(
                    vmId,
                    record.getThreatScore(),
                    record.getDataset(),
                    record.getEntityType(),
                    record.getEntityId(),
                    adaptiveThresholdFor(slice)));
        }

        double threshold = adaptiveThresholdFor(slice);

        return new ScenarioData(spec, hosts, vms, threatMap, threshold,
                hi - lo + 1, expanded);
    }

    /**
     * D-profile VM count: 4 x the scale's reference count, capped at what
     * the scale's STANDARD fleet supports at <= 80% CPU utilization. The cap
     * keeps the host fleet (and thus the host set) IDENTICAL across datasets
     * for the same profile/scale — a fairness requirement — while
     * guaranteeing FFD feasibility (32 x 250 = 8000 MIPS = 80% of the M
     * fleet's 10000 MIPS).
     */
    private static int dProfileVmCount(Scale scale) {
        double fleetMips = 0;
        for (int[] h : standardFleet(scale, 0)) {
            fleetMips += h[0];
        }
        int maxByCapacity = (int) Math.floor(0.8 * fleetMips / VM_MIPS);
        return Math.min(4 * scale.referenceVmCount, maxByCapacity);
    }

    /**
     * Scenario-level adaptive threshold (Stage 2E defaults) from the slice's
     * score population. Computed independently of any method.
     */
    public static double adaptiveThresholdFor(List<ThreatRecord> slice) {
        List<Double> scores = slice.stream()
                .map(ThreatRecord::getThreatScore)
                .collect(Collectors.toList());
        return new AdaptiveThreatThreshold().compute(scores);
    }

    /**
     * Standard fleet for a scale: reference composition, unless the VM count
     * requires the D-profile adjustment (VM count > scale reference but the
     * fleet stays at the scale's host count).
     */
    private static List<int[]> standardFleet(Scale scale, int vmCount) {
        // Composition by host count: S=1L+2S, M=2L+6S, L=4L+12S
        int larges;
        switch (scale) {
            case S: larges = 1; break;
            case M: larges = 2; break;
            case L: larges = 4; break;
            default: throw new IllegalStateException("scale " + scale);
        }
        int smalls = scale.hostCount - larges;
        List<int[]> fleet = new ArrayList<>();
        for (int i = 0; i < larges; i++) {
            fleet.add(new int[] {LARGE_MIPS, LARGE_RAM});
        }
        for (int i = 0; i < smalls; i++) {
            fleet.add(new int[] {SMALL_MIPS, SMALL_RAM});
        }
        return fleet;
    }

    /**
     * Deterministic E-profile fleet solver: fewest total hosts, then fewest
     * LARGE hosts, such that aggregate VM CPU demand is in
     * [85%, 95%] of aggregate host CPU capacity. RAM is checked to be
     * sufficient (it always is at these ratios).
     */
    static List<int[]> solveEFleet(double aggVmMips) {
        double minCap = aggVmMips / E_RATIO_MAX;
        double maxCap = aggVmMips / E_RATIO_MIN;
        for (int total = 1; total <= 32; total++) {
            for (int larges = 0; larges <= total; larges++) {
                double cap = larges * (double) LARGE_MIPS
                        + (total - larges) * (double) SMALL_MIPS;
                if (cap >= minCap && cap <= maxCap) {
                    double ram = larges * (double) LARGE_RAM
                            + (total - larges) * (double) SMALL_RAM;
                    // RAM feasibility guard (demand = VM_MIPS/2 per VM roughly)
                    if (ram >= aggVmMips / 2.0) {
                        List<int[]> fleet = new ArrayList<>();
                        for (int i = 0; i < larges; i++) {
                            fleet.add(new int[] {LARGE_MIPS, LARGE_RAM});
                        }
                        for (int i = 0; i < total - larges; i++) {
                            fleet.add(new int[] {SMALL_MIPS, SMALL_RAM});
                        }
                        return fleet;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "No E-profile fleet found for aggregate VM MIPS " + aggVmMips);
    }

    // === Entity construction (same classes as Stage 2H validation) ===

    public static Host createHost(int hostId, int mips, int ram) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(new PeProvisionerSimple(mips)));
        return new Host(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(HOST_BW),
                HOST_STORAGE,
                peList,
                new VmSchedulerTimeShared(peList)
        );
    }

    public static Vm createVm(int vmId) {
        return new Vm(vmId, 0, VM_MIPS, 1, VM_RAM, VM_BW, VM_SIZE, "Xen",
                new CloudletSchedulerTimeShared());
    }

    private Stage2iScenario() {
    }
}
