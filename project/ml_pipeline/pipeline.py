from __future__ import annotations

import argparse
import csv
import gzip
import json
import math
import tarfile
from collections import defaultdict
from pathlib import Path
from statistics import mean, median, pstdev
from typing import Iterable, Iterator

AZURE_VMTABLE_COLUMNS = [
    "vmid", "subscriptionid", "deploymentid", "vmcreated", "vmdeleted",
    "maxcpu", "avgcpu", "p95maxcpu", "vmcategory", "vmcorecountbucket",
    "vmmemorybucket",
]
AZURE_CPU_COLUMNS = ["timestamp", "vmid", "mincpu", "maxcpu", "avgcpu"]
ALIBABA_BATCH_INSTANCE_COLUMNS = [
    "instance_name", "task_name", "job_name", "task_type", "status",
    "start_time", "end_time", "machine_id", "seq_no", "total_seq_no",
    "cpu_avg", "cpu_max", "mem_avg", "mem_max",
]
FEATURE_COLUMNS = [
    "member_count", "start_count", "mean_start_interval_seconds",
    "mean_member_lifetime_seconds", "mean_cpu_activity_percent",
    "cpu_variability_percent_points", "mean_memory_demand",
    "cpu_resource_footprint_core_seconds",
]
OUTPUT_COLUMNS = [
    "dataset", "entity_type", "entity_id", "observation_start_seconds",
    "observation_end_seconds", *FEATURE_COLUMNS,
]

AZURE_CORE_REPRESENTATIVES = {"2": 2.0, "4": 4.0, "8": 8.0, "12": 12.0, "24": 24.0, ">24": 30.0, "30": 30.0}
AZURE_MEMORY_REPRESENTATIVES = {"2": 2.0, "4": 4.0, "8": 8.0, "32": 32.0, "64": 64.0, ">64": 70.0, "70": 70.0}


def _finite(value: float | None) -> bool:
    return value is not None and math.isfinite(value)


def _float(value: str) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _int(value: str) -> int | None:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _mean(values: list[float]) -> float | None:
    return mean(values) if values else None


def _std(values: list[float]) -> float | None:
    return pstdev(values) if len(values) >= 2 else None


