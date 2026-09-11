# Stage 2D — VM Migration + Co-residency Duration

**Status: COMPLETE**

**Date: 2026-09-10**

## Purpose

Implement the minimum machinery required to model VM migration and measure
co-residency duration in the CloudSim simulation.

This is NOT optimization. It establishes migration state transitions and
duration measurement that later stages (NSSAO, adaptive threshold) will build on.

## Files Created

### Java Classes (in `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2d/`)

| File | Purpose |
|------|---------|
| `MigrationEvent.java` | Represents a VM migration event (source→dest, timing, VM reference) |
| `VmMigrationService.java` | Executes VM migration between hosts, records events, preserves metadata |
| `CoResidencyDurationTracker.java` | Tracks co-residency duration across migration events |
| `MigrationCostModel.java` | Configurable migration cost abstraction |
| `Stage2dMigrationValidation.java` | Main validation runner with deterministic scenario |

### Modified Files

| File | Change |
|------|--------|
| `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2c/SecurityStateSnapshot.java` | Extended to support migration count, migration cost, co-residency duration data |
| `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2d/VmMigrationService.java` | Imports VmThreatState from stage2c |
| `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2d/CoResidencyDurationTracker.java` | Imports VmThreatState from stage2c |

## Migration Model

### MigrationEvent

Represents a single VM migration:

```
MigrationEvent {
    int vmId                 // CloudSim VM ID
    int sourceHostId         // Source host ID
    int destinationHostId    // Destination host ID
    double migrationStartTime // Simulation time when migration starts
    double migrationTimestamp  // Simulation time when migration completes
    Vm migratedVm            // Reference to the migrated VM (for metadata verification)
}
```

**Timing model:** Stage 2D treats migration as effectively instantaneous at the
simulation level. `migrationStartTime == migrationTimestamp` for the default
`instantaneous()` factory method. This is a SIMULATION ABSTRACTION — real
migrations involve downtime, network transfer, memory dirty-page iteration, etc.

### VmMigrationService

Responsibilities:
- Validate migration requests (VM exists, source has VM, destination exists)
- Move VM between host guest lists
- Preserve VM threat metadata (VmThreatState NOT affected by migration)
- Record MigrationEvent for each migration
- Reject invalid migrations:
  - Same-host migration (source == destination)
  - VM not on source host
  - VM not in VmThreatState map
  - Null VM/host

#### CloudSim Resource State Update (OPTION A found)

CloudSim 7.0.1 provides a correct API path for migration through the
`HostEntity` interface:

**Deallocate from source host:**
```java
sourceHost.guestDeallocate(vm);  // frees RAM, BW, MIPS via provisioners + scheduler
sourceHost.getGuestList().remove(vm);
vm.setHost(null);
```

**Allocate to destination host:**
```java
destinationHost.guestCreate(vm);  // allocates RAM, BW, MIPS via provisioners + scheduler, adds to guest list
```

This is the same mechanism used internally by CloudSim's migration code
(`VmAllocationPolicy.java`, `PowerVmAllocationPolicyMigrationAbstract.java`).

**Why the current implementation does NOT use this:**
- The current Stage 2D validation uses manually constructed hosts (not part of
  a running CloudSim datacenter)
- The validation demonstrates migration state transitions for testing purposes
- Using `guestCreate`/`guestDeallocate` would require the hosts to be part of
  a fully initialized CloudSim simulation with proper VM requests
- The current guest-list-only approach is sufficient for validating the
  co-residency duration tracking algorithm, which is the primary goal of Stage 2D

**For Stage 2E+ (NSSAO) and production simulations:**
- If hosts are part of a full CloudSim datacenter simulation, the proper
  `guestCreate`/`guestDeallocate` API path SHOULD be used to maintain
  CloudSim's internal resource accounting state correctly
- The current implementation should be considered a VALIDATION/ABSTRACTION layer
  for testing duration tracking, not a production migration implementation

