from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

BASELINE = 0.6
FIXED_THRESHOLDS = [0.5, 0.6, 0.7, 0.8, 0.9]
QUANTILES = [0.80, 0.85, 0.90, 0.95]
BOUNDED_MIN = 0.5
BOUNDED_MAX = 0.9


def threshold_result(dataset: str, strategy: str, threshold: float, scores: pd.Series, window: str = "all") -> dict[str, object]:
    flagged = scores >= threshold
    return {
        "dataset": dataset,
        "window": window,
        "strategy": strategy,
        "threshold": float(threshold),
        "entity_count": int(scores.size),
        "flagged_count": int(flagged.sum()),
        "flagged_percentage": float(flagged.mean() * 100),
        "score_min": float(scores.min()),
        "score_max": float(scores.max()),
        "score_mean": float(scores.mean()),
        "score_median": float(scores.median()),
        "score_p90": float(scores.quantile(0.90)),
        "score_p95": float(scores.quantile(0.95)),
    }


def evaluate_dataset(dataset: str, frame: pd.DataFrame) -> list[dict[str, object]]:
    scores = frame["threat_score"]
    rows = []
    for threshold in FIXED_THRESHOLDS:
        rows.append(threshold_result(dataset, "fixed", threshold, scores))
    for quantile in QUANTILES:
        threshold = float(scores.quantile(quantile))
        rows.append(threshold_result(dataset, f"quantile_p{int(quantile * 100)}", threshold, scores))
    p90 = float(scores.quantile(0.90))
    adjustment = 0.5 * (p90 - BASELINE)
    bounded = max(BOUNDED_MIN, min(BOUNDED_MAX, BASELINE + adjustment))
    rows.append(threshold_result(dataset, "bounded_p90_pressure", bounded, scores))
    return rows


def evaluate_windows(dataset: str, frame: pd.DataFrame) -> tuple[list[dict[str, object]], str | None]:
    if frame["observation_start_seconds"].nunique() < 2:
        return [], "No meaningful temporal windows: all observation_start_seconds are identical."
    ordered = frame.sort_values("observation_start_seconds").copy()
    ordered["window"] = pd.qcut(ordered["observation_start_seconds"], q=4, duplicates="drop")
    rows = []
    for window, group in ordered.groupby("window", observed=True):
        scores = group["threat_score"]
        threshold = float(scores.quantile(0.90))
        rows.append(threshold_result(dataset, "quantile_p90_window", threshold, scores, str(window)))
    return rows, None


def plot_strategy(rows: pd.DataFrame, path: Path) -> None:
    plt.figure(figsize=(9, 5))
    for dataset in sorted(rows["dataset"].unique()):
        subset = rows[(rows["dataset"] == dataset) & (rows["strategy"].isin(["fixed", "quantile_p90", "bounded_p90_pressure"]))]
        for strategy in subset["strategy"].unique():
            values = subset[subset["strategy"] == strategy]
            plt.plot(values["threshold"], values["flagged_percentage"], marker="o", label=f"{dataset} {strategy}")
    plt.xlabel("Threshold")
    plt.ylabel("High-anomaly / threat-flagged entities (%)")
    plt.title("Threshold sensitivity on within-dataset scores")
    plt.grid(alpha=0.3)
    plt.legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(path, dpi=140)
    plt.close()


def plot_windows(rows: pd.DataFrame, path: Path) -> None:
    if not rows:
        return
    frame = pd.DataFrame(rows)
    plt.figure(figsize=(8, 4))
    plt.plot(range(1, len(frame) + 1), frame["threshold"], marker="o")
    plt.xlabel("Sequential Alibaba window")
    plt.ylabel("P90 adaptive threshold")
    plt.title("Alibaba windowed P90 threshold")
    plt.grid(alpha=0.3)
    plt.tight_layout()
    plt.savefig(path, dpi=140)
    plt.close()


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    outputs = root / "outputs"
    plots = outputs / "plots"
    azure = pd.read_csv(outputs / "azure_to_azure_scores.csv")
    alibaba = pd.read_csv(outputs / "alibaba_to_alibaba_scores.csv")
    rows = evaluate_dataset("azure", azure) + evaluate_dataset("alibaba", alibaba)
    window_rows, window_limitation = evaluate_windows("azure", azure)
    alibaba_window_rows, alibaba_window_limitation = evaluate_windows("alibaba", alibaba)
    window_rows += alibaba_window_rows
    results = pd.DataFrame(rows)
    results.to_csv(outputs / "stage_1e_threshold_comparison.csv", index=False)
    window_frame = pd.DataFrame(window_rows)
    if not window_frame.empty:
        window_frame.to_csv(outputs / "stage_1e_window_analysis.csv", index=False)
    plot_strategy(results, plots / "stage_1e_threshold_sensitivity.png")
    plot_windows(alibaba_window_rows, plots / "stage_1e_alibaba_window_thresholds.png")
    report = {
        "fixed_thresholds_tested": FIXED_THRESHOLDS,
        "quantiles_tested": QUANTILES,
        "bounded_strategy": {
            "formula": "clamp(0.6 + 0.5 * (P90 - 0.6), 0.5, 0.9)",
            "baseline": BASELINE,
            "minimum": BOUNDED_MIN,
            "maximum": BOUNDED_MAX,
            "observable_signal": "current P90 threat-score pressure",
        },
        "results": rows,
        "window_results": window_rows,
        "window_limitations": {"azure": window_limitation, "alibaba": alibaba_window_limitation},
        "interpretation": {
            "transfer_scores_used_for_threat_prevalence": False,
            "score_shift_warning": "Transfer score shifts are source-fitted workload-behaviour distributions and must not be interpreted as threat prevalence.",
            "fixed_threshold_conclusion": "0.6 is a paper-reference baseline only; fixed absolute thresholds show different flag pressure across datasets.",
            "quantile_conclusion": "Quantile thresholds maintain a predictable alert fraction within each current distribution and are more robust to absolute transfer-score shifts, but they do not measure malicious prevalence.",
            "bounded_conclusion": "The bounded P90-pressure strategy is an interpretable offline candidate, not a final controller formula.",
            "cloudsim_required_later": ["current VM/server risk", "co-residency risk", "resource utilization", "migration count", "migration cost", "computational overhead", "security objective response"],
        },
        "recommendation": "Use a bounded quantile-based threshold as the next CloudSim integration candidate: compute a current-window quantile, clamp it to configured bounds, and later adjust it using measured risk and migration pressure. Do not freeze the final formula yet.",
    }
    (outputs / "stage_1e_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
