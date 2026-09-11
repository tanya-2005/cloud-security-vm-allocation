from __future__ import annotations

import csv
import gzip
import json
from collections import Counter, defaultdict
from pathlib import Path

VMTABLE_COLUMNS = [
    "vmid", "subscriptionid", "deploymentid", "vmcreated", "vmdeleted",
    "maxcpu", "avgcpu", "p95maxcpu", "vmcategory", "vmcorecountbucket",
    "vmmemorybucket",
]
CPU_COLUMNS = ["timestamp", "vmid", "mincpu", "maxcpu", "avgcpu"]


def read_gzip(path: Path, columns: list[str]) -> list[dict[str, str]]:
    rows = []
    try:
        with gzip.open(path, "rt", newline="", errors="replace") as stream:
            for values in csv.reader(stream):
                if len(values) == len(columns):
                    rows.append(dict(zip(columns, values)))
    except EOFError:
        pass
    return rows


def write_csv(path: Path, rows: list[dict[str, str]], columns: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    project = Path(__file__).resolve().parents[1]
    raw = project / "data" / "raw" / "azure_v2"
    output = raw / "aligned_sample"
    output.mkdir(parents=True, exist_ok=True)
    vmtable = read_gzip(raw / "trace_data_vmtable_vmtable.prefix8MiB.csv.gz", VMTABLE_COLUMNS)
    cpu = read_gzip(raw / "trace_data_vm_cpu_readings_file-1-of-195.prefix8MiB.csv.gz", CPU_COLUMNS)
    cpu_by_vm: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in cpu:
        try:
            value = float(row["avgcpu"])
            timestamp = int(row["timestamp"])
        except ValueError:
            continue
        if value >= 0 and timestamp >= 0:
            cpu_by_vm[row["vmid"]].append(row)
    vm_by_id = {row["vmid"]: row for row in vmtable}
    overlap = set(vm_by_id) & set(cpu_by_vm)
    candidates = sorted(overlap)
    deployment_candidates: dict[str, list[str]] = defaultdict(list)
    for vmid in candidates:
        deployment_candidates[vm_by_id[vmid]["deploymentid"]].append(vmid)
    selected = set()
    for deployment_id in sorted(deployment_candidates):
        vmids = deployment_candidates[deployment_id]
        selected.update(vmids)
        if len(selected) >= 500:
            break
    selected_vms = [vm_by_id[vmid] for vmid in sorted(selected)]
    selected_cpu = [row for vmid in sorted(selected) for row in cpu_by_vm[vmid]]
    deployment_ids = {row["deploymentid"] for row in selected_vms}
    deployments = []
    with gzip.open(raw / "trace_data_deployments_deployments.csv.gz", "rt", newline="", errors="replace") as stream:
        for values in csv.reader(stream):
            if len(values) == 2 and values[0] in deployment_ids:
                deployments.append({"deploymentid": values[0], "deploymentsize": values[1]})
    write_csv(output / "vmtable_aligned.csv", selected_vms, VMTABLE_COLUMNS)
    write_csv(output / "cpu_readings_aligned.csv", selected_cpu, CPU_COLUMNS)
    write_csv(output / "deployments_aligned.csv", deployments, ["deploymentid", "deploymentsize"])
    cpu_counts = Counter(row["deploymentid"] for row in selected_vms for _ in cpu_by_vm[row["vmid"]])
    computable = sum(count >= 2 for count in cpu_counts.values())
    diagnostics = {
        "source_vmtable_rows": len(vmtable),
        "source_vmtable_unique_vmids": len(vm_by_id),
        "source_cpu_rows": len(cpu),
        "source_cpu_unique_vmids": len(cpu_by_vm),
        "overlap_vmids": len(overlap),
        "candidate_overlapping_vmids": len(candidates),
        "selected_vmtable_rows": len(selected_vms),
        "selected_unique_vmids": len(selected),
        "selected_deployment_ids": len(deployment_ids),
        "selected_cpu_rows": len(selected_cpu),
        "selected_cpu_unique_vmids": len({row["vmid"] for row in selected_cpu}),
        "overlap_percentage_of_selected_vms": 100.0 if selected else 0.0,
        "deployments_with_at_least_two_cpu_readings": computable,
        "feature_checkpoint_pass": bool(selected and computable),
        "original_files_unchanged": True,
    }
    (output / "alignment_diagnostics.json").write_text(json.dumps(diagnostics, indent=2), encoding="utf-8")
    print(json.dumps(diagnostics, indent=2))


if __name__ == "__main__":
    main()