### Current Approach

The current implementation modifies only the host guest list directly.
This is a VALIDATION ABSTRACTION that is sufficient for the Stage 2D
co-residency duration tracking tests. For full CloudSim integration in
later stages, the proper CloudSim allocation APIs should be used.

## Co-residency Duration Definition

### Concept

Co-residency duration measures how long a pair of VMs from different entities
remains co-resident on the same simulated physical host.

```
Time t0: VM A and VM B placed on Host 1 → co-residency starts
Time t1: VM B migrates to Host 2 → co-residency ends
Duration = t1 - t0
```

### CoResidencyDurationTracker

**Operation (multi-snapshot comparison algorithm):**
1. `recordSnapshot(hosts, simulationTime)` — snapshot current co-residency state
2. Call after initial placement (t=0) and after each migration
3. Compares consecutive snapshot pairs (t_{k-1} -> t_k) to detect:
   - **START**: pair present in current but not in previous → episode starts at t_k
   - **END**: pair in previous but not in current → episode ends at t_k
   - **CONTINUE**: pair in both → existing episode continues

**Pair identity includes host:** (vmId1, vmId2, hostId). A VM pair moving
to a different host ends the old host-specific episode and starts a new one.

**Multiple episodes for same pair:** Supported. The same VM pair can have
multiple separate episodes (e.g., A-B on Host 0: 0→100, then B returns:
200→later). These are tracked as separate episodes, NOT merged.

### Multi-Snapshot Validation Scenario

```
t=0:
  Host 0: VM A (high threat, entity-1) + VM B (normal, entity-2)
  Host 1: VM C (entity-3) + VM D (entity-4)

t=100: VM B migrates Host 0 → Host 1
  Host 0: VM A (alone)
  Host 1: VM B + VM C + VM D

t=200: VM B migrates Host 1 → Host 0
  Host 0: VM A + VM B
  Host 1: VM C + VM D
```

### Expected Episodes

| Episode | VM Pair | Host | Type | Start | End | Duration |
|---------|---------|------|------|-------|-----|----------|
| A-B #1 | A(10)-B(11) | 0 | HT-NORMAL | 0 | 100 | 100 |
| B-C | B(11)-C(12) | 1 | NORMAL | 100 | 200 | 100 |
| B-D | B(11)-D(13) | 1 | NORMAL | 100 | 200 | 100 |
| C-D | C(12)-D(13) | 1 | NORMAL | 0 | 200 | 200 |
| A-B #2 | A(10)-B(11) | 0 | HT-NORMAL | 200 | 200(ongoing) | 0 |

### Same-Pair Multiple Episodes Test

The A-B pair on Host 0 has TWO separate episodes:
- Episode 1: t=0 → t=100, duration=100 (B migrates away)
- Episode 2: t=200 → ongoing (B returns to Host 0)

These are tracked as TWO separate episodes, NOT merged into one. This
proves the tracker correctly handles episodes that start after the first
snapshot and episodes that restart after ending.

### Co-residency Types

- **NORMAL_NORMAL**: both VMs have threat < threshold
- **HIGH_THREAT_NORMAL**: one VM is high-threat, other is normal
- **HIGH_THREAT_HIGH_THREAT**: both VMs are high-threat

Uses existing VmThreatState metadata and the configured threshold.

### Important

**Co-residency duration is measured from simulated CloudSim placement/migration
events, NOT inferred from Azure or Alibaba trace timestamps.**

The public datasets do NOT provide physical host placement. Duration comes
entirely from CloudSim simulation state.

## Migration Cost Definition

### MigrationCostModel

```
Migration cost = migrationCount * perMigrationCost
```

- `perMigrationCost`: configurable simulation parameter (default: 1.0)
- `migrationCount`: from recorded MigrationEvents

### Important

**Migration cost is a configurable simulation abstraction UNLESS supported by
measured CloudSim quantities.**

