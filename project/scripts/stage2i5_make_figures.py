#!/usr/bin/env python3
"""Stage 2I.5 - results visualization ONLY.

Reads ONLY the stored Stage 2I.3/2I.4 results (never re-runs experiments,
never modifies stored results) and produces publication-quality PNG/SVG
figures plus the per-figure source CSV tables into
project/results/stage2i5/.

Aggregation: median across the 5 seeds per (cell, method); seed
variability shown as min/max range where useful. No significance tests,
no weighted overall score, no thesis conclusions.

Every figure is regenerated deterministically from the stored CSVs; no
manually entered experimental values appear anywhere in this script.
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless; reproducible
# Newer matplotlib randomizes SVG element ids per run; fix the salt so
# every figure is byte-reproducible from the stored CSVs.
matplotlib.rcParams["svg.hashsalt"] = "stage2i5-reproducible"
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
IN_ANALYSIS = ROOT / "project/results/stage2i4/analysis_summary.csv"
IN_CELL = ROOT / "project/results/stage2i4/cell_method_summary.csv"
OUT = ROOT / "project/results/stage2i5"

METHODS = [
    "proposed_nssao",
    "b1_ffd_static",
    "b2_bestfit_f4_local_search",
    "b3_random_feasible_relocation",
]
LABELS = {
    "proposed_nssao": "NSSAO (proposed)",
    "b1_ffd_static": "B1 FFD static",
    "b2_bestfit_f4_local_search": "B2 Best-Fit+f4 LS",
    "b3_random_feasible_relocation": "B3 Random reloc.",
}
COLORS = {
    "proposed_nssao": "#1f77b4",
    "b1_ffd_static": "#7f7f7f",
    "b2_bestfit_f4_local_search": "#2ca02c",
    "b3_random_feasible_relocation": "#d62728",
}

CELL_ORDER = [
    "A-azure-M", "A-alibaba-M",
    "B-azure-M", "B-alibaba-M", "B-azure-L", "B-alibaba-L",
    "C-azure-M", "C-alibaba-M",
    "D-azure-M", "D-alibaba-M",
    "E-azure-M", "E-alibaba-M",
]
CELL_SHORT = {c: c.replace("-M", "\nM").replace("-L", "\nL") for c in CELL_ORDER}

METRIC_INFO = {
    "f1": ("f1", "f1 = mean(threat x server risk)  [lower is better]"),
    "f2": ("f2", "f2 = cross-entity co-residency risk  [lower is better]"),
    "f3": ("f3", "f3 = active-host cost + migration cost  [lower is better]"),
    "f4": ("f4", "f4 = 1 - overall resource utilization  [lower is better]"),
    "migration_count": ("Migrations", "Migration count (events)"),
    "migration_cost": ("Migration cost", "Migration cost (count x 1.0)"),
    "total_coresidency_duration": (
        "Co-residency duration", "Total co-residency duration (time units)"),
    "coresidency_episode_count": (
        "Episodes", "Co-residency episode count"),
}


def read_analysis():
    """analysis_summary.csv -> {(cell, method): {metric_suffix: value}}."""
    data = {}
    with IN_ANALYSIS.open(newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            key = (row["cell"], row["method"])
            data[key] = row
    return data


def read_cell_method():
    """cell_method_summary.csv -> {(cell, method, seed): {col: value}}."""
    data = {}
    with IN_CELL.open(newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            data[(row["cell"], row["method"], int(row["seed"]))] = row
    return data


def validate(data_cells, data_seed):
    """Structural validation: 12 cells x 4 methods x 5 seeds."""
    problems = []
    for cell in CELL_ORDER:
        for m in METHODS:
            if (cell, m) not in data_cells:
                problems.append(f"missing analysis row {cell}/{m}")
            seeds = [s for (c, mm, s) in data_seed if c == cell and mm == m]
            if sorted(seeds) != [1, 2, 3, 4, 5]:
                problems.append(f"bad seed set {cell}/{m}: {sorted(seeds)}")
    n_cells = len({c for (c, _m) in data_cells})
    n_methods = len({m for (_c, m) in data_cells})
    if n_cells != 12:
        problems.append(f"expected 12 cells, found {n_cells}")
    if n_methods != 4:
        problems.append(f"expected 4 methods, found {n_methods}")
    return problems


def write_table(name, header, rows):
    path = OUT / name
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(rows)
    return path


def grouped_bars(ax, data, metric, agg="median", err=False):
    """Grouped bar chart per cell; one bar group per cell, one bar per
    method. err=True adds min/max whiskers from the analysis CSV."""
    width = 0.2
    xs = range(len(CELL_ORDER))
    for i, m in enumerate(METHODS):
        vals, mins, maxs = [], [], []
        for cell in CELL_ORDER:
            row = data[(cell, m)]
            vals.append(float(row[f"{metric}_{agg}"]))
            mins.append(float(row[f"{metric}_min"]))
            maxs.append(float(row[f"{metric}_max"]))
        pos = [x + (i - 1.5) * width for x in xs]
        if err:
            yerr_lo = [v - lo for v, lo in zip(vals, mins)]
            yerr_hi = [hi - v for v, hi in zip(vals, maxs)]
            ax.bar(pos, vals, width, color=COLORS[m],
                   yerr=[yerr_lo, yerr_hi], capsize=2,
                   error_kw=dict(lw=0.7, alpha=0.6))
        else:
            ax.bar(pos, vals, width, color=COLORS[m])
        ax.set_xticks(list(xs))
        ax.set_xticklabels([CELL_SHORT[c] for c in CELL_ORDER], fontsize=8)
        ax.set_ylabel(METRIC_INFO[metric][0])
        ax.set_title(METRIC_INFO[metric][1], fontsize=10)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    data = read_analysis()
    data_seed = read_cell_method()

    problems = validate(data, data_seed)
    if problems:
        print("VALIDATION FAILED:")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print("Validation OK: 12 cells x 4 methods x 5 seeds present "
          "in stored analysis CSVs")

    created = []

    # --------------------------------------------------------------
    # Figures 1-4: objectives f2, f3, f4, f1 (median + min/max whiskers)
    # --------------------------------------------------------------
    for metric, fname, title in [
        ("f2", "fig1_f2_coresidency_risk",
         "Fig 1: Cross-entity co-residency risk (f2) by cell"),
        ("f3", "fig2_f3_defensive_cost",
         "Fig 2: Defensive cost (f3) by cell"),
        ("f4", "fig3_f4_resource_inefficiency",
         "Fig 3: Resource inefficiency (f4 = 1 - utilization) by cell"),
        ("f1", "fig4_f1_threat_server_risk",
         "Fig 4: Threat x server-risk objective (f1) by cell"),
    ]:
        # Source table for this figure
        rows = []
        for cell in CELL_ORDER:
            for m in METHODS:
                r = data[(cell, m)]
                rows.append([cell, LABELS[m], r[f"{metric}_median"],
                             r[f"{metric}_min"], r[f"{metric}_max"],
                             r[f"{metric}_mean"], r[f"{metric}_sd"]])
        write_table(f"{fname}_source.csv",
                    ["cell", "method", "median", "min", "max", "mean", "sd"],
                    rows)

        fig, ax = plt.subplots(figsize=(11, 4.6))
        grouped_bars(ax, data, metric, err=True)
        ax.legend([LABELS[m] for m in METHODS],
                  loc="upper left", fontsize=8, ncol=2)
        ax.set_title(title, fontsize=11)
        ax.tick_params(axis="x", labelsize=8)
        fig.tight_layout()
        for ext in ("png", "svg"):
            p = OUT / f"{fname}.{ext}"
            fig.savefig(p, dpi=200, metadata={"Date": None})
            created.append(p)
        plt.close(fig)

    # --------------------------------------------------------------
    # Figure 5: migration count by cell
    # --------------------------------------------------------------
    rows = []
    for cell in CELL_ORDER:
        for m in METHODS:
            r = data[(cell, m)]
            rows.append([cell, LABELS[m], r["migration_count_median"],
                         r["migration_count_min"], r["migration_count_max"]])
    write_table("fig5_migration_count_source.csv",
                ["cell", "method", "median", "min", "max"], rows)
    fig, ax = plt.subplots(figsize=(11, 4.4))
    grouped_bars(ax, data, "migration_count")
    ax.legend([LABELS[m] for m in METHODS], loc="upper left",
              fontsize=8, ncol=2)
    ax.set_title("Fig 5: Migration count by cell (per method)", fontsize=11)
    fig.tight_layout()
    for ext in ("png", "svg"):
        p = OUT / f"fig5_migration_count.{ext}"
        fig.savefig(p, dpi=200, metadata={"Date": None})
        created.append(p)
    plt.close(fig)

    # --------------------------------------------------------------
    # Figure 6: multi-objective trade-off (NO overall score)
    #   (a) f2 vs f3 scatter (median per cell/method)
    #   (b) f2 vs f4 scatter
    #   Points: one marker per method; one label per cell; arrows are NOT
    #   used. Shows the Pareto trade-off surface across methods honestly.
    # --------------------------------------------------------------
    rows = []
    for cell in CELL_ORDER:
        for m in METHODS:
            r = data[(cell, m)]
            rows.append([cell, LABELS[m], r["f2_median"], r["f3_median"],
                         r["f4_median"], r["f1_median"]])
    write_table("fig6_tradeoff_source.csv",
                ["cell", "method", "f2_median", "f3_median", "f4_median",
                 "f1_median"], rows)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.8))
    markers = {"proposed_nssao": "o", "b1_ffd_static": "s",
               "b2_bestfit_f4_local_search": "^",
               "b3_random_feasible_relocation": "D"}
    for ax, (xm, ym, xl, yl) in zip(axes, [
            ("f3_median", "f2_median",
             "f3 cost (median, lower is better)",
             "f2 co-residency risk (median, lower is better)"),
            ("f4_median", "f2_median",
             "f4 inefficiency (median, lower is better)",
             "f2 co-residency risk (median, lower is better)")]):
        for m in METHODS:
            xs = [float(data[(c, m)][xm]) for c in CELL_ORDER]
            ys = [float(data[(c, m)][ym]) for c in CELL_ORDER]
            ax.scatter(xs, ys, marker=markers[m], c=COLORS[m], s=42,
                       label=LABELS[m], alpha=0.85, edgecolors="none")
        # annotate a few extreme cells only to avoid clutter
        for cell in ("C-azure-M", "B-alibaba-L", "A-azure-M"):
            ax.annotate(cell,
                        (float(data[(cell, "proposed_nssao")][xm]) * 1.0,
                         float(data[(cell, "proposed_nssao")][ym])),
                        fontsize=6.5, alpha=0.7)
        ax.set_xlabel(xl)
        ax.set_ylabel(yl)
        ax.grid(alpha=0.25)
    axes[0].legend(fontsize=7.5, loc="upper right")
    fig.suptitle("Fig 6: Multi-objective trade-off - no method dominates "
                 "(median across seeds, 12 cells)", fontsize=11)
    fig.tight_layout()
    for ext in ("png", "svg"):
        p = OUT / f"fig6_tradeoff.{ext}"
        fig.savefig(p, dpi=200, metadata={"Date": None})
        created.append(p)
    plt.close(fig)

    # --------------------------------------------------------------
    # Figure 7: Azure vs Alibaba (paired f2 per profile, median)
    # --------------------------------------------------------------
    rows = []
    profiles = ["A", "B", "C", "D", "E"]
    for m in METHODS:
        for p in profiles:
            az = data[(f"{p}-azure-M", m)]
            al = data[(f"{p}-alibaba-M", m)]
            rows.append([p, LABELS[m], az["f2_median"], al["f2_median"]])
    write_table("fig7_azure_vs_alibaba_source.csv",
                ["profile", "method", "azure_f2_median",
                 "alibaba_f2_median"], rows)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), sharey=False)
    width = 0.2
    for ax, ds, title in [(axes[0], "azure", "Azure-derived scores"),
                          (axes[1], "alibaba", "Alibaba-derived scores")]:
        xs = range(len(profiles))
        for i, m in enumerate(METHODS):
            vals = [float(data[(f"{p}-{ds}-M", m)]["f2_median"])
                    for p in profiles]
            pos = [x + (i - 1.5) * width for x in xs]
            ax.bar(pos, vals, width, color=COLORS[m])
        ax.set_xticks(list(xs))
        ax.set_xticklabels([f"{p}\n({name})" for p, name in zip(
            profiles, ["low", "moderate", "high", "co-residency",
                       "resource"])], fontsize=8)
        ax.set_ylabel("f2 co-residency risk (median)")
        ax.set_title(title, fontsize=10)
        ax.grid(axis="y", alpha=0.25)
    handles = [plt.Rectangle((0, 0), 1, 1, color=COLORS[m]) for m in METHODS]
    fig.legend(handles, [LABELS[m] for m in METHODS], loc="upper center",
               ncol=4, fontsize=8, bbox_to_anchor=(0.5, 1.02))
    fig.suptitle("Fig 7: f2 by profile - Azure vs Alibaba (separate "
                 "populations, not pooled)", fontsize=11, y=1.06)
    fig.tight_layout()
    for ext in ("png", "svg"):
        p = OUT / f"fig7_azure_vs_alibaba.{ext}"
        fig.savefig(p, dpi=200, metadata={"Date": None})
        created.append(p)
    plt.close(fig)

    # --------------------------------------------------------------
    # Figure 8: profile-wise behaviour (normalized per profile)
    #   Shows f2 relative reduction of NSSAO vs B1 per profile, plus
    #   f3 relative increase - the observed trade-off, per profile.
    # --------------------------------------------------------------
    rows = []
    for p in profiles:
        for ds in ("azure", "alibaba"):
            cell = f"{p}-{ds}-M"
            f2_b1 = float(data[(cell, "b1_ffd_static")]["f2_median"])
            f2_ns = float(data[(cell, "proposed_nssao")]["f2_median"])
            f3_b1 = float(data[(cell, "b1_ffd_static")]["f3_median"])
            f3_ns = float(data[(cell, "proposed_nssao")]["f3_median"])
            f2_red = (f2_b1 - f2_ns) / f2_b1 if f2_b1 > 0 else 0.0
            f3_inc = (f3_ns - f3_b1) / f3_b1 if f3_b1 > 0 else 0.0
            rows.append([cell, f2_b1, f2_ns, f2_red, f3_b1, f3_ns, f3_inc])
    write_table("fig8_relative_tradeoff_source.csv",
                ["cell", "b1_f2_median", "nssao_f2_median", "f2_reduction",
                 "b1_f3_median", "nssao_f3_median", "f3_increase"], rows)

    fig, ax = plt.subplots(figsize=(10, 4.2))
    xcells = [r[0] for r in rows]
    xs = range(len(xcells))
    width = 0.38
    ax.bar([x - width / 2 for x in xs], [r[3] for r in rows], width,
           color=COLORS["proposed_nssao"],
           label="f2 reduction vs B1 (NSSAO advantage)")
    ax.bar([x + width / 2 for x in xs], [r[6] for r in rows], width,
           color="#ff7f0e", label="f3 increase vs B1 (NSSAO price)")
    ax.set_xticks(list(xs))
    ax.set_xticklabels(xcells, fontsize=8, rotation=20)
    ax.axhline(0, color="black", lw=0.8)
    ax.set_ylabel("Relative change vs B1 (fraction)")
    ax.set_title("Fig 8: Observed trade-off per cell - f2 reduction is "
                 "bought with f3 increase (medians; vs B1 FFD static)",
                 fontsize=10)
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    for ext in ("png", "svg"):
        p = OUT / f"fig8_relative_tradeoff.{ext}"
        fig.savefig(p, dpi=200, metadata={"Date": None})
        created.append(p)
    plt.close(fig)

    print(f"Created {len(created)} figure files:")
    for p in created:
        print("  " + str(p.relative_to(ROOT)))


if __name__ == "__main__":
    main()
