# Python ML Pipeline

This pipeline uses only the frozen primary entities:

- Azure Public Dataset V2 grouped by `deploymentid`.
- Alibaba Cluster Trace V2018 batch instances grouped by `job_name`.

Alibaba `app_du` and `container_usage` are excluded from the primary ML path because the public V2018 usage artifact has blank `container_id` values.

## Extraction

From the project directory:

```text
python ml_pipeline/pipeline.py --row-limit 1000
```

The extraction stage reads the existing bounded development samples and writes:

- `data/processed/azure_features.csv`
- `data/processed/alibaba_features.csv`
- `data/processed/azure_diagnostics.json`
- `data/processed/alibaba_diagnostics.json`

The eight numeric features are exactly:

```text
member_count
start_count
mean_start_interval_seconds
mean_member_lifetime_seconds
mean_cpu_activity_percent
cpu_variability_percent_points
mean_memory_demand
cpu_resource_footprint_core_seconds
```

Azure uses the official bucket representatives: `>24` cores maps to `30`, and `>64` GB maps to `70`. Alibaba batch CPU values use `100 units = 1 core`; Alibaba normalized memory sentinels `-1` and `101` are rejected for memory fields.

No dynamic threshold, CloudSim, Java, NSSAO, placement, or risk model is implemented here.

## ML stage

Use the project environment and run:

```text
python ml_pipeline/pipeline.py --row-limit 10000
python ml_pipeline/ml_stage.py
```

The ML stage fits median imputation, `RobustScaler`, and Isolation Forest on the source dataset only. The default configuration is 200 trees, `max_samples=256` capped at the source row count, `contamination=auto`, and random seed `20260909`.

The raw Isolation Forest `score_samples` value is retained as `isolation_forest_score`. Since lower values are more anomalous, the continuous score is normalized using source calibration:

```text
threat_score = clip((source_raw_max - isolation_forest_score) /
					 (source_raw_max - source_raw_min), 0, 1)
```

Transfer experiments reuse the source imputer, scaler, model, and score calibration without fitting on the target dataset. This stage does not implement thresholds, CloudSim, Java, NSSAO, placement, risk, or migration.