In Stage 2D, we use a simple configurable per-migration cost because the
current CloudSim setup does NOT provide real migration cost data (downtime,
network bandwidth, memory copy overhead, energy consumption, etc.).

Real migration cost factors NOT modeled:
- VM downtime during migration
- Network bandwidth consumption
- Memory dirty-page iteration overhead
- Storage migration cost
- Energy consumption

These can be added in later stages if needed.

## Validation Scenario (Multi-Snapshot)

### Initial Placement (t=0)

```
Host 0: VM A (threat=0.85, entity-1-high, HIGH THREAT) + VM B (threat=0.20, entity-2-normal)
Host 1: VM C (threat=0.30, entity-3) + VM D (threat=0.10, entity-4)
```

Co-residency at t=0:
- A-B pair: cross-entity, HIGH_THREAT_NORMAL (A is high-threat)
- C-D pair: cross-entity, NORMAL_NORMAL

### Migration 1 (t=100): VM B: Host 0 → Host 1

### Post-Migration 1 Placement (t=100)

```
Host 0: VM A (threat=0.85, entity-1-high) — ALONE now
Host 1: VM B (threat=0.20, entity-2-normal) + VM C (threat=0.30, entity-3) + VM D (threat=0.10, entity-4)
```

### Migration 2 (t=200): VM B: Host 1 → Host 0

### Post-Migration 2 Placement (t=200)

```
Host 0: VM A (threat=0.85) + VM B (threat=0.20)
Host 1: VM C (threat=0.30) + VM D (threat=0.10)
```

### Expected Results

- A-B episode #1: start=0, end=100, duration=100 ✓
- B-C episode: start=100, end=200, duration=100 ✓
- B-D episode: start=100, end=200, duration=100 ✓
- C-D episode: start=0, end=200, duration=200 ✓
- A-B episode #2: start=200, ongoing (end=200 at snapshot) ✓
- Migration count: 2 ✓
- Total co-residency duration: 500 (100+100+100+200+0) ✓

## Validation Results

Running Stage2dMigrationValidation produces:

```
STAGE 2D VALIDATION REPORT
========================================
Scenario: VM B migrates Host 0->1 at t=100, then Host 1->0 at t=200
VMs: 4 (VM A: high threat, VM B/C/D: normal)
Hosts: 2
High-threat threshold: 0.5 (TESTING ONLY)
Snapshots: 3
  t=0: CoResidencySnapshot{time=0.0, pairs=2}
  t=100: CoResidencySnapshot{time=100.0, pairs=3}
  t=200: CoResidencySnapshot{time=200.0, pairs=2}
Migration events: 2
Co-residency episodes: 5
  Episode details:
    CoResidencyEpisode{vm1=10, vm2=11, host=0, type=HIGH_THREAT_NORMAL, start=0.0, end=100.0, duration=100.0}
    CoResidencyEpisode{vm1=11, vm2=12, host=1, type=NORMAL_NORMAL, start=100.0, end=200.0, duration=100.0}
    CoResidencyEpisode{vm1=11, vm2=13, host=1, type=NORMAL_NORMAL, start=100.0, end=200.0, duration=100.0}
    CoResidencyEpisode{vm1=12, vm2=13, host=1, type=NORMAL_NORMAL, start=0.0, end=200.0, duration=200.0}
    CoResidencyEpisode{vm1=10, vm2=11, host=0, type=HIGH_THREAT_NORMAL, start=200.0, end=200.0, duration=0.0}
  A-B episode 1: 100.0
  A-B episode 2: CoResidencyEpisode{vm1=10, vm2=11, host=0, type=HIGH_THREAT_NORMAL, start=200.0, end=200.0, duration=0.0}
  B-C episode: 100.0
  C-D episode: 200.0
  Episode types:
    NORMAL_NORMAL: 3
    HIGH_THREAT_NORMAL: 2
    HIGH_THREAT_HIGH_THREAT: 0
Total co-residency duration: 500.0
Total high-threat co-residency duration: 100.0
Total normal co-residency duration: 400.0
All checks passed: true

STAGE 2D VALIDATION PASSED
========================================
```

