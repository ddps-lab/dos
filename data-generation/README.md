### 1. Generate and Optimize LHS data

- **./generate-and-optimize-lhs-data.sh**
    - Generate LHS data
        - ./raw-lhs-data-generation.py → ../data/raw-lhs-data.csv
    - Optimize LHS data using D-Optimal
        - ./optimal-lhs-data-generation.R → ../data/optimal-lhs-data.csv

### 2. Execute SPMM based on the optimal LHS data

- **SPMM**
    - ../data/optimal-lhs-data.csv → SPMM → ../data/spmm-data.csv

### 3. Split SPMM data into Trainset and Testset

- **./generate-trainset-testset.sh**
    - Extract Trainset Using D-Optimal
        - ./train-set-generation.R → ../data/train-set.csv
    - Extract the rest as Testset
        - ./test-set-generation.py → ../data/test-set.csv
