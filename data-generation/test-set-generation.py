# Let It Sparse : test set generation

# Load python packages
import pandas as pd
import sys
import re

# Total dataset
dataset = pd.read_csv("../data/spmm-data/spmm-data.csv")

# Row number of train set extracted with DOE
train_set = []

# Input from pipeline
for line in sys.stdin:

    # Set up regular expression compilation
    rule = re.compile('[0-9]+')
    # Apply regular expression to line
    temp = rule.findall(line)
    # Collect rows of train set
    for row in temp[1:]:
        train_set.append(int(row)-1)

# Generate data that is not extracted as train set from the entire dataset as test set
test_set = dataset.drop(index=train_set, axis=0)

# Save test set
test_set.to_csv("../data/datasets/test-set.csv",index=False)

print("Data generation complete.")
