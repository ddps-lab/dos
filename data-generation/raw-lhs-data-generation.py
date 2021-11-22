# Let It Sparse : raw lhs data generation

# Load python packages
import numpy as np
import pandas as pd
import random
from pyDOE import *

# Generate combinations of LR, LC, RC, LD, RD, LNNZ, and RNNZ using the LSH algorithm.
def lhs_generate():
      
    # We generate 2,500,000 distinct cases of NRL, NCL, and NCR combinations as candidate experiment scenarios.
    sample = 2500000

    # Using the LHS algorithm with a uniform distribution function, 
    # we first generate a floating point value in the range of 0.0 and 1.0 
    # and multiply the maximum value in each dimension to the generated value to determine experimental cases. 
    lr = lhs(1, samples=sample) * 150000
    lc = lhs(1, samples=sample) * 100000
    rc = lhs(1, samples=sample) * 50000

    # We measure the density values using the DBLP, Amazon, Youtube, Orkut, LiveJournal dataset provided by Stanford SNAP dataset which results in the total of 30 left matrix density values.
    ld_list = [0.00108175, 0.00082282, 0.00056263, 0.00034241, 0.00015297, 0.00002088, 0.00163948, 0.00078778, 0.00041097, 0.00019487, 0.00008429, 0.00001651, 0.02533638, 0.00952101, 0.00296184, 0.00082185, 0.00018467, 0.00000464, 0.01084252, 0.00860544, 0.00491597, 0.00160539, 0.00047003, 0.00002483, 0.00370564, 0.00182521, 0.00082487, 0.00031941, 0.00013363, 0.00000434]
    # To reflect the workload characteristics of SPMM in machine learning jobs, we decided to represent the sparsity of a right matrix in an empirical way by incrementally increasing the value. 
    rd_list = [0.0005, 0.001, 0.005, 0.01, 0.03, 0.05, 0.07, 0.1, 0.13, 0.15, 0.17, 0.2,  0.23, 0.25, 0.27, 0.3] 

    # Select random samples from the given one-dimensional array 
    # and generate ld and rd as many as the number of sample
    ld = np.random.choice(ld_list, size=(sample,1))
    rd = np.random.choice(rd_list, size=(sample,1))

    # Calculate lnnz using lr, lc, ld
    lnnz = lr * lc * ld
    # Calculate rnnz using lc, rc, rd
    rnnz = lc * rc * rd

    # Concatenate lr, lc, rc, ld, rd, lnnz, rnnz
    concat_lr_to_rnnz = np.concatenate((lr,lc,rc,ld,rd,lnnz,rnnz), axis = 1)

    # Create DataFrame and Type Conversion
    result_df = pd.DataFrame(concat_lr_to_rnnz,columns=['lr','lc','rc','ld','rd','lnnz','rnnz']).astype({'lr':'int','lc':'int','rc':'int','lnnz':'int','rnnz':'int'})

    return result_df


# All the generated 2,500,000 SPMM cases cannot be executed on an executor node due to the limited available memory size or various system limitations imposed by Apache Spark
# Inexcutable SPMM scenarios need to be removed from offline experiments to avoid unnecessary cost. 
def lhs_filtering(in_df):

    # Apache Spark limits the NNZs of a matrix to the maximum 32bit integer value. 
    intmaxvalue = 2147483647

    # Removed when nnz of left sparsematrix exceeds intMaxValue    
    in_df = in_df[in_df['lnnz'] < intmaxvalue]

    # Removed when nnz of the right densematrix exceeds intMaxValue
    in_df = in_df[(in_df['lc'] * in_df['rc']) < intmaxvalue]

    # Removed when nnz of the result densematrix exceeds intMaxValue
    in_df = in_df[(in_df['lr'] * in_df['rc']) < intmaxvalue]

    # Remove data with lnnz and rnnz greater than 70,000,000 to run on an Executor with a memory size of 32GB
    result_df = in_df[(in_df['lnnz'] < 70000000) & (in_df['rnnz'] < 70000000)]

    return result_df

# Create lhs dataframe and preprocess
lhs_df = lhs_generate()
preprocessed_lhs_df = lhs_filtering(lhs_df)

# Save preprocessed lhs dataframe
preprocessed_lhs_df.to_csv('../data/raw-lhs-data.csv',index=False)
