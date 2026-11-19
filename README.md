# Cloud Security-Aware VM Allocation Using Threat Detection and Multi-Objective Optimization

A threat-aware Virtual Machine (VM) allocation framework that combines machine learning-based workload anomaly detection with multi-objective optimization to improve security in cloud computing environments.

The project uses **Isolation Forest, Adaptive Threat Thresholding, First Fit Decreasing (FFD), NSSAO, Pareto-based selection, and CloudSim 7.0.1** to simulate security-aware VM placement and migration.

---

## Table of Contents

- [Overview](#overview)
- [Research Motivation](#research-motivation)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [Technology Stack](#technology-stack)
- [Datasets](#datasets)
- [Methodology](#methodology)
- [Optimization Objectives](#optimization-objectives)
- [Experimental Evaluation](#experimental-evaluation)
- [Project Structure](#project-structure)
- [Installation and Setup](#installation-and-setup)
- [Running the Project](#running-the-project)
- [Results and Observations](#results-and-observations)
- [Limitations](#limitations)
- [Future Scope](#future-scope)

---

## Overview

Cloud computing environments use virtualization to allow multiple Virtual Machines belonging to different tenants to share the same physical infrastructure.

Although this improves resource utilization, it introduces a security concern known as **VM Co-residency Risk**.

When VMs belonging to different tenants share the same physical host, an attacker may potentially exploit shared hardware resources to perform side-channel attacks.

This project presents a threat-aware VM allocation framework that considers workload anomaly scores during VM placement.

Instead of focusing only on resource utilization, the framework attempts to balance:

- Security risk
- Co-residency risk
- Defensive migration cost
- Resource utilization

The system processes real-world cloud workload traces, generates anomaly-based threat scores, and uses a CloudSim-based simulation environment to evaluate security-aware VM allocation strategies.

**Important:** This is a research-oriented simulation. It does not perform real cloud deployment or prevent actual attacks.

---

## Research Motivation

The project is inspired by the research direction of:

**"A User Threat-Driven Multi-Objective Adaptive VM Allocation Framework for Cloud Security" — IEEE Transactions on Information Forensics and Security (2026).**

Our implementation adapts the research concepts into a working simulation pipeline using publicly available cloud workload datasets.

The implementation includes its own threat-scoring, risk evaluation, optimization, and experimental components. It should not be considered an exact reproduction of the research paper.

---

## Key Features

- Machine learning-based workload anomaly detection using Isolation Forest.
- Threat score generation for cloud workload entities.
- Adaptive thresholding for identifying high-threat workloads.
- Initial VM placement using First Fit Decreasing (FFD).
- Multi-objective optimization using NSSAO.
- Pareto-based selection of non-dominated allocation solutions.
- CloudSim-based simulation of VM allocation and migration.
- Evaluation of security risk, co-residency risk, defensive cost, and resource efficiency.
- Comparative evaluation against three baseline allocation strategies.
- Automated generation of experimental summaries, CSV files, and visualizations.
- End-to-end demonstration using Azure and Alibaba workload scores.

---

## System Architecture

The project follows a multi-stage architecture.

```text
       REAL-WORLD CLOUD WORKLOAD DATASETS
              Azure V2 / Alibaba V2018
                         |
                         v
                DATA PREPROCESSING
                Python / Pandas
                         |
                         v
              FEATURE EXTRACTION
                         |
                         v
              ISOLATION FOREST
              Anomaly Detection
                         |
                         v
                THREAT SCORES
                    (0 to 1)
                         |
                         v
              ADAPTIVE THRESHOLD
              Threat Classification
                         |
                         v
                VM CREATION
                         |
                         v
             INITIAL VM PLACEMENT
                  FFD Algorithm
                         |
                         v
              NSSAO OPTIMIZATION
                         |
                         v
              PARETO-BASED SELECTION
                         |
                         v
                CLOUDSIM 7.0.1
             VM Allocation / Migration
                         |
                         v
                PERFORMANCE
                  EVALUATION
                         |
                         v
              RESULTS AND GRAPHS
```

---

## Technology Stack

| Technology | Purpose |
|---|---|
| Python | Data processing and machine learning |
| Pandas | Dataset manipulation |
| NumPy | Numerical computation |
| Scikit-learn | Isolation Forest implementation |
| Matplotlib | Result visualization |
| Joblib | Model serialization |
| Java | VM allocation and optimization implementation |
| CloudSim 7.0.1 | Cloud infrastructure simulation |
| Maven | Java project build management |
| Git and GitHub | Version control |

### Algorithms Used

| Algorithm / Technique | Purpose |
|---|---|
| Isolation Forest | Detect unusual workload behaviour |
| Adaptive Thresholding | Classify high-threat workloads |
| First Fit Decreasing (FFD) | Generate initial VM placement |
| NSSAO | Multi-objective allocation optimization |
| Pareto Selection | Select non-dominated allocation solutions |

---

## Datasets

The project uses two publicly available cloud workload datasets.

### 1. Microsoft Azure Public Dataset V2

Used to analyze cloud workload behaviour and generate anomaly-based threat scores.

### 2. Alibaba Cluster Trace 2018

Used to evaluate the framework on a second cloud workload dataset.

The datasets provide workload-related information, which is processed to extract features for anomaly detection.

**Dataset consideration:** These traces do not provide the actual physical host placement of every workload. Therefore, physical hosts, VM placement, and co-residency relationships are modelled inside CloudSim.

Raw datasets are not included in this repository because of their size. Processed threat-score files and relevant project artifacts are included where available.

---

## Methodology

### 1. Data Preprocessing

The workload datasets are processed using Python libraries such as Pandas and NumPy.

Relevant workload features are extracted and prepared for anomaly detection.

### 2. Threat Detection Using Isolation Forest

Isolation Forest is used to identify unusual workload behaviour.

The model generates an anomaly-based threat score between 0 and 1.

A higher score indicates more anomalous behaviour according to the trained model.

These scores are not probabilities of malicious activity and do not independently prove that a workload is malicious.

### 3. Adaptive Threat Thresholding

An adaptive threshold is calculated using the distribution of threat scores.

The implementation uses:

```text
Threshold = clamp(P75 + 0.5 × (P90 - P75), 0.5, 0.9)
```

Where:

- P75 represents the 75th percentile.
- P90 represents the 90th percentile.
- Clamp restricts the threshold to the range 0.5–0.9.

Workloads with scores meeting or exceeding the threshold are classified as high-threat workloads.

### 4. Initial VM Placement Using FFD

First Fit Decreasing (FFD) generates an initial VM allocation based on resource requirements.

This provides the starting placement for subsequent optimization.

### 5. Multi-Objective Optimization Using NSSAO

The NSSAO optimizer searches for alternative VM placements while considering multiple objectives.

It evaluates candidate allocations and generates a Pareto front of non-dominated solutions.

### 6. Pareto-Based Selection

Pareto-based selection identifies feasible solutions that balance competing objectives.

The selected allocation is then passed to the simulation stage.

### 7. VM Migration and CloudSim Integration

CloudSim simulates the cloud infrastructure, including:

- Physical hosts
- Virtual machines
- VM placement
- VM migration
- Resource utilization
- Co-residency duration

The selected allocation is applied through the simulation environment.

### 8. Performance Evaluation

The framework calculates four objective values and additional performance metrics to evaluate the allocation strategy.

---

## Optimization Objectives

The framework considers four objectives, all formulated as minimization objectives.

| Objective | Description |
|---|---|
| f1 | Threat-weighted server risk |
| f2 | Co-residency risk |
| f3 | Defensive migration cost |
| f4 | Unused resource capacity |

The objectives represent different aspects of cloud allocation.

For example, reducing co-residency risk may require additional VM migrations, which can increase defensive cost.

Similarly, improving security may result in less resource-efficient placement.

The optimization process therefore attempts to balance security and resource efficiency rather than optimizing only one metric.

---

## Experimental Evaluation

The project includes a systematic experimental evaluation consisting of **240 simulation runs**.

### Experiment Design

The experiment includes:

- 12 scenario cells.
- 4 allocation strategies.
- 5 random seeds per strategy and scenario.

Total:

```text
12 × 4 × 5 = 240 experiments
```

### Allocation Strategies

| Strategy | Description |
|---|---|
| B1 – FFD Static | Initial FFD placement without optimization |
| B2 – Best-Fit + Local Search | Resource-efficiency-oriented baseline |
| B3 – Random Feasible | Randomized allocation control |
| Proposed – NSSAO | Multi-objective security-aware optimization |

All strategies use a common experimental evaluation framework to support fair comparisons.

### Experiment Analysis

The experiment pipeline includes:

- Experiment design
- Experiment execution
- Result collection
- Comparative analysis
- Visualization
- Result interpretation

The results are stored in CSV files and visualized using generated graphs.

---

## Results and Observations

The experimental analysis produced the following observations:

1. **Co-residency Risk:** NSSAO achieved the lowest median f2 value in all 12 scenario cells.

2. **Defensive Cost:** NSSAO had the highest median defensive cost in 11 out of 12 scenario cells, indicating a security-cost trade-off.

3. **Resource Efficiency:** Best-Fit + Local Search performed better on the unused-capacity objective in 10 out of 12 scenario cells.

4. **Pareto Trade-offs:** All 36 representative NSSAO-versus-baseline comparisons showed trade-offs rather than one method dominating the other across all objectives.

5. **Migration Behaviour:** NSSAO performed the most migrations in 8 out of 12 scenario cells.

These findings are descriptive results from the implemented simulation and experimental configuration. They do not establish statistical significance or guarantee the same performance in a real cloud environment.

### Stage 2K – End-to-End Demonstration

Stage 2K provides a live demonstration of the complete allocation pipeline.

It supports running the demonstration on both Azure and Alibaba processed threat-score datasets.

The demonstration displays:

- Selected workload entities
- Threat scores
- Adaptive threshold
- High-threat workload count
- Initial VM placement
- NSSAO optimization
- Pareto-front information
- VM migrations
- CPU and memory utilization
- Co-residency duration
- Four objective values

---

## Project Structure

```text
cloud-security-vm-allocation/
│
├── documentation/
│
├── modules/
│   ├── cloudsim/
│   └── cloudsim-examples/
│       └── src/main/java/org/cloudbus/cloudsim/examples/
│           ├── stage2b/
│           ├── stage2c/
│           ├── stage2d/
│           ├── stage2e/
│           ├── stage2f/
│           ├── stage2g/
│           ├── stage2h/
│           ├── stage2i/
│           └── stage2k/
│               └── Stage2kDemo.java
│
├── project/
│   ├── docs/
│   ├── ml_pipeline/
│   ├── models/
│   ├── outputs/
│   ├── results/
│   └── requirements.txt
│
├── pom.xml
├── README.md
└── .gitignore
```

*The directory structure above highlights the main project components. Individual folders may contain additional implementation and result files.*

---

## Installation and Setup

### Prerequisites

Install the following:

- Git
- Java Development Kit (JDK 21)
- Apache Maven 3.9+
- Python 3.10 or later
- VS Code or IntelliJ IDEA (recommended)

### 1. Clone the Repository

```bash
git clone https://github.com/tanya-2005/cloud-security-vm-allocation.git
```

Navigate into the repository:

```bash
cd cloud-security-vm-allocation
```

### 2. Verify Java and Maven

```bash
java -version
mvn -version
```

Make sure Java 21 is being used.

### 3. Build the CloudSim Project

From the repository root:

```bash
mvn -DskipTests compile -pl modules/cloudsim-examples -am
```

This compiles the CloudSim modules and examples required by the project.

### 4. Set Up Python Dependencies

Create a virtual environment:

```bash
python -m venv .venv
```

Activate it on Windows:

```powershell
.venv\Scripts\Activate.ps1
```

Install the required Python packages:

```bash
pip install -r project/requirements.txt
```

### 5. Dataset Setup

The raw workload datasets are not included in the repository.

Download the required Azure and Alibaba datasets from their respective public sources and follow the preprocessing instructions in the project documentation.

---

## Running the Project

### Stage 2K – End-to-End Demo

After successfully compiling the project, execute the following command from the repository root.

```powershell
java "-Dstdout.encoding=UTF-8" -cp "modules\cloudsim\target\classes;modules\cloudsim-examples\target\classes" org.cloudbus.cloudsim.examples.stage2k.Stage2kDemo both high
```

The command runs the demonstration on both processed datasets using the high-threat workload selection mode.

The output reports the allocation process and corresponding performance metrics.

### Experimental Evaluation – Stage 2I

The Stage 2I implementation contains the experimental framework used for the 240-run evaluation.

The experiment outputs and analysis artifacts are available under:

```text
project/results/stage2i3/
project/results/stage2i4/
project/results/stage2i5/
```

These directories contain experiment records, comparative analysis, validation reports, and generated visualizations.

Refer to the corresponding Stage 2I documentation under `project/docs/` for the detailed experiment workflow.

---

## Important Implementation Notes

- The project uses public workload traces for anomaly detection and workload analysis.
- Actual physical host placement is not provided by the public datasets; it is simulated using CloudSim.
- Alibaba workload entities are batch jobs, and mapping one workload entity to one simulated VM is an implementation abstraction.
- Isolation Forest generates anomaly scores, not verified attacker labels.
- Co-residency risk and server risk are modelled using project-defined evaluation formulas.
- Migration cost and duration are simulation metrics, not measurements from a production cloud deployment.

---

## Limitations

The current implementation has several limitations:

1. Public workload traces do not contain actual physical host placement information.
2. There are no verified attack labels for directly evaluating malicious workload detection.
3. Co-residency and security risks are modelled within a simulation environment.
4. The entity-to-VM mapping is an abstraction of real cloud workload behaviour.
5. The experimental evaluation uses five random seeds per scenario and reports descriptive comparisons.
6. The proposed optimization and baseline implementations are evaluated under the project's configured simulation assumptions.

---

## Future Scope

Potential extensions include:

- Integrating real cloud infrastructure telemetry.
- Evaluating the framework using labelled security datasets.
- Extending the threat detection model with additional machine learning techniques.
- Exploring more advanced multi-objective optimization algorithms.
- Evaluating scalability with larger VM and host configurations.
- Testing the allocation framework in a controlled real-cloud environment.
- Improving migration-cost and co-residency models using real deployment measurements.

---

## Conclusion

This project demonstrates a threat-aware VM allocation approach that combines machine learning-based anomaly detection, adaptive threat classification, and multi-objective optimization.

By integrating NSSAO and Pareto-based selection with CloudSim, the framework evaluates the trade-offs between security risk, defensive cost, and resource utilization.

The experimental findings indicate that considering workload threat scores during VM allocation can reduce modelled co-residency risk, while introducing trade-offs in migration cost and resource efficiency.

The project highlights the importance of balancing security and performance in multi-tenant cloud environments.

---

## Repository

**GitHub:** https://github.com/tanya-2005/cloud-security-vm-allocation

**Developed as an academic research and implementation project.**
