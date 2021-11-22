# Dense Or Sparse : test prediction mdoel

# Load python packages
# 1. Basic data processing packages
import pandas as pd
import numpy as np
import pickle
import math
# 2. Machine learning packages
from sklearn.preprocessing import MinMaxScaler
from sklearn.metrics import mean_squared_error
# 3. Deep learning package
import tensorflow as tf

# Load dataset
test = pd.read_csv('../data/test-set.csv')

# Feature and Target settings
X_test = test[['lr','lc','rc','ld','rd','lnnz','rnnz']] 
smsm_y_test = test['smsm_total_latency']
smdm_y_test = test['smdm_total_latency']

# Load Scaler
minmax_scaler = pickle.load(open('./scaler/minmax_scaler.pkl','rb'))

# Data scaling
X_test = minmax_scaler.transform(X_test)

# Load Model
smsm_dnn_model = tf.keras.models.load_model('./model/smsm_dnn_model')
smdm_dnn_model = tf.keras.models.load_model('./model/smdm_dnn_model')

# MAPE
def mean_absolute_percentage_error(y_test, y_pred):
    y_test, y_pred = np.array(y_test), np.array(y_pred)
    return np.mean(np.abs((y_test - y_pred) / y_test)) * 100

def test_models():
			
	# Predict testset by model
	smsm_dnn_y_pred = smsm_dnn_model.predict(X_test).reshape(-1,)
	smdm_dnn_y_pred = smdm_dnn_model.predict(X_test).reshape(-1,)
	
	# sm*sm prediction model performance
	print(f'sm*sm prediction model MAPE: {mean_absolute_percentage_error(smsm_y_test, smsm_dnn_y_pred)}')
	print(f'sm*sm prediction mdoel RMSE: {math.sqrt(mean_squared_error(smsm_y_test, smsm_dnn_y_pred))}\n')
	
	# sm*dm prediction model performance
	print(f'sm*dm prediction model MAPE: {mean_absolute_percentage_error(smdm_y_test, smdm_dnn_y_pred)}')
	print(f'sm*dm prediction model RMSE: {math.sqrt(mean_squared_error(smdm_y_test, smdm_dnn_y_pred))}')

# Execute test models
test_models()
