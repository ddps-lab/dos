# Dense Or Sparse : inference

# Load python packages
# 1. Basic data processing packages
import numpy as np
import pickle
# 2. Machine learning packages
from sklearn.preprocessing import MinMaxScaler
# 3. Deep learning package
import tensorflow as tf
# 4. Other Packages
import argparse

# Setting Argument
parser = argparse.ArgumentParser()
parser.add_argument('--nr_l', type=int)
parser.add_argument('--nc_l', type=int)
parser.add_argument('--nc_r', type=int)
parser.add_argument('--d_l', type=float)
parser.add_argument('--d_r', type=float)
parser.add_argument('--nnz_l', type=int)
parser.add_argument('--nnz_r', type=int)
args = parser.parse_args()

# Convert argument to variable
NR_L = args.nr_l
NC_L = args.nc_l
NC_R = args.nc_r
D_L = args.d_l
D_R = args.d_r
NNZ_L = args.nnz_l
NNZ_R = args.nnz_r

# Load Model
smsm_dnn_model = tf.keras.models.load_model('./model/smsm_dnn_model')
smdm_dnn_model = tf.keras.models.load_model('./model/smdm_dnn_model')

# Load Scaler
minmax_scaler = pickle.load(open('./scaler/minmax_scaler.pkl','rb'))


def inference(nr_l, nc_l, nc_r, d_l, d_r, nnz_l, nnz_r):
		
	# Create input feature to use as model input
	input_feature = np.array([[nr_l, nc_l, nc_r, d_l, d_r, nnz_l, nnz_r]])
	
	# Apply minmax scaler to input_feature
	input_feature_scaler = minmax_scaler.transform(input_feature)
	
	# Generate model-specific predictions for input feature
	smsm_dnn_result = smsm_dnn_model.predict(input_feature_scaler)
	smdm_dnn_result = smdm_dnn_model.predict(input_feature_scaler)
	
	# If sm*dm is better than sm*sm
	if (smdm_dnn_result[0] <= smsm_dnn_result[0]):
	    optim_method = "sm*dm"
	# If sm*sm is better than sm*dm
	else:
	    optim_method = "sm*sm"
	
	# Generate result
	result = "sm*sm latency : " + str(int(smsm_dnn_result[0])) + "ms , " + \
	"sm*dm latency : " + str(int(smdm_dnn_result[0])) + "ms , " + \
	"optim_method : " + optim_method

	print(result)

# Execute inference
inference(NR_L, NC_L, NC_R, D_L, D_R, NNZ_L, NNZ_R)
