# Let It Sparse : test set generation

# Load python packages
import pandas as pd
import sys
import re

# Total dataset
dataset = pd.read_csv("../data/spmm-data.csv")

# Row number of train set extracted with DOE
train_set = []

train_row_start = False
line_number = 0
# Input from pipeline
for line in sys.stdin:
    
    line_number += 1
    
    if (line_number == 5):
        train_row_start = True
    if (train_row_start == True):
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
test_set.to_csv("../data/test-set.csv",index=False)

print("Successfully generate trainset and testset")
