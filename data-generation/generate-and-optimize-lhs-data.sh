#!/bin/bash

python3 raw-lhs-data-generation.py
Rscript optimal-lhs-data-generation.R

echo "Successfully generate and optimize lhs data"
