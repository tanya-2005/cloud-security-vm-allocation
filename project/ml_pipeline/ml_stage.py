from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import joblib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.impute import SimpleImputer
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import RobustScaler

FEATURE_COLUMNS = [
    "member_count", "start_count", "mean_start_interval_seconds",
    "mean_member_lifetime_seconds", "mean_cpu_activity_percent",
    "cpu_variability_percent_points", "mean_memory_demand",
    "cpu_resource_footprint_core_seconds",
]
METADATA_COLUMNS = [
    "dataset", "entity_type", "entity_id", "observation_start_seconds",
    "observation_end_seconds",
]
SEED = 20260909


@dataclass(frozen=True)
class ModelConfig:
    n_estimators: int = 200
    max_samples: int | str = 256
    contamination: str = "auto"
    random_state: int = SEED


def load_features(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path)
    missing = [column for column in [*METADATA_COLUMNS, *FEATURE_COLUMNS] if column not in frame.columns]
    if missing:
        raise ValueError(f"Missing required columns in {path}: {missing}")
    for column in FEATURE_COLUMNS:
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    return frame


def build_pipeline(config: ModelConfig) -> Pipeline:
    max_samples: int | float | str = config.max_samples
    return Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", RobustScaler()),
        ("model", IsolationForest(
            n_estimators=config.n_estimators,
            max_samples=max_samples,
            contamination=config.contamination,
            random_state=config.random_state,
        )),
    ])


def fit_score(source: pd.DataFrame, target: pd.DataFrame, config: ModelConfig) -> tuple[pd.DataFrame, Pipeline, dict[str, float]]:
    effective_max_samples = min(config.max_samples, len(source)) if isinstance(config.max_samples, int) else config.max_samples
    effective_config = ModelConfig(
        n_estimators=config.n_estimators,
        max_samples=effective_max_samples,
        contamination=config.contamination,
        random_state=config.random_state,
    )
    model = build_pipeline(effective_config)
    model.fit(source[FEATURE_COLUMNS])
    source_raw = model.named_steps["model"].score_samples(model.named_steps["scaler"].transform(model.named_steps["imputer"].transform(source[FEATURE_COLUMNS])))
    raw_min, raw_max = float(np.min(source_raw)), float(np.max(source_raw))
    denominator = raw_max - raw_min
    if denominator <= 0:
        raise ValueError("Isolation Forest source scores have no range; threat normalization is undefined")
    raw = model.named_steps["model"].score_samples(model.named_steps["scaler"].transform(model.named_steps["imputer"].transform(target[FEATURE_COLUMNS])))
    threat = np.clip((raw_max - raw) / denominator, 0.0, 1.0)
    result = target.copy()
    result["isolation_forest_score"] = raw
    result["anomaly_score"] = -raw
    result["threat_score"] = threat
    calibration = {
        "raw_score_min": raw_min,
        "raw_score_max": raw_max,
        "effective_max_samples": effective_max_samples,
    }
    return result, model, calibration


def summary(frame: pd.DataFrame) -> dict[str, object]:
    scores = frame["threat_score"]
    return {
        "entities": int(len(frame)),
        "anomaly_rate_at_0_5": float((scores >= 0.5).mean()),
        "threat_score": {
            "min": float(scores.min()), "max": float(scores.max()),
            "mean": float(scores.mean()), "median": float(scores.median()),
            "std": float(scores.std(ddof=0)),
            "quantiles": {str(q): float(scores.quantile(q)) for q in [0.5, 0.75, 0.9, 0.95, 0.99]},
        },
        "top_entities": frame.nlargest(min(10, len(frame)), "threat_score")[
            ["dataset", "entity_type", "entity_id", "threat_score", "isolation_forest_score"
        ]].to_dict(orient="records"),
    }


def save_plot(frame: pd.DataFrame, path: Path, title: str) -> None:
    plt.figure(figsize=(7, 4))
    plt.hist(frame["threat_score"], bins=20, range=(0, 1), edgecolor="black")
    plt.xlabel("Threat score")
    plt.ylabel("Entity count")
    plt.title(title)
    plt.tight_layout()
    plt.savefig(path, dpi=140)
    plt.close()


def run_direction(source_name: str, target_name: str, source: pd.DataFrame, target: pd.DataFrame, root: Path, config: ModelConfig) -> dict[str, object]:
    artifacts = root / "models"
    outputs = root / "outputs"
    plots = outputs / "plots"
    artifacts.mkdir(parents=True, exist_ok=True)
    outputs.mkdir(parents=True, exist_ok=True)
    plots.mkdir(parents=True, exist_ok=True)
    source_result, model, calibration = fit_score(source, target, config)
    direction = f"{source_name}_to_{target_name}"
    source_result.to_csv(outputs / f"{direction}_scores.csv", index=False)
    if source_name == target_name:
        source_result.to_csv(outputs / f"{source_name}_within_scores.csv", index=False)
    joblib.dump(model, artifacts / f"{source_name}_model.joblib")
    (artifacts / f"{source_name}_calibration.json").write_text(json.dumps(calibration, indent=2), encoding="utf-8")
    report = {"source": source_name, "target": target_name, "calibration": calibration, "summary": summary(source_result)}
    (outputs / f"{direction}_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    save_plot(source_result, plots / f"{direction}_threat_scores.png", f"{source_name} model on {target_name}")
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--n-estimators", type=int, default=200)
    parser.add_argument("--max-samples", type=int, default=256)
    args = parser.parse_args()
    root = args.project_root
    processed = root / "data" / "processed"
    azure = load_features(processed / "azure_features.csv")
    alibaba = load_features(processed / "alibaba_features.csv")
    config = ModelConfig(n_estimators=args.n_estimators, max_samples=args.max_samples)
    (root / "models").mkdir(parents=True, exist_ok=True)
    (root / "models" / "pipeline_config.json").write_text(
        json.dumps({"config": asdict(config), "features": FEATURE_COLUMNS, "metadata_excluded": METADATA_COLUMNS}, indent=2),
        encoding="utf-8",
    )
    reports = {
        "config": asdict(config),
        "within_dataset": {
            "azure": run_direction("azure", "azure", azure, azure, root, config),
            "alibaba": run_direction("alibaba", "alibaba", alibaba, alibaba, root, config),
        },
        "transfer": {
            "azure_to_alibaba": run_direction("azure", "alibaba", azure, alibaba, root, config),
            "alibaba_to_azure": run_direction("alibaba", "azure", alibaba, azure, root, config),
        },
        "normalization": "threat_score = clip((source_raw_max - isolation_forest_score) / (source_raw_max - source_raw_min), 0, 1); lower score_samples means more anomalous.",
        "preprocessing": "SimpleImputer(strategy=median) then RobustScaler, both fit on source only.",
    }
    (root / "outputs" / "stage_1c_report.json").write_text(json.dumps(reports, indent=2), encoding="utf-8")
    print(json.dumps(reports, indent=2))


if __name__ == "__main__":
    main()