def _interval_mean(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    ordered = sorted(values)
    return mean([b - a for a, b in zip(ordered, ordered[1:])])


def _stats(rows: list[dict[str, object]], rejected: dict[str, int]) -> dict[str, object]:
    result: dict[str, object] = {"row_count": len(rows), "entity_count": len(rows), "rejected": rejected}
    feature_stats: dict[str, dict[str, float | None]] = {}
    for column in FEATURE_COLUMNS:
        values = [float(row[column]) for row in rows if _finite(row.get(column))]  # type: ignore[arg-type]
        feature_stats[column] = {
            "min": min(values) if values else None,
            "max": max(values) if values else None,
            "mean": mean(values) if values else None,
            "median": median(values) if values else None,
            "std": pstdev(values) if len(values) > 1 else None,
            "missing": len(rows) - len(values),
            "finite": len(values),
        }
    result["features"] = feature_stats
    result["complete_feature_entities"] = sum(
        all(_finite(row.get(column)) for column in FEATURE_COLUMNS) for row in rows
    )
    return result


def read_gzip_rows(path: Path, columns: list[str], limit: int | None = None) -> Iterator[dict[str, str]]:
    try:
        with gzip.open(path, "rt", newline="", errors="replace") as stream:
            reader = csv.reader(stream)
            count = 0
            for values in reader:
                if len(values) != len(columns):
                    continue
                yield dict(zip(columns, values))
                count += 1
                if limit is not None and count >= limit:
                    break
    except EOFError:
        return


def read_csv_rows(path: Path, columns: list[str], limit: int | None = None) -> Iterator[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8-sig", errors="replace") as stream:
        reader = csv.reader(stream)
        count = 0
        for values in reader:
            if len(values) != len(columns):
                continue
            yield dict(zip(columns, values))
            count += 1
            if limit is not None and count >= limit:
                break


def read_rows(path: Path, columns: list[str], limit: int | None = None) -> Iterator[dict[str, str]]:
    if path.suffix == ".gz":
        yield from read_gzip_rows(path, columns, limit)
    else:
        yield from read_csv_rows(path, columns, limit)


def read_tar_member_rows(path: Path, member_name: str, columns: list[str], limit: int | None = None) -> Iterator[dict[str, str]]:
    with tarfile.open(path, mode="r:gz") as archive:
        member = archive.next()
        if member is None or member.name != member_name:
            return
        stream = archive.extractfile(member)
        if stream is None:
            return
        reader = csv.reader((line.decode("utf-8", errors="replace") for line in stream))
        count = 0
        for values in reader:
            if len(values) != len(columns):
                continue
            yield dict(zip(columns, values))
            count += 1
            if limit is not None and count >= limit:
                break


def extract_azure(vmtable_path: Path, cpu_path: Path, output_path: Path, diagnostics_path: Path, row_limit: int | None = None) -> dict[str, object]:
    vm_rows = list(read_rows(vmtable_path, AZURE_VMTABLE_COLUMNS, row_limit))
    cpu_by_vm: dict[str, list[float]] = defaultdict(list)
    for row in read_rows(cpu_path, AZURE_CPU_COLUMNS, None):
        value = _float(row["avgcpu"])
        if value is not None and 0 <= value <= 100 and row["vmid"]:
            cpu_by_vm[row["vmid"]].append(value)
    groups: dict[str, list[dict[str, object]]] = defaultdict(list)
    rejected = defaultdict(int)
    for row in vm_rows:
        entity = row["deploymentid"]
        if not entity or not row["vmid"]:
            rejected["missing_entity_or_member_id"] += 1
            continue
        created, deleted = _int(row["vmcreated"]), _int(row["vmdeleted"])
        avg_cpu = _float(row["avgcpu"])
        core = AZURE_CORE_REPRESENTATIVES.get(row["vmcorecountbucket"])
        memory = AZURE_MEMORY_REPRESENTATIVES.get(row["vmmemorybucket"])
        if created is None or deleted is None or deleted < created:
            rejected["invalid_lifetime"] += 1
            continue
        if avg_cpu is None or not 0 <= avg_cpu <= 100:
            rejected["invalid_cpu"] += 1
            continue
        if core is None or memory is None:
            rejected["unknown_bucket"] += 1
            continue
        groups[entity].append({
            "vmid": row["vmid"], "created": float(created), "deleted": float(deleted),
            "avg_cpu": avg_cpu, "core": core, "memory": memory,
        })
    output = []
    for entity, members in groups.items():
        starts = [float(member["created"]) for member in members]
        lifetimes = [float(member["deleted"]) - float(member["created"]) for member in members]
        cpu = [float(member["avg_cpu"]) for member in members]
        memory = [float(member["memory"]) for member in members]
        footprint = sum(float(member["core"]) * float(member["avg_cpu"]) / 100 * lifetime for member, lifetime in zip(members, lifetimes))
        reading_values = [value for member in members for value in cpu_by_vm.get(str(member["vmid"]), [])]
        output.append({
            "dataset": "azure_v2", "entity_type": "deployment", "entity_id": entity,
            "observation_start_seconds": min(starts), "observation_end_seconds": max(float(member["deleted"]) for member in members),
            "member_count": len(members), "start_count": len(starts),
            "mean_start_interval_seconds": _interval_mean(starts),
            "mean_member_lifetime_seconds": _mean(lifetimes),
            "mean_cpu_activity_percent": _mean(cpu),
            "cpu_variability_percent_points": _std(reading_values),
            "mean_memory_demand": _mean(memory),
            "cpu_resource_footprint_core_seconds": footprint,
        })
    write_csv(output_path, output, OUTPUT_COLUMNS)
    diagnostics = _stats(output, dict(rejected))
    diagnostics["cpu_reading_vm_count"] = len(cpu_by_vm)
    diagnostics["source_rows"] = len(vm_rows)
    diagnostics_path.write_text(json.dumps(diagnostics, indent=2), encoding="utf-8")
    return diagnostics


def extract_alibaba(batch_instance_path: Path, output_path: Path, diagnostics_path: Path, row_limit: int | None = None, tar_prefix: bool = False) -> dict[str, object]:
    if tar_prefix:
        rows = list(read_tar_member_rows(batch_instance_path, "batch_instance.csv", ALIBABA_BATCH_INSTANCE_COLUMNS, row_limit))
    else:
        rows = list(read_csv_rows(batch_instance_path, ALIBABA_BATCH_INSTANCE_COLUMNS, row_limit))
    groups: dict[str, list[dict[str, float | str]]] = defaultdict(list)
    rejected = defaultdict(int)
    for row in rows:
        entity = row["job_name"]
        if not entity or not row["instance_name"]:
            rejected["missing_entity_or_member_id"] += 1
            continue
        start, end = _int(row["start_time"]), _int(row["end_time"])
        cpu_avg, cpu_max = _float(row["cpu_avg"]), _float(row["cpu_max"])
        memory_avg, memory_max = _float(row["mem_avg"]), _float(row["mem_max"])
        if start is None or end is None or end < start:
            rejected["invalid_lifetime"] += 1
            continue
        if cpu_avg is None or cpu_max is None or cpu_avg < 0 or cpu_max < 0:
            rejected["invalid_cpu"] += 1
            continue
        if memory_avg in (-1, 101) or memory_max in (-1, 101) or memory_avg is None or memory_max is None:
            rejected["invalid_memory"] += 1
            continue
        groups[entity].append({"instance": row["instance_name"], "start": float(start), "end": float(end), "cpu_avg": cpu_avg, "cpu_max": cpu_max, "memory": memory_avg})
    output = []
    for entity, members in groups.items():
        starts = [float(member["start"]) for member in members]
        lifetimes = [float(member["end"]) - float(member["start"]) for member in members]
        cpu = [float(member["cpu_avg"]) for member in members]
        memory = [float(member["memory"]) for member in members]
        output.append({
            "dataset": "alibaba_v2018", "entity_type": "batch_job", "entity_id": entity,
            "observation_start_seconds": min(starts), "observation_end_seconds": max(float(member["end"]) for member in members),
            "member_count": len(members), "start_count": len(starts),
            "mean_start_interval_seconds": _interval_mean(starts),
            "mean_member_lifetime_seconds": _mean(lifetimes),
            "mean_cpu_activity_percent": _mean(cpu),
            "cpu_variability_percent_points": _std(cpu),
            "mean_memory_demand": _mean(memory),
            "cpu_resource_footprint_core_seconds": sum(value / 100 * lifetime for value, lifetime in zip(cpu, lifetimes)),
        })
    write_csv(output_path, output, OUTPUT_COLUMNS)
    diagnostics = _stats(output, dict(rejected))
    diagnostics["source_rows"] = len(rows)
    diagnostics_path.write_text(json.dumps(diagnostics, indent=2), encoding="utf-8")
    return diagnostics


def write_csv(path: Path, rows: list[dict[str, object]], columns: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--row-limit", type=int, default=1000)
    args = parser.parse_args()
    raw = args.project_root / "data" / "raw"
    processed = args.project_root / "data" / "processed"
    azure = raw / "azure_v2" / "aligned_sample"
    alibaba = raw / "alibaba_v2018"
    azure_result = extract_azure(
        azure / "vmtable_aligned.csv",
        azure / "cpu_readings_aligned.csv",
        processed / "azure_features.csv",
        processed / "azure_diagnostics.json",
        args.row_limit,
    )
    alibaba_result = extract_alibaba(
        raw / "alibaba_v2018" / "batch_instance.tar.gz.prefix8MiB",
        processed / "alibaba_features.csv",
        processed / "alibaba_diagnostics.json",
        args.row_limit,
        True,
    )
    print(json.dumps({"azure": azure_result, "alibaba": alibaba_result}, indent=2))


if __name__ == "__main__":
    main()
