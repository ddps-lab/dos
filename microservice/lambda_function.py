# Import package
import json
import numpy as np
import tensorflow as tf
import pickle
from sklearn.preprocessing import MinMaxScaler

# Load Model
smsm_dnn_model = tf.keras.models.load_model('/var/task/dos/dos/model/smsm_dnn_model')
smdm_dnn_model = tf.keras.models.load_model('/var/task/dos/dos/model/smsm_dnn_model')

# Load Scaler
minmax_scaler = pickle.load(open('/var/task/dos/dos/scaler/minmax_scaler.pkl','rb'))

def handler(event, context):
    
    body = event["body-json"]

    # Preprocess features from events
    lr = body["lr"]
    lc = body["lc"]
    rc = body["rc"]
    ld = body["ld"]
    rd = body["rd"]
    lnnz = body["lnnz"]
    rnnz = body["rnnz"]

    # Create input feature to use as model input
    input_feature = np.array([[lr,lc,rc,ld,rd,lnnz,rnnz]])
    
    # Apply minmax scaler to input_feature
    input_feature_scaler = minmax_scaler.transform(input_feature)

    # Generate model-specific predictions for input feature
    smsm_dnn_result = smsm_dnn_model.predict(input_feature_scaler)
    smdm_dnn_result = smdm_dnn_model.predict(input_feature_scaler)

    # If sm*dm is better than sm*sm
    if (smdm_dnn_result[0] <= smsm_dnn_result[0]):
        optim_method = "smdm"
    # If sm*sm is better than sm*dm
    else:
        optim_method = "smsm"
    
		# Generate result
    result = "sm*sm : " + str(smsm_dnn_result[0]) + " , " + \
		"sm*dm : " + str(smdm_dnn_result[0]) + " , " + \
		"optim_method : " + optim_method

		# Return result
    return {
        'statusCode': 200,
        'body': json.dumps(result)
    }
