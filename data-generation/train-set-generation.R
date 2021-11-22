#!/usr/bin/env Rscript

# If AlgDesign is not installed, install it using the command below
install.packages("AlgDesign")

library(AlgDesign)

# Set output limit
options(max.print=1000000)

# Source data to optimize
input = read.csv("../data/spmm-data.csv")

# Data optimization using DOE
# data: input data
# nTrials: number of extracted data
# nRepeats: number of iterations of the entire process
# criterion: optimization method to use
output = optFederov(data=input, nTrials=1040, nRepeats=50,criterion = "D")

# Change work directory
setwd("../data/")

# Save result matrix as csv
write.csv(output$design, file="train-set.csv", row.names=FALSE)

print("print train rows")

# Row of result matrix
output$rows