### Validation Checks

| Check | Description | Expected |
|-------|-------------|----------|
| [A] VM B threat score unchanged | 0.20 preserved | PASS |
| [B] VM B entity ID unchanged | entity-2-normal preserved | PASS |
| [C] After M1: Source host (0) no longer has VM B | VM B removed (checked immediately after M1) | PASS |
| [D] After M1: Destination host (1) now has VM B | VM B added (checked immediately after M1) | PASS |
| [E] After M2: VM B returns to Host 0 | VM B back | PASS |
| [F] Migration count = 2 | Exactly 2 migrations | PASS |
| [G] After M1: A-B on Host 0 absent | No cross-entity pair (checked at t=100 state) | PASS |
| [H] After M1: B-C on Host 1 present | New co-residency (checked at t=100 state) | PASS |
| [I] After M2: A-B on Host 0 present again | Returns | PASS |
| [J] Utilization after M2 correct | Host 0: 0.333, Host 1: 0.333 | PASS |
| [K] No unrelated metadata changes | A,C,D unchanged | PASS |
| [L] Co-residency episodes >= 4 | 5 episodes detected | PASS |
| [M] A-B episode 1: start=0, end=100, dur=100 | Exact match | PASS |
| [N] A-B episode 2: start=200, ongoing | Exact match | PASS |
| [O] A-B has exactly 2 separate episodes | Not merged | PASS |
| [P] B-C episode: start=100, end=200, dur=100 | Exact match | PASS |
| [Q] C-D episode: start=0, end=200, dur=200 | Genuine duration assertion | PASS |
| [R] Same-host migration rejected | Exception thrown | PASS |
| [S] Deterministic (same results both runs) | Episode count match | PASS |
| [T] Invalid-host migration rejected | Exception, placement unchanged, no event | PASS |
| [U] Migration cost formal assertion | 2 migrations × 1.0 = 2.0 | PASS |

## How to Run

From `C:\cloudsim file\cloudsim\`:

```bash
# Compile
mvn compile -pl modules/cloudsim-examples -am -DskipTests

# Run Stage 2D validation
java -Xmx256m -cp "modules/cloudsim/target/cloudsim-7.0.1.jar;modules/cloudsim-examples/target/classes" \
  org.cloudbus.cloudsim.examples.stage2d.Stage2dMigrationValidation
