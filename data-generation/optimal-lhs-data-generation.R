#!/usr/bin/env Rscript

# If AlgDesign is not installed, install it using the command below
# install.packages("AlgDesign")

library(AlgDesign)

# Set output limit
options(max.print=1000000)

# Source data to optimize
input = read.csv("../data/raw-lhs-data/raw-lhs-data.csv")

# Data optimization using DOE
# data: input data
# nTrials: number of extracted data
# nRepeats: number of iterations of the entire process
# criterion: optimization method to use
output = optFederov(data=input, nTrials=1300, nRepeats=5,criterion = "D")

# Change work directory
setwd("../data/")

# Save result matrix as csv
write.csv(output$design, file="optimal-lhs-data.csv", row.names=FALSE)

# Row of result matrix
print("Optimal lhs data generation complete.")
