### 1. Generate LHS data
- ./raw-lhs-data-generation.py → ../data/raw-lhs-data.csv
### 2. Optimizing LHS data using D-Optimal
- ./optimal-lhs-data-generation.R → ../data/optimal-lhs-data.csv
### 3. Execute SPMM based on the optimized LHS data to generate SPMM data
- SPMM based on ../data/optimal-lhs-data.csv → ../data/spmm-data.csv
### 4. Split SPMM data into Trainset and Testset
- Extract Trainset Using D-Optimal
  - ./train-set-generation.R → ../data/train-set.csv
- Extract the rest as Testset
  - ./test-set-generation.py → ../data/test-set.csv