```

## Existing CloudSim Examples

Verified intact:
- CloudSimExample1: OK
- CloudSimExample2: OK

## Assumptions

1. **Migration is instantaneous at simulation level.** Stage 2D records
   `migrationStartTime == migrationTimestamp`. Real migration takes time but
   that's not modeled here.

2. **Migration cost is a configurable simulation parameter.** Default 1.0 per
   migration. Not a real cost measurement.

3. **Co-residency duration is measured from simulation events only.** Not from
   trace timestamps.

4. **High-threat threshold = 0.5** is TEMPORARY for Stage 2D testing.
   NOT the final adaptive threshold. NOT 0.6.

5. **CloudSim host IDs are assigned by CloudSim**, not by our code. We use
   `host.getId()` to get actual IDs.

6. **Current migration implementation is guest-list-only abstraction.**
   CloudSim provides `guestCreate`/`guestDeallocate` APIs that properly
   update resource accounting, but the current Stage 2D validation uses
   manually constructed hosts for testing the duration tracking algorithm.
   For full CloudSim simulations in Stage 2E+, the proper API path
   SHOULD be used.

## What Remains for Later Stages

### Stage 2E (NSSAO)
- Multi-objective optimization using:
  - Overall VM risk (threat + co-residency)
  - Co-residency risk
  - Co-residency duration (now available!)
  - Defensive cost (including migration cost, now available!)
  - Maximize resource utilization

### Stage 2D+ (Adaptive Threshold)
- Replace temporary 0.5 with actual adaptive threshold
- Threshold should respond to CloudSim state variables

### Future Extensions
- Model migration downtime
- Model network transfer cost
- Model energy consumption during migration
- Add vulnerability risk (when data available)
- Add network impact (when topology available)

## Methodology Adherence

- ✅ Migration model is simulation abstraction (clearly documented)
- ✅ Migration cost is configurable parameter (not paper's equation)
- ✅ Co-residency duration from simulation events (not trace timestamps)
- ✅ Threat scores preserved across migration (verified in validation)
- ✅ No 0.6 threshold introduced
- ✅ No adaptive threshold implemented
- ✅ No NSSAO implemented
- ✅ No placement optimization
- ✅ No clustering
- ✅ No new ML
- ✅ No fabricated vulnerability/network data
- ✅ Existing CloudSim examples unaffected
- ✅ CloudSim core unmodified
- ✅ Multi-snapshot duration tracking with correct episode start/end detection
- ✅ Same-pair multiple episodes correctly tracked as separate episodes
- ✅ Host-specific episode identity (pair on different host = different episode)

## CloudSim API Investigation Result

**OPTION A:** CloudSim 7.0.1 provides correct APIs for migration.

The `HostEntity` interface (implemented by `Host`) provides:
- `guestCreate(GuestEntity)` — allocates RAM, BW, MIPS via provisioners + scheduler, adds to guest list
- `guestDeallocate(GuestEntity)` — frees RAM, BW, MIPS, removes from guest list

These are the same APIs used internally by CloudSim's own migration code
(`VmAllocationPolicy.java`, `PowerVmAllocationPolicyMigrationAbstract.java`).

**Decision for Stage 2D:** The current guest-list-only abstraction is retained
for the validation/testing layer. The proper CloudSim APIs are documented
above for use in Stage 2E+ production simulations.

## STOP — Stage 2D Complete

DO NOT continue to Stage 2E/NSSAO automatically.

## Explicit Clarifications

### CloudSim State Synchronization Decision

The current Stage 2D `VmMigrationService` manipulates `host.getGuestList()` directly.
This is a VALIDATION ABSTRACTION — sufficient for co-residency duration tracking.

**What IS synchronized:**
- VM presence on host (guest list membership)
- Co-residency pair detection (correct after migration)
- Duration tracking (correct across migration events)
- Migration event recording

**What is NOT synchronized:**
- RAM provisioner allocations
- BW provisioner allocations
- VM scheduler reservations
- Full CloudSim resource accounting state

CloudSim 7.0.1 provides `guestCreate`/`guestDeallocate` APIs on `HostEntity`
that properly update all provisioner/scheduler state. These SHOULD be used for
production simulations in Stage 2E+. The current guest-list approach is
documented as an intentional abstraction for the validation layer.

### Co-residency Simulation Abstraction

Co-residency is determined by host guest-list membership in the CloudSim
simulation. The public Azure and Alibaba trace datasets do NOT contain
physical host placement information. Therefore:
- Co-residency pairs are a **simulation construct**, not trace-derived
- Co-residency duration is measured from simulation time, not trace time
- Cross-entity co-residency risk depends on the simulation scenario

### Threshold Clarification

- **0.5** is the TEMPORARY testing threshold for Stage 2D only
- It is NOT the final project threshold
- **0.6** from the paper is NOT adopted as the project threshold
- The final threshold will be determined by adaptive thresholding (Stage 2E+)
- NSSAO adaptive thresholding is NOT implemented in Stage 2D

### What is NOT in Stage 2D

- ❌ Adaptive thresholding (Stage 2E+)
- ❌ NSSAO multi-objective optimization (Stage 2E+)
- ❌ Real cloud migration cost modeling
- ❌ Migration downtime modeling
- ❌ Network transfer modeling
- ❌ Vulnerability risk (no data available)
- ❌ Network topology impact (no data available)
