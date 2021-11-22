# Let It Sparse : train and save prediction model

# Load python packages
# 1. Basic data processing packages
import pandas as pd
import numpy as np
import pickle
# 2. Machine learning packages
from sklearn.preprocessing import MinMaxScaler
# 3. Deep learning package
import tensorflow as tf
# 4. Other Packages
from pathlib import Path

# Load dataset
train = pd.read_csv('../data/train-set.csv')

# Feature and Target settings
X_train = train[['lr','lc','rc','ld','rd','lnnz','rnnz']] 
smsm_y_train = train['smsm_total_latency']
smdm_y_train = train['smdm_total_latency']

# Data scaling
minmax_scaler = MinMaxScaler()
minmax_scaler.fit(X_train)
X_train = minmax_scaler.transform(X_train)

# Network and Compile settings for dnn model
def build_dnn_model(input_shape):
    model=tf.keras.Sequential()
    model.add(tf.keras.layers.Dense(1024, activation="relu", input_shape=input_shape, kernel_initializer='normal')) 
    model.add(tf.keras.layers.Dense(128, activation="relu", kernel_initializer='normal'))
    model.add(tf.keras.layers.Dense(64, activation="relu", kernel_initializer='normal'))
    model.add(tf.keras.layers.Dense(32, activation="relu", kernel_initializer='normal'))
    model.add(tf.keras.layers.Dense(16, activation="relu", kernel_initializer='normal'))
    model.add(tf.keras.layers.Dense(1))
    model.compile(optimizer=tf.keras.optimizers.Adagrad(learning_rate=0.07), loss='mape')
    return model

# Train and Save dnn models
def train_and_save_dnn_models():

    # Train model
    smsm_dnn_model = build_dnn_model((X_train.shape[1],))
    smdm_dnn_model = build_dnn_model((X_train.shape[1],))
    
    callback = tf.keras.callbacks.EarlyStopping(monitor='val_loss', patience=100)
    
    smsm_dnn_model.fit(X_train, smsm_y_train, epochs=1000, validation_split=0.1, verbose=0, callbacks=[callback])
    smdm_dnn_model.fit(X_train, smdm_y_train, epochs=1000, validation_split=0.1, verbose=0, callbacks=[callback])
    
    # Save Model and Scaler
    Path('./model').mkdir(parents=True, exist_ok=True)
    Path('./scaler').mkdir(parents=True, exist_ok=True)
    smsm_dnn_model.save("./model/smsm_dnn_model")
    smdm_dnn_model.save("./model/smdm_dnn_model")
    pickle.dump(minmax_scaler, open('./scaler/minmax_scaler.pkl', 'wb'))

# Execute train and save dnn models
train_and_save_dnn_models()

Print("Successfully execute train set save dnn models")
