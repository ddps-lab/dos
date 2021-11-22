#!/bin/bash

Rscript train-set-generation.R
python3 test-set-generation.py

