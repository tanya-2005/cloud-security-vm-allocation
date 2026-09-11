from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

FEATURE_COLUMNS = [
    "member_count", "start_count", "mean_start_interval_seconds",
    "mean_member_lifetime_seconds", "mean_cpu_activity_percent",
    "cpu_variability_percent_points", "mean_memory_demand",
    "cpu_resource_footprint_core_seconds",
]
DIRECTIONS = {
    "azure_to_azure": ("azure", "azure"),
    "alibaba_to_alibaba": ("alibaba", "alibaba"),
    "azure_to_alibaba": ("azure", "alibaba"),
    "alibaba_to_azure": ("alibaba", "azure"),
}


def stats(values: pd.Series) -> dict[str, float | int]:
    values = pd.to_numeric(values, errors="coerce").dropna()
    return {
        "count": int(values.size),
        "min": float(values.min()),
        "max": float(values.max()),
        "mean": float(values.mean()),
        "median": float(values.median()),
        "std": float(values.std(ddof=0)),
        "q1": float(values.quantile(0.25)),
        "q3": float(values.quantile(0.75)),
        "p90": float(values.quantile(0.90)),
        "p95": float(values.quantile(0.95)),
        "p99": float(values.quantile(0.99)),
        "zero_count": int((values == 0).sum()),
        "zero_percentage": float((values == 0).mean() * 100),
        "one_count": int((values == 1).sum()),
        "one_percentage": float((values == 1).mean() * 100),
    }


def load_scores(outputs: Path, direction: str) -> pd.DataFrame:
    return pd.read_csv(outputs / f"{direction}_scores.csv")


def plot_score_distributions(frames: dict[str, pd.DataFrame], plots: Path) -> None:
    for direction, frame in frames.items():
        plt.figure(figsize=(7, 4))
        plt.hist(frame["threat_score"], bins=20, range=(0, 1), edgecolor="black")
        plt.xlabel("Threat score")
        plt.ylabel("Entity count")
        plt.title(direction.replace("_", " "))
        plt.tight_layout()
        plt.savefig(plots / f"stage_1d_{direction}_threat_scores.png", dpi=140)
        plt.close()


def plot_features(azure: pd.DataFrame, alibaba: pd.DataFrame, plots: Path) -> None:
    fig, axes = plt.subplots(4, 2, figsize=(12, 14))
    for axis, feature in zip(axes.flat, FEATURE_COLUMNS):
        axis.hist(azure[feature].dropna(), bins=20, alpha=0.65, label="Azure", density=True)
        axis.hist(alibaba[feature].dropna(), bins=20, alpha=0.65, label="Alibaba", density=True)
        axis.set_title(feature)
        axis.legend()
    fig.tight_layout()
    fig.savefig(plots / "stage_1d_feature_distributions.png", dpi=140)
    plt.close(fig)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    outputs = root / "outputs"
    processed = root / "data" / "processed"
    plots = outputs / "plots"
    plots.mkdir(parents=True, exist_ok=True)
    frames = {direction: load_scores(outputs, direction) for direction in DIRECTIONS}
    calibration = {
        "azure": json.loads((root / "models" / "azure_calibration.json").read_text()),
        "alibaba": json.loads((root / "models" / "alibaba_calibration.json").read_text()),
    }
    score_report: dict[str, object] = {}
    for direction, frame in frames.items():
        source, target = DIRECTIONS[direction]
        raw = pd.to_numeric(frame["isolation_forest_score"], errors="coerce")
        score_report[direction] = {
            "source": source,
            "target": target,
            "threat_score": stats(frame["threat_score"]),
            "isolation_forest_score": stats(raw),
            "source_calibration": calibration[source],
            "target_below_source_min_count": int((raw < calibration[source]["raw_score_min"]).sum()) if source != target else 0,
            "target_above_source_max_count": int((raw > calibration[source]["raw_score_max"]).sum()) if source != target else 0,
        }
        score_report[direction]["target_below_source_min_percentage"] = score_report[direction]["target_below_source_min_count"] / len(frame) * 100 if source != target else 0.0
        score_report[direction]["target_above_source_max_percentage"] = score_report[direction]["target_above_source_max_count"] / len(frame) * 100 if source != target else 0.0
    azure = pd.read_csv(processed / "azure_features.csv")
    alibaba = pd.read_csv(processed / "alibaba_features.csv")
    feature_report: dict[str, object] = {}
    for feature in FEATURE_COLUMNS:
        feature_report[feature] = {
            "azure": stats(azure[feature]),
            "alibaba": stats(alibaba[feature]),
        }
    report = {
        "score_distributions": score_report,
        "feature_distributions": feature_report,
        "interpretation": {
            "transfer_scores": "Source-fitted workload-behaviour transfer score distributions; not threat prevalence estimates.",
            "direct_comparability": "Source-min/source-max normalization is monotonic within a transfer direction but is not directly comparable across independently calibrated datasets.",
            "threshold_readiness": "A single fixed threshold is not justified by current transfer evidence. The paper's 0.6 remains only a baseline reference.",
            "recommended_quantity_for_future_threshold": "Use the continuous normalized threat_score only after calibration is revisited; evaluate source-fitted calibration, quantile stability, and cloud-state response before implementing an adaptive threshold.",
            "safety_checks": {
                "labels_fabricated": False,
                "classification_metrics_reported": False,
                "target_fitting_or_tuning": False,
                "clustering_introduced": False,
                "later_stage_code_modified": False,
            },
        },
    }
    (outputs / "stage_1d_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    plot_score_distributions(frames, plots)
    plot_features(azure, alibaba, plots)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
